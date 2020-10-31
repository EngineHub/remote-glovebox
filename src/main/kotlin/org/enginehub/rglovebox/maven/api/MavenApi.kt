package org.enginehub.rglovebox.maven.api

import io.ktor.utils.io.*

interface MavenApi {
    /**
     * Get the artifact described by the request.
     *
     * @return a stream of data for the artifact
     */
    suspend fun getArtifact(request: ArtifactRequest): ByteReadChannel

    suspend fun getMetadata(request: MetadataRequest): MavenMetadata
}