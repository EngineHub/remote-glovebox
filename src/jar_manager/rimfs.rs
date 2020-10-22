use std::cmp::min;
use std::collections::HashMap;
use std::io::{ErrorKind, Read};
use std::path::Path;
use crate::jar_manager::special_cache::RuntimeSized;

pub struct InMemoryFs {
    size: usize,
    data: HashMap<String, Vec<u8>>
}

impl InMemoryFs {
    pub fn from_zip(path: &Path) -> std::io::Result<InMemoryFs> {
        let mut map = HashMap::<String, Vec<u8>>::new();
        let mut zip_ar = zip::ZipArchive::new(std::fs::File::open(path)?)?;
        for i in 0..zip_ar.len() {
            let mut file = zip_ar.by_index(i).unwrap();
            let mut vec = Vec::<u8>::with_capacity(file.size() as usize);
            file.read_exact(vec.as_mut_slice())?;
            map.insert(file.name().into(), vec);
        }
        Ok(InMemoryFs {
            size: map.iter().fold(0, |a, n| a + n.1.len()),
            data: map,
        })
    }

    pub fn reader(&self, path: String) -> std::io::Result<Reader> {
        let data = self.data.get(&path)
            .ok_or_else(|| std::io::Error::new(
                ErrorKind::NotFound, format!("file {} not found", path)
            ))?;
        Ok(Reader { file_data: data, offset: 0 })
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
