use std::io::{BufReader, Read};

use http::Uri;

use crate::maven::transport::interface::{
    Error, MavenArtifactRequest, MavenMetadata, MavenMetadataRequest, Transport,
};
use reqwest::blocking::Client;

pub struct HttpTransport {
    base_url: Uri,
    client: Client,
}

impl HttpTransport {
    pub fn new(base_url: Uri) -> HttpTransport {
        HttpTransport {
            base_url,
            client: Client::new(),
        }
    }
}

impl Transport for HttpTransport {
    fn get_artifact(&self, request: MavenArtifactRequest) -> Result<Box<dyn Read>, Error> {
        let classifier_bit = match request.classifier {
            Some(classifier) => format!("-{}", classifier),
            None => "".to_string(),
        };
        self.client
            .get(&format!(
                "{}/{}/{}/{}/{}",
                self.base_url,
                request.group,
                request.name,
                request.path_version,
                format!(
                    "{}-{}{}.{}",
                    request.name, request.file_version, classifier_bit, request.extension
                )
            ))
            .send()
            .and_then(|r| r.error_for_status())
            .map_err(|e| match e.status() {
                Some(reqwest::StatusCode::NOT_FOUND) => Error::NotFound,
                _ => Error::Other(Box::new(e)),
            })
            .map(|r| Box::new(r) as Box<dyn Read>)
    }

    fn get_metadata(&self, request: MavenMetadataRequest) -> Result<MavenMetadata, Error> {
        let version_bit = match request.version {
            Some(version) => format!("{}/", version),
            None => "".to_string(),
        };
        self.client
            .get(&format!(
                "{}/{}/{}/{}maven-metadata.xml",
                self.base_url, request.group, request.name, version_bit,
            ))
            .send()
            .and_then(|r| r.error_for_status())
            .map_err(|e| match e.status() {
                Some(reqwest::StatusCode::NOT_FOUND) => Error::NotFound,
                _ => Error::Other(Box::new(e)),
            })
            .and_then(|r| {
                let metadata: MavenMetadata = quick_xml::de::from_reader(BufReader::new(r))
                    .map_err(|e| Error::Deserialize(e))?;
                Ok(metadata)
            })
    }
}
