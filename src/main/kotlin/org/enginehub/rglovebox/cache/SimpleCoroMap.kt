package org.enginehub.rglovebox.cache

import org.enginehub.rglovebox.lock.RWLock
import org.enginehub.rglovebox.lock.withReadLock
import org.enginehub.rglovebox.lock.withWriteLock

class SimpleCoroMap<K, V> {

    private val map = HashMap<K, V>()
    private val rwLock = RWLock()

    suspend fun get(key: K): V? = rwLock.withReadLock { map[key] }

    suspend fun put(key: K, value: V) {
        rwLock.withWriteLock {
            map[key] = value
        }
    }

    suspend fun computeIfAbsent(key: K, func: suspend (key: K) -> V): V {
        val v = get(key)
        if (v != null) {
            return v
        }
        return rwLock.withWriteLock {
            val valueUnderLock = map[key]
            if (valueUnderLock != null) {
                return valueUnderLock
            }
            func(key).also { map[key] = it }
        }
    }

    suspend fun compute(key: K, func: suspend (key: K, value: V?) -> V): V {
        return rwLock.withWriteLock {
            func(key, map[key]).also { map[key] = it }
        }
    }

}