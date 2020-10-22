use std::cmp::min;
use std::collections::HashMap;
use std::io::{ErrorKind, Read};
use std::path::Path;

use crate::jar_manager::special_cache::RuntimeSized;
use actix_web::web::{Bytes, BytesMut};
use bytes::buf::BufMutExt;

pub struct InMemoryFs {
    size: usize,
    data: HashMap<String, Bytes>,
}

impl InMemoryFs {
    pub fn from_zip(path: &Path) -> std::io::Result<InMemoryFs> {
        let mut map = HashMap::<String, Bytes>::new();
        let mut zip_ar = zip::ZipArchive::new(std::fs::File::open(path)?)?;
        for i in 0..zip_ar.len() {
            let mut file = zip_ar.by_index(i).unwrap();
            let mut mut_bytes = BytesMut::with_capacity(file.size() as usize).writer();
            std::io::copy(&mut file, &mut mut_bytes)?;
            map.insert(file.name().into(), mut_bytes.into_inner().freeze());
        }
        Ok(InMemoryFs {
            size: map.iter().fold(0, |a, n| a + n.1.len()),
            data: map,
        })
    }

    pub fn bytes(&self, path: String) -> std::io::Result<Bytes> {
        let data = self.data.get(&path).ok_or_else(|| {
            std::io::Error::new(ErrorKind::NotFound, format!("file {} not found", path))
        })?;
        Ok(data.clone())
    }
}

impl RuntimeSized for InMemoryFs {
    fn size(&self) -> usize {
        self.size
    }
}

pub struct Reader<'a> {
    file_data: &'a Vec<u8>,
    offset: usize,
}

impl Read for Reader<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let remaining = self.file_data.len() - self.offset;
        let len = min(remaining, buf.len());
        buf[..len].copy_from_slice(&self.file_data[self.offset..self.offset + len]);
        self.offset += len;
        Ok(len)
    }
}