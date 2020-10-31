package org.enginehub.rglovebox.maven.api

data class ArtifactRequest(
    val group: String,
    val name: String,
    val pathVersion: String,
    val fileVersion: String = pathVersion,
    val extension: String = "jar",
    val classifier: String? = null,
)