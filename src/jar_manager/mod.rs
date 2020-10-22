use std::fs::File;
use std::io::Read;
use std::ops::Deref;
use std::path::PathBuf;

use actix::{Actor, Context, Handler, Message};
use http::Uri;
use once_cell::sync::Lazy;

use crate::jar_manager::rimfs::InMemoryFs;
use crate::jar_manager::special_cache::SpecialCache;

mod rimfs;
mod special_cache;

static JAR_CACHE: Lazy<PathBuf> = Lazy::new(|| {
    PathBuf::from("./jars")
});

const MAX_CACHE_SIZE: usize = 100_000_000;

fn resolve_jar(request: &JarDataRequest) -> PathBuf {
    let mut copy = PathBuf::from(JAR_CACHE.deref());
    copy.push(&request.group);
    copy.push(&request.name);
    copy.push(format!("{}.jar", request.version));
    return copy;
}

pub struct JarManager {
    maven: Uri,
    cache_fs: SpecialCache<PathBuf, InMemoryFs>,
}


impl JarManager {
    pub fn new(maven: Uri) -> JarManager {
        return JarManager {
            maven,
            cache_fs: SpecialCache::new(MAX_CACHE_SIZE),
        };
    }
}

impl Actor for JarManager {
    type Context = Context<Self>;
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
            )).send()
                .and_then(|r| r.error_for_status())?;
            let mut file = std::fs::File::create(path.clone())?;
            std::io::copy(&mut request, &mut file)?;
        }
        let cache_entry = (&self.cache_fs).get(&path);
        let fs = match cache_entry {
            Some(x) => x,
            None => {
                (&self.cache_fs).add(PathBuf::from(path), InMemoryFs::from_zip(&path)?);
                (&self.cache_fs).get(&path).unwrap()
            }
        };

        Ok(Box::from(fs.reader(msg.path)?))
    }
}

struct JarDataReader<'a> {
    zip_ar: &'a zip::read::ZipArchive<File>,
    file: zip::read::ZipFile<'a>,
}

impl Read for JarDataReader<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        return self.file.read(buf);
    }
}

pub struct JarDataRequest {
    group: String,
    name: String,
    version: String,
    path: String,
}

impl Message for JarDataRequest {
    /// The reader for the data from the JAR, or an I/O error.
    type Result = std::io::Result<Box<dyn Read>>;
}

