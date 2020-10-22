use std::hash::Hash;

use lru::LruCache;

/// A very special cache.
/// When it exceeds capacity-by-entry-size, discards the least recently accessed item.
pub struct SpecialCache<K, V>
    where K: Hash + Eq,
          V: RuntimeSized {
    capacity: usize,
    size: usize,
    cache: LruCache<K, V>,
}

impl<K, V> SpecialCache<K, V>
    where K: Hash + Eq,
          V: RuntimeSized {
    pub fn new(capacity: usize) -> SpecialCache<K, V> {
        SpecialCache {
            capacity,
            size: 0,
            cache: LruCache::unbounded(),
        }
    }

    pub fn get(&mut self, key: &K) -> Option<&V> {
        (&self.cache).get(key)
    }

    /// Add the given entry, evicting older entries if needed
    pub fn add(&mut self, key: K, value: V) {
        while self.size + value.size() > self.capacity {
            // don't loop forever
            if self.size == 0 {
                panic!("Value is bigger than cache size");
            }
            let removed = (&self.cache).pop_lru();
            if let Some((_, value)) = removed {
                self.size -= value.size();
            }
        }
        (&self.cache).put(key, value);
        self.size += value.size()
    }
}

pub trait RuntimeSized {
    fn size(&self) -> usize;
}
