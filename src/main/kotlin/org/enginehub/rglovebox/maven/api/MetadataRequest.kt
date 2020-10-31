package org.enginehub.rglovebox.maven.api

data class MetadataRequest(
    val group: String,
    val name: String,
    val version: String? = null,
)