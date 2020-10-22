use std::collections::HashMap;
use std::io::BufReader;

use actix::clock::Duration;
use attohttpc::Response;
use http::Uri;
use serde::Deserialize;
use std::time::Instant;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum Error {
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Http(#[from] attohttpc::Error),
    #[error(transparent)]
    Deserialize(#[from] quick_xml::de::DeError),
    #[error("no javadoc snapshot version")]
    MissingJavadocSnapshot,
}

type Result<T> = std::result::Result<T, Error>;

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

pub struct MavenRepo {
    uri: Uri,
    snapshot_map: HashMap<String, (String, Instant)>,
}

impl MavenRepo {
    pub fn new(uri: Uri) -> MavenRepo {
        MavenRepo {
            uri,
            snapshot_map: HashMap::new(),
        }
    }

    pub fn resolve_javadoc(&mut self, coords: MavenUrlCoords) -> Result<Response> {
        attohttpc::get(format!(
            "{}/{}/{}/{}/{}",
            self.uri,
            coords.group,
            coords.name,
            coords.path_version,
            format!("{}-{}-javadoc.jar", coords.name, coords.file_version)
        ))
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(Error::from)
    }

    pub fn resolve_full_coords(&mut self, coords: MavenCoords) -> Result<MavenUrlCoords> {
        let group = coords.group.replace('.', "/");
        Ok(if coords.version.contains("-SNAPSHOT") {
            let snap_ver = self.resolve_snapshot(&group, &coords)?;
            MavenUrlCoords {
                group,
                name: coords.name,
                path_version: coords.version,
                file_version: snap_ver,
            }
        } else {
            MavenUrlCoords {
                group,
                name: coords.name,
                path_version: String::from(&coords.version),
                file_version: coords.version,
            }
        })
    }

    fn resolve_snapshot(&mut self, group: &str, coords: &MavenCoords) -> Result<String> {
        let result = self.snapshot_map.get(&coords.version);
        if let Some((snap_value, check_time)) = result {
            // expire snapshot javadocs after 30 minutes
            if check_time.elapsed() < Duration::from_secs(30 * 60) {
                return Ok(String::from(snap_value));
            }
        }
        let response = attohttpc::get(format!(
            "{}/{}/{}/{}/maven-metadata.xml",
            self.uri, group, &coords.name, &coords.version,
        ))
        .send()
        .and_then(|r| r.error_for_status())?;
        let metadata: MavenMetadata =
            quick_xml::de::from_reader(BufReader::new(response)).map_err(Error::from)?;

        let snap_value = metadata
            .versioning
            .snapshot_versions
            .snapshot_versions
            .into_iter()
            .find(|x| match &x.classifier {
                Some(s) => s == "javadoc",
                None => false,
            })
            .ok_or(Error::MissingJavadocSnapshot)?
            .value;
        self.snapshot_map.insert(
            String::from(&coords.version),
            (String::from(&snap_value), Instant::now()),
        );
        Ok(snap_value)
    }
}

#[derive(Debug, Deserialize)]
struct MavenMetadata {
    versioning: Versioning,
}

#[derive(Debug, Deserialize)]
struct Versioning {
    #[serde(rename = "snapshotVersions")]
    snapshot_versions: SnapshotVersions,
}

#[derive(Debug, Deserialize)]
struct SnapshotVersions {
    #[serde(rename = "snapshotVersion")]
    snapshot_versions: Vec<SnapshotVersion>,
}

#[derive(Debug, Deserialize)]
struct SnapshotVersion {
    classifier: Option<String>,
    value: String,
}
