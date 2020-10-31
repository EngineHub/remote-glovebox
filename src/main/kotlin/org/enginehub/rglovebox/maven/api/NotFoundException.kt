package org.enginehub.rglovebox.maven.api

class NotFoundException(override val message: String? = "Not found") : RuntimeException()