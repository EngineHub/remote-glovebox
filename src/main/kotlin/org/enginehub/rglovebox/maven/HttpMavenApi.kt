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

package org.enginehub.rglovebox.maven

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import org.enginehub.rglovebox.byteunits.ByteUnit
import org.enginehub.rglovebox.byteunits.ByteValue
import org.enginehub.rglovebox.maven.api.*
import java.io.File

class HttpMavenApi(repo: String, fsCacheSize: ByteValue) : MavenApi {

    private val repo = with(HttpUrl.Companion) { repo.toHttpUrl() }
    private val httpClient = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                cache(
                    Cache(
                        File("./cache/maven/"),
                        fsCacheSize.convert(ByteUnit.BYTE).amount.longValueExact()
                    )
                )
                // always hit the network and do conditional GET, it's pretty in-expensive
                addInterceptor {
                    it.proceed(it.request().newBuilder()
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build())
                }
            }
        }
    }
    private val mapper = XmlMapper.builder()
        .addModules(KotlinModule.Builder().build())
        .build()

    override suspend fun getArtifact(request: ArtifactRequest): ByteReadChannel {
        val realReq = fixSnapshot(request)
        val classifierBit = realReq.classifier?.let { "-$it" } ?: ""
        val resolvedUrl = repo.newBuilder()
            .addPathSegments(
                realReq.group.replace('.', '/') +
                        "/${realReq.name}" +
                        "/${realReq.pathVersion}" +
                        "/${realReq.name}-${realReq.fileVersion}$classifierBit.${realReq.extension}"
            )
            .build()
            .toUrl()
        val response = httpClient.get(resolvedUrl)
        if (response.status == HttpStatusCode.NotFound) {
            throw NotFoundException("Received 404 for $realReq ($resolvedUrl)")
        }
        require(response.status.isSuccess()) {
            "Failed to get $request"
        }
        return response.bodyAsChannel()
    }

    private suspend fun fixSnapshot(request: ArtifactRequest): ArtifactRequest {
        if (!request.fileVersion.endsWith("-SNAPSHOT")) {
            return request
        }
        val metadata = getMetadata(MetadataRequest(request.group, request.name, request.pathVersion))
        val snapVersion = metadata.versioning.snapshotVersions.firstOrNull {
            it.classifier == request.classifier && it.extension == request.extension
        }?.value ?: throw NotFoundException("No matching snapshot version for $request")
        return request.copy(fileVersion = snapVersion)
    }

    override suspend fun getMetadata(request: MetadataRequest): MavenMetadata {
        val resolvedUrl = repo.newBuilder()
            .addPathSegments(
                request.group.replace('.', '/') +
                        "/${request.name}" +
                        (request.version?.let { "/$it" } ?: "") +
                        "/maven-metadata.xml"
            )
            .build()
            .toUrl()
        val response = httpClient.get(resolvedUrl)
        if (response.status == HttpStatusCode.NotFound) {
            throw NotFoundException("Received 404 for $request's maven-metadata.xml ($resolvedUrl)")
        }
        require(response.status.isSuccess()) {
            "Failed to get $request"
        }
        return withContext(Dispatchers.IO) {
            response.bodyAsChannel().toInputStream(coroutineContext.job).use {
                mapper.readValue(it)
            }
        }
    }
}
