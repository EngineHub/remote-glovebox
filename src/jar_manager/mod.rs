use std::fs::File;
use std::path::PathBuf;

use actix::{Actor, Context as ActixContext, Handler, Message};
use actix_web::web::Bytes;
use anyhow::Context;
use byte_unit::Byte;
use http::Uri;
use log::debug;
use lru_disk_cache::LruDiskCache;
use once_cell::sync::Lazy;
use thiserror::Error;

use crate::jar_manager::maven_repo::{MavenCoords, MavenRepo, MavenUrlCoords};
use crate::jar_manager::rimfs::InMemoryFs;
use crate::jar_manager::special_cache::SpecialCache;

mod maven_repo;
mod rimfs;
mod special_cache;

static JAR_CACHE: Lazy<PathBuf> = Lazy::new(|| PathBuf::from("./jars"));

fn resolve_jar(request: &MavenUrlCoords) -> String {
    format!(
        "{}/{}/{}.jar",
        request.group, request.name, request.file_version
    )
}

#[derive(Error, Debug)]
pub enum Error {
    #[error("entry not found")]
    NotFound,
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Maven(#[from] crate::jar_manager::maven_repo::Error),
    #[error(transparent)]
    Other(#[from] anyhow::Error),
}

type Result<T> = std::result::Result<T, Error>;

pub struct JarManager {
    maven: MavenRepo,
    fs_cache: LruDiskCache,
    mem_cache: SpecialCache<String, InMemoryFs>,
}

impl JarManager {
    pub fn new(
        maven: Uri,
        mem_cache_size: Byte,
        fs_cache_size: Byte,
    ) -> anyhow::Result<JarManager> {
        Ok(JarManager {
            maven: MavenRepo::new(maven),
            fs_cache: LruDiskCache::new(&*JAR_CACHE, fs_cache_size.get_bytes())
                .context("failed to load LRU disk cache")?,
            mem_cache: SpecialCache::new(mem_cache_size),
        })
    }

    fn cache_file(&mut self, msg: MavenUrlCoords, path: &str) -> Result<File> {
        let mut request = self.maven.resolve_javadoc(msg).map_err(|e| {
            debug!("error processing remote JAR: {}", e);
            Error::NotFound
        })?;
        self.fs_cache
            .insert_with(&path, |mut f| {
                std::io::copy(&mut request, &mut f).map(|_| ())
            })
            .context("Failed to insert into FS cache")?;
        self.fs_cache
            .get_file(&path)
            .context("Failed to get from FS cache")
            .map_err(Error::from)
    }
}

impl Actor for JarManager {
    type Context = ActixContext<Self>;

    fn started(&mut self, ctx: &mut Self::Context) {
        ctx.set_mailbox_capacity(10_000);
    }
}

impl Handler<JarDataRequest> for JarManager {
    type Result = <JarDataRequest as Message>::Result;

    fn handle(&mut self, msg: JarDataRequest, _ctx: &mut Self::Context) -> Self::Result {
        let coords = self.maven.resolve_full_coords(MavenCoords {
            group: String::from(&msg.group),
            name: String::from(&msg.name),
            version: String::from(&msg.version),
        })?;
        let jar_path = resolve_jar(&coords);
        let cache_file = match self.fs_cache.get_file(&jar_path) {
            Ok(file) => file,
            Err(lru_disk_cache::Error::FileNotInCache) => self.cache_file(coords, &jar_path)?,
            Err(e) => {
                return Err(Error::from(
                    anyhow::Error::new(e).context("failed to get from FS cache"),
                ))
            }
        };
        let cache_entry = (&mut self.mem_cache).get(&jar_path);
        let fs = match cache_entry {
            Some(x) => x,
            None => {
                (&mut self.mem_cache)
                    .add(String::from(&jar_path), InMemoryFs::from_zip(&cache_file)?);
                (&mut self.mem_cache).get(&jar_path).unwrap()
            }
        };

        fs.bytes(msg.path).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => Error::NotFound,
            _ => Error::from(anyhow::Error::new(e).context("failed to get from memory cache")),
        })
    }
}

pub struct JarDataRequest {
    pub group: String,
    pub name: String,
    pub version: String,
    pub path: String,
}

impl Message for JarDataRequest {
    /// The reader for the data from the JAR, or an I/O error.
    type Result = Result<Bytes>;
}
