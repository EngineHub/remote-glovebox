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