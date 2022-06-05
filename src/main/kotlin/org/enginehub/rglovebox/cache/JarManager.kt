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

import com.google.common.collect.ImmutableSet
import com.google.common.hash.Hashing
import com.google.common.io.ByteStreams
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.enginehub.rglovebox.MissingJavadocException
import org.enginehub.rglovebox.byteunits.ByteValue
import org.enginehub.rglovebox.maven.api.*
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger { }

class JarManager(
    memCacheSize: ByteValue,
    private val mavenApi: MavenApi
) {
    private val versionMap = SimpleCoroMap<VersionCacheKey, ResolvedVersion>()
    private val jarMap = LimitedSizeCache<JarCacheKey, SimpleFSWrapper>(memCacheSize, onRemoval = { _, remValue ->
        remValue.close()
    })

    suspend fun get(group: String, name: String, version: String, path: String): SendableJarEntry {
        val request = resolveRequest(group, name, version)
        val jarCacheKey = JarCacheKey(request.group, request.name, request.fileVersion)
        jarMap.getOrFill(jarCacheKey) { cacheKey ->
            withContext(Dispatchers.IO) {
                mavenApi.getArtifact(request).toInputStream(coroutineContext.job).use {
                    loadZipFs("${cacheKey.group}:${cacheKey.name}:${cacheKey.version}", it)
                }
            }
        }.use { borrowedFs ->
            var p = borrowedFs.fs.getPath(path)
            if (Files.isDirectory(p)) {
                // try with index.html
                p = p.resolve("index.html")
            }
            if (!Files.isRegularFile(p)) {
                throw MissingJavadocException()
            }
            // This doesn't actually do any I/O, so there's no need to switch
            val bytes = Files.readAllBytes(p)
            val hash = Hashing.murmur3_128().hashBytes(bytes).toString()
            return SendableJarEntry(
                bytes,
                when (p.extension) {
                    "html" -> ContentType.Text.Html
                    "css" -> ContentType.Text.CSS
                    "js" -> ContentType.Text.JavaScript
                    else -> ContentType.Application.OctetStream
                },
                contentVersion = EntityTagVersion(hash)
            )
        }
    }

    private suspend fun resolveRequest(group: String, name: String, version: String): ArtifactRequest {
        when (version.lowercase(Locale.ROOT)) {
            "release" -> return resolveKindToRequest(group, name, VersionKind.RELEASE)
            "snapshot" -> return resolveKindToRequest(group, name, VersionKind.SNAPSHOT)
        }
        return ArtifactRequest(group, name, version, classifier = "javadoc")
    }

    private suspend fun resolveKindToRequest(group: String, name: String, kind: VersionKind): ArtifactRequest {
        val version = versionMap.compute(VersionCacheKey(group, name, kind)) { key, existingValue ->
            // if it's good, re-use it
            if (existingValue != null && existingValue.expireTime.isAfter(Instant.now())) {
                return@compute existingValue
            }
            // re-compute the version
            try {
                val metadata = mavenApi.getMetadata(MetadataRequest(group, name))
                kind.extractVersion(metadata).also {
                    if (it is ResolvedVersion.None) {
                        throw NotFoundException("Not found in downloaded maven-metadata.xml")
                    }
                }
            } catch (e: NotFoundException) {
                logger.info { "No maven-metadata.xml for $key was available: ${e.message}" }
                ResolvedVersion.None()
            }
        }
        return when (version) {
            is ResolvedVersion.None -> throw MissingJavadocException()
            is ResolvedVersion.Some -> resolveRequest(group, name, version.version)
        }
    }
}

internal sealed class ResolvedVersion {
    val expireTime: Instant = Instant.now().plus(30, ChronoUnit.MINUTES)
    class None : ResolvedVersion()
    class Some(val version: String) : ResolvedVersion()
}

internal class SimpleFSWrapper(override val size: Long, val fs: FileSystem) : Sized, AutoCloseable by fs

internal fun loadZipFs(name: String, content: InputStream): SimpleFSWrapper {
    var size = 0L
    val fs = Jimfs.newFileSystem(
        name.replace(':', '-').replace('.', '-'),
        Configuration.unix().toBuilder().setWorkingDirectory("/").build()
    )
    ZipInputStream(content).use { zipReader ->
        while (true) {
            val next = zipReader.nextEntry ?: break
            val resolved: Path = fs.getPath(next.name)
            if (next.isDirectory) {
                Files.createDirectories(resolved)
            } else {
                Files.newByteChannel(
                    resolved,
                    ImmutableSet.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                ).use { channel ->
                    ByteStreams.copy(
                        Channels.newChannel(zipReader),
                        channel
                    )
                }
                size += next.size
            }
        }
    }
    return SimpleFSWrapper(size, fs)
}

internal enum class VersionKind {
    RELEASE {
        override fun extractVersion(metadata: MavenMetadata): ResolvedVersion {
            return metadata.versioning.release?.let { ResolvedVersion.Some(it) } ?: ResolvedVersion.None()
        }
    },
    SNAPSHOT {
        override fun extractVersion(metadata: MavenMetadata): ResolvedVersion {
            return metadata.versioning.latest?.let { ResolvedVersion.Some(it) } ?: ResolvedVersion.None()
        }
    },
    ;

    abstract fun extractVersion(metadata: MavenMetadata): ResolvedVersion
}

internal data class VersionCacheKey(
    val group: String,
    val name: String,
    val kind: VersionKind,
)

internal data class JarCacheKey(
    val group: String,
    val name: String,
    val version: String,
)
