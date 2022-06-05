/*
 * This file is part of remote-glovebox, licensed under GPLv3 or any later version.
 *
 * Copyright (c) EngineHub <https://enginehub.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

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
class LimitedSizeCache<K, V : Sized>(
    private val limit: ByteValue,
    private val onRemoval: (K, V) -> Unit,
) {

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
                    onRemoval(removedKey, removed)
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
