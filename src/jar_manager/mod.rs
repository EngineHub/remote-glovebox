use std::fs::File;
use std::io::ErrorKind;
use std::path::PathBuf;

use actix::{Actor, Context as ActixContext, Handler, Message};
use actix_web::web::Bytes;
use anyhow::Context;
use byte_unit::Byte;
use http::Uri;
use lru_disk_cache::LruDiskCache;
use once_cell::sync::Lazy;

use crate::jar_manager::rimfs::InMemoryFs;
use crate::jar_manager::special_cache::SpecialCache;

mod rimfs;
mod special_cache;

static JAR_CACHE: Lazy<PathBuf> = Lazy::new(|| PathBuf::from("./jars"));

fn resolve_jar(request: &JarDataRequest) -> String {
    format!("{}/{}/{}.jar", request.group, request.name, request.version)
}

pub struct JarManager {
    maven: Uri,
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
            maven,
            fs_cache: LruDiskCache::new(&*JAR_CACHE, fs_cache_size.get_bytes())
                .context("failed to load LRU disk cache")?,
            mem_cache: SpecialCache::new(mem_cache_size),
        })
    }

    fn cache_file(&mut self, msg: &JarDataRequest, path: &str) -> std::io::Result<File> {
        let mut request = attohttpc::get(format!(
            "{}/{}/{}/{}/{}",
            self.maven,
            msg.group.replace('.', "/"),
            msg.name,
            msg.version,
            format!("{}-{}-javadoc.jar", msg.name, msg.version)
        ))
        .send()
        .and_then(|r| r.error_for_status())?;
        self.fs_cache
            .insert_with(&path, |mut f| {
                std::io::copy(&mut request, &mut f).map(|_| ())
            })
            .map_err(|e| std::io::Error::new(ErrorKind::Other, e))?;
        self.fs_cache
            .get_file(&path)
            .map_err(|e| std::io::Error::new(ErrorKind::Other, e))
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
        let jar_path = resolve_jar(&msg);
        let cache_file = match self.fs_cache.get_file(&jar_path) {
            Ok(file) => file,
            Err(lru_disk_cache::Error::FileNotInCache) => self.cache_file(&msg, &jar_path)?,
            Err(e) => return Err(std::io::Error::new(ErrorKind::Other, e)),
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

        fs.bytes(msg.path)
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
    type Result = std::io::Result<Bytes>;
}
