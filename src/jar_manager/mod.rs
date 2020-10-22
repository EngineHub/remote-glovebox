use std::ops::Deref;
use std::path::PathBuf;

use actix::{Actor, Context, Handler, Message};
use actix_web::web::Bytes;
use http::Uri;
use once_cell::sync::Lazy;

use crate::jar_manager::rimfs::InMemoryFs;
use crate::jar_manager::special_cache::SpecialCache;

mod rimfs;
mod special_cache;

static JAR_CACHE: Lazy<PathBuf> = Lazy::new(|| PathBuf::from("./jars"));

fn resolve_jar(request: &JarDataRequest) -> PathBuf {
    let mut copy = PathBuf::from(JAR_CACHE.deref());
    copy.push(&request.group);
    copy.push(&request.name);
    copy.push(format!("{}.jar", request.version));
    copy
}

pub struct JarManager {
    maven: Uri,
    cache_fs: SpecialCache<PathBuf, InMemoryFs>,
}

impl JarManager {
    pub fn new(maven: Uri, cache_size: usize) -> JarManager {
        JarManager {
            maven,
            cache_fs: SpecialCache::new(cache_size),
        }
    }
}

impl Actor for JarManager {
    type Context = Context<Self>;

    fn started(&mut self, ctx: &mut Self::Context) {
        ctx.set_mailbox_capacity(10_000);
    }
}

impl Handler<JarDataRequest> for JarManager {
    type Result = <JarDataRequest as Message>::Result;

    fn handle(&mut self, msg: JarDataRequest, _ctx: &mut Self::Context) -> Self::Result {
        let path = resolve_jar(&msg);
        if !path.exists() {
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
            std::fs::create_dir_all(path.parent().unwrap())?;
            let mut file = std::fs::File::create(path.clone())?;
            std::io::copy(&mut request, &mut file)?;
        }
        let cache_entry = (&mut self.cache_fs).get(&path);
        let fs = match cache_entry {
            Some(x) => x,
            None => {
                (&mut self.cache_fs).add(PathBuf::from(&path), InMemoryFs::from_zip(&path)?);
                (&mut self.cache_fs).get(&path).unwrap()
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
