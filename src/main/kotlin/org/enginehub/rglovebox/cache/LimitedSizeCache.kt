package org.enginehub.rglovebox.cache

import com.google.common.collect.ConcurrentHashMultiset
import mu.KotlinLogging
import org.enginehub.rglovebox.byteunits.ByteUnit
import org.enginehub.rglovebox.byteunits.ByteValue
import org.enginehub.rglovebox.lock.RWLock
import org.enginehub.rglovebox.lock.withReadLock
import org.enginehub.rglovebox.lock.withWriteLock

private val logger = KotlinLogging.logger { }

/**
 * If the [limit] is exceeded by the sum of values, drops the least-recently-used value.
 */
class LimitedSizeCache<K, V : Sized>(private val limit: ByteValue) {

    private val maxSize = limit.convert(ByteUnit.BYTE).amount.longValueExact()
    private var size: Long = 0L
    private val cache = LinkedHashMap<K, V>(16, 0.75F, true)
    private val borrowed = ConcurrentHashMultiset.create<K>()
    private val rwLock = RWLock()

    private inner class BorrowedItem(private val key: K, override val value: V) : Borrowed<V> {
        override suspend fun release() {
            // "read" lock is fine here, we just need to know that no one else is writing
            rwLock.withReadLock {
                borrowed.remove(key)
            }
        }
    }

    private fun borrow(key: K, value: V): Borrowed<V> {
        borrowed.add(key)
        return BorrowedItem(key, value)
    }

    suspend fun getOrFill(key: K, func: suspend (key: K) -> V): Borrowed<V> {
        // fast path: already in cache, just return
        rwLock.withReadLock {
            cache[key]?.let { return borrow(key, it) }
        }
        // slow path: check cache again, then load, insert, clean, etc.
        rwLock.withWriteLock {
            cache[key]?.let { return borrow(key, it) }
            val newValue = func(key)
            require(newValue.size < maxSize) {
                "New value size exceeds cache size, cannot cache"
            }
            size += newValue.size
            cache[key] = newValue
            // this is going to be used soon, so hold on to it!
            val borrowedItem = borrow(key, newValue)
            if (size > maxSize) {
                val iter = cache.iterator()
                require(iter.hasNext()) {
                    "Cache exceeded size without anything in it?"
                }
                do {
                    // the least-recent item is always first in iteration order
                    val (removedKey, removed) = iter.next()
                    if (borrowed.contains(removedKey)) {
                        // this is currently being used, can't remove it
                        continue
                    }
                    iter.remove()
                    size -= removed.size
                    (removed as? AutoCloseable)?.close()
                } while (size > maxSize || iter.hasNext())
            }
            return borrowedItem
        }
    }

}

interface Sized {
    val size: Long
}

interface Borrowed<V> {
    val value: V

    suspend fun release()
}

suspend inline fun <V, R> Borrowed<V>.use(block: (value: V) -> R) : R {
    try {
        return block(value)
    } finally {
        release()
    }
}