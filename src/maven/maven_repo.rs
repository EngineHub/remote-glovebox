use std::cmp::Reverse;
use std::collections::HashMap;
use std::io::Read;
use std::time::Instant;

use actix::clock::Duration;
use thiserror::Error;

use crate::maven::transport::interface::{MavenArtifactRequest, MavenMetadataRequest, Transport};

#[derive(Error, Debug)]
pub enum Error {
    #[error(transparent)]
    Transport(#[from] crate::maven::transport::interface::Error),
    #[error("no javadoc could be found")]
    MissingJavadoc,
}

type Result<T> = std::result::Result<T, Error>;

#[derive(Clone)]
pub struct MavenCoords {
    pub group: String,
    pub name: String,
    pub version: String,
}

pub struct MavenUrlCoords {
    pub group: String,
    pub name: String,
    /// The version in the path of the URL
    pub path_version: String,
    /// The version embedded in the filename of the URL
    pub file_version: String,
}

#[derive(Copy, Clone, Hash, PartialEq, Eq)]
enum VersionKind {
    Release,
    Snapshot,
}

impl VersionKind {
    fn matches_version(&self, version: &str) -> bool {
        let is_snap = matches!(self, VersionKind::Snapshot);
        is_snap == version.contains("-SNAPSHOT")
    }
}

#[derive(Hash, PartialEq, Eq)]
struct VersionCacheKey {
    group: String,
    name: String,
    kind: VersionKind,
}

#[derive(Hash, PartialEq, Eq)]
struct SnapshotCacheKey {
    group: String,
    name: String,
    version: String,
}

struct CacheEntry {
    write_time: Instant,
    version: String,
}

pub struct MavenRepo<T: Transport> {
    transport: T,
    version_map: HashMap<VersionCacheKey, CacheEntry>,
    snapshot_map: HashMap<SnapshotCacheKey, CacheEntry>,
}

impl<T: Transport> MavenRepo<T> {
    pub fn new(transport: T) -> MavenRepo<T> {
        MavenRepo {
            transport,
            version_map: HashMap::new(),
            snapshot_map: HashMap::new(),
        }
    }

    pub fn resolve_javadoc(&mut self, coords: MavenUrlCoords) -> Result<Box<dyn Read>> {
        self.transport
            .get_artifact(MavenArtifactRequest {
                group: coords.group,
                name: coords.name,
                path_version: coords.path_version,
                file_version: coords.file_version,
                classifier: Some("javadoc".to_string()),
                extension: "jar".to_string(),
            })
            .map_err(|e| match e {
                crate::maven::transport::interface::Error::NotFound => Error::MissingJavadoc,
                _ => Error::Transport(e),
            })
    }

    pub fn resolve_full_coords(&mut self, coords: MavenCoords) -> Result<MavenUrlCoords> {
        let group = coords.group.replace('.', "/");
        match coords.version.to_uppercase().as_str() {
            version if version.contains("-SNAPSHOT") => {
                let snap_ver = self.resolve_snapshot(&group, &coords)?;
                Ok(MavenUrlCoords {
                    group,
                    name: coords.name,
                    path_version: coords.version,
                    file_version: snap_ver,
                })
            }
            kind_str @ "RELEASE" | kind_str @ "SNAPSHOT" => {
                let version = self.find_latest_version(
                    &group,
                    &coords,
                    match kind_str {
                        "RELEASE" => VersionKind::Release,
                        "SNAPSHOT" => VersionKind::Snapshot,
                        _ => panic!("Impossible to reach"),
                    },
                )?;
                let mut coords_clone = coords.clone();
                coords_clone.version = version;
                // re-resolve, with the newly discovered version
                self.resolve_full_coords(coords_clone)
            }
            _ => Ok(MavenUrlCoords {
                group,
                name: coords.name,
                path_version: coords.version.clone(),
                file_version: coords.version,
            }),
        }
    }

    fn resolve_snapshot(&mut self, group: &str, coords: &MavenCoords) -> Result<String> {
        let cache_key = SnapshotCacheKey {
            group: coords.group.clone(),
            name: coords.name.clone(),
            version: coords.version.clone(),
        };
        let result = self.snapshot_map.get(&cache_key);
        if let Some(entry) = result {
            // expire snapshot javadocs after 30 minutes
            if entry.write_time.elapsed() < Duration::from_secs(30 * 60) {
                return Ok(entry.version.clone());
            }
        }
        let metadata = self
            .transport
            .get_metadata(MavenMetadataRequest {
                group: group.to_string(),
                name: coords.name.clone(),
                version: Some(coords.version.clone()),
            })
            .map_err(|e| match e {
                crate::maven::transport::interface::Error::NotFound => Error::MissingJavadoc,
                _ => Error::Transport(e),
            })?;

        let snap_value = metadata
            .versioning
            .snapshot_versions
            .ok_or(Error::MissingJavadoc)?
            .snapshot_versions
            .into_iter()
            .find(|x| match &x.classifier {
                Some(s) => s == "javadoc",
                None => false,
            })
            .ok_or(Error::MissingJavadoc)?
            .value;
        self.snapshot_map.insert(
            cache_key,
            CacheEntry {
                write_time: Instant::now(),
                version: snap_value.clone(),
            },
        );
        Ok(snap_value)
    }

    fn find_latest_version(
        &mut self,
        group: &str,
        coords: &MavenCoords,
        kind: VersionKind,
    ) -> Result<String> {
        let cache_key = VersionCacheKey {
            group: coords.group.clone(),
            name: coords.name.clone(),
            kind,
        };
        let result = self.version_map.get(&cache_key);
        if let Some(entry) = result {
            // expire version mapping after 30 minutes
            if entry.write_time.elapsed() < Duration::from_secs(30 * 60) {
                return Ok(entry.version.clone());
            }
        }

        let mut versions = self
            .transport
            .get_metadata(MavenMetadataRequest {
                group: group.to_string(),
                name: coords.name.clone(),
                version: None,
            })?
            .versioning
            .versions
            .ok_or(Error::MissingJavadoc)?
            .versions
            .into_iter()
            .filter(|v| kind.matches_version(v))
            .map(|s| semver::Version::parse(s.as_str()).map(|v| (s, v)))
            .filter_map(|r| r.ok())
            .collect::<Vec<_>>();
        versions.sort_unstable_by_key(|v| Reverse(v.1.clone()));
        let version = versions.into_iter().next().ok_or(Error::MissingJavadoc)?.0;
        self.version_map.insert(
            cache_key,
            CacheEntry {
                write_time: Instant::now(),
                version: version.clone(),
            },
        );
        Ok(version)
    }
}
