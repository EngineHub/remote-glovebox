package org.enginehub.rglovebox.maven.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class MavenMetadata(
    val versioning: Versioning,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Versioning(
    val latest: String?,
    val release: String?,
    val snapshotVersions: List<SnapshotVersion> = listOf(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SnapshotVersion(
    val value: String,
    val classifier: String?,
    val extension: String?,
)