use std::io::Read;

use serde::Deserialize;
use thiserror::Error;

pub struct MavenArtifactRequest {
    pub group: String,
    pub name: String,
    // Yes, you must resolve path & file version first
    pub path_version: String,
    pub file_version: String,
    pub classifier: Option<String>,
    pub extension: String,
}

pub struct MavenMetadataRequest {
    pub group: String,
    pub name: String,
    /// If present, fetches metadata for the specific version.
    pub version: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct MavenMetadata {
    pub versioning: Versioning,
}

#[derive(Debug, Deserialize)]
pub struct Versioning {
    pub versions: Option<Versions>,
    #[serde(rename = "snapshotVersions")]
    pub snapshot_versions: Option<SnapshotVersions>,
}

#[derive(Debug, Deserialize)]
pub struct Versions {
    #[serde(rename = "version")]
    pub versions: Vec<String>,
}

#[derive(Debug, Deserialize)]
pub struct SnapshotVersions {
    #[serde(rename = "snapshotVersion")]
    pub snapshot_versions: Vec<SnapshotVersion>,
}

#[derive(Debug, Deserialize)]
pub struct SnapshotVersion {
    pub classifier: Option<String>,
    pub value: String,
}

#[derive(Error, Debug)]
pub enum Error {
    #[error("item not found")]
    NotFound,
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Other(#[from] Box<dyn std::error::Error + Send>),
    #[error(transparent)]
    Deserialize(#[from] quick_xml::de::DeError),
}

pub trait Transport {
    fn get_artifact(&self, request: MavenArtifactRequest) -> Result<Box<dyn Read>, Error>;

    fn get_metadata(&self, request: MavenMetadataRequest) -> Result<MavenMetadata, Error>;
}
