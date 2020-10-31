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
import okhttp3.HttpUrl
import org.enginehub.rglovebox.byteunits.ByteUnit
import org.enginehub.rglovebox.byteunits.ByteValue
import org.enginehub.rglovebox.maven.api.*
import java.io.File
import kotlin.coroutines.coroutineContext

class HttpMavenApi(repo: String, fsCacheSize: ByteValue) : MavenApi {

    private val repo = with(HttpUrl.Companion) { repo.toHttpUrl() }
    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                cache(
                    Cache(
                        File("./cache/maven/"),
                        fsCacheSize.convert(ByteUnit.BYTE).amount.longValueExact()
                    )
                )
            }
        }
    }
    private val mapper = XmlMapper.builder()
        .addModules(KotlinModule())
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
        val response = httpClient.get<HttpResponse>(resolvedUrl)
        if (response.status == HttpStatusCode.NotFound) {
            throw NotFoundException("Received 404 for $realReq ($resolvedUrl)")
        }
        require(response.status.isSuccess()) {
            "Failed to get $request"
        }
        return response.content
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
        val response = httpClient.get<HttpResponse>(resolvedUrl)
        if (response.status == HttpStatusCode.NotFound) {
            throw NotFoundException("Received 404 for $request's maven-metadata.xml ($resolvedUrl)")
        }
        require(response.status.isSuccess()) {
            "Failed to get $request"
        }
        return response.content.toInputStream(coroutineContext.job).use {
            withContext(Dispatchers.IO) {
                mapper.readValue(it)
            }
        }
    }
}