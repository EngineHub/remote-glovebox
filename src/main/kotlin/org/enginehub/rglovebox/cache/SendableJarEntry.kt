package org.enginehub.rglovebox.cache

import io.ktor.http.*
import io.ktor.http.content.*

class SendableJarEntry(
    val bytes: ByteArray,
    val contentType: ContentType,
    val contentVersion: Version,
)
