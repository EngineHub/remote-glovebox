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

package org.enginehub.rglovebox.lock

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A dead-simple RW lock, based on the two-mutex version from Wikipedia.
 */
class RWLock {

    private val readLock = Mutex()
    private val globalLock = Mutex()
    private var readers = 0

    suspend fun lockRead() {
        readLock.withLock {
            readers++
            if (readers == 1) {
                globalLock.lock()
            }
        }
    }

    suspend fun unlockRead() {
        readLock.withLock {
            readers--
            if (readers == 0) {
                globalLock.unlock()
            }
        }
    }

    suspend fun lockWrite() {
        globalLock.lock()
    }

    // we might use suspend in the future, so for API compatibility, we'll mark it suspend
    @Suppress("RedundantSuspendModifier")
    suspend fun unlockWrite() {
        globalLock.unlock()
    }
}

suspend inline fun <R> RWLock.withReadLock(block: () -> R): R {
    lockRead()
    try {
        return block()
    } finally {
        unlockRead()
    }
}

suspend inline fun <R> RWLock.withWriteLock(block: () -> R): R {
    lockWrite()
    try {
        return block()
    } finally {
        unlockWrite()
    }
}