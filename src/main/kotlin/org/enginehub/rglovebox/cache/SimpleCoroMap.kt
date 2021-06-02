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