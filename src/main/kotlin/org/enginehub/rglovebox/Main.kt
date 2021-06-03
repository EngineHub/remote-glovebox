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

package org.enginehub.rglovebox

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.pipeline.*
import mu.KotlinLogging
import org.enginehub.rglovebox.byteunits.ByteUnit
import org.enginehub.rglovebox.byteunits.ByteValue
import org.enginehub.rglovebox.cache.JarManager
import org.enginehub.rglovebox.clikt.byteValue
import org.enginehub.rglovebox.maven.HttpMavenApi
import org.enginehub.rglovebox.maven.api.NotFoundException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val logger = KotlinLogging.logger { }

fun main(argv: Array<String>) = Main().main(argv)

class Main : CliktCommand(help = "Run remote-glovebox", name = "remote-glovebox") {
    private val host by option(help = "The host to bind to").default("localhost")
    private val port by option(help = "The port to bind to").int().default(58972)
    private val maven by option(help = "The maven site to load from")
        .required()
    private val jarMemCacheSize by option(help = "The amount of memory for the JAR cache, approximately")
        .byteValue()
        .default(ByteValue(BigInteger.valueOf(100), ByteUnit.MEBIBYTE))
    private val httpFsCacheSize by option(help = "The amount of filesystem space for the HTTP cache, approximately")
        .byteValue()
        .default(ByteValue(BigInteger.valueOf(512), ByteUnit.MEBIBYTE))

    init {
        context { helpFormatter = CliktHelpFormatter(maxWidth = 160, showDefaultValues = true) }
    }

    override fun run() {
        logger.info { "Starting server on $host:$port" }
        embeddedServer(Netty, port = port, host = host) {
            module(JarManager(jarMemCacheSize, HttpMavenApi(maven, httpFsCacheSize)))
        }.start(wait = true)
    }
}

fun Application.module(jarManager: JarManager) {
    val notFoundPage = try {
        Files.readString(Path.of("./404.html"))
    } catch (e: NoSuchFileException) {
        "This page does not exist."
    }
    install(StatusPages) {
        exception<Throwable> { cause ->
            logger.warn(cause) { "Unexpected exception in call route" }
            call.respond(HttpStatusCode.InternalServerError, "Internal Server Error.")
        }

        suspend fun PipelineContext<*, ApplicationCall>.handleNotFound(notFoundPage: String) {
            call.respondText(ContentType.Text.Html, HttpStatusCode.NotFound) { notFoundPage }
        }
        exception<MissingJavadocException> {
            handleNotFound(notFoundPage)
        }
        status(HttpStatusCode.NotFound) {
            handleNotFound(notFoundPage)
        }
    }
    install(DefaultHeaders) {
        // have some fun with developers/hackers
        header(HttpHeaders.Server, "Microsoft-IIS/8.5")
        header("X-Content-Type-Options", "nosniff")
    }
    install(CallLogging)
    install(CachingHeaders) {
        options { outgoingContent ->
            when (outgoingContent.contentType?.withoutParameters()) {
                ContentType.Text.CSS, ContentType.Text.JavaScript, ContentType.Text.Html -> CachingOptions(
                    CacheControl.MaxAge(maxAgeSeconds = 3600)
                )
                else -> null
            }
        }
    }

    routing {
        route("/javadoc/{group}/{name}/{version}") {
            get {
                val version = call.parameters["version"]!!
                logger.info { "Redirecting ${call.request.uri}" }
                // we need a slash on the end for jdoc to resolve properly
                call.respondRedirect("./${version}/")
                return@get
            }
            get("/{path...}") {
                serveJarEntry(jarManager)
            }
            get("/{path...}/") {
                serveJarEntry(jarManager)
            }
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.serveJarEntry(
    jarManager: JarManager
) {
    val group = call.parameters["group"]!!
    val name = call.parameters["name"]!!
    val version = call.parameters["version"]!!
    val path = call.parameters.getAll("path")?.joinToString("/") ?: "/"
    if (path == "resources/fonts/dejavu.css") {
        // special-case this, so it doesn't show an error in the network log
        call.respondText("", ContentType.Text.CSS)
        return
    }
    try {
        val entry = jarManager.get(group, name, version, path)

        when (val result = entry.contentVersion.check(call.request.headers)) {
            VersionCheckResult.OK -> {
                // Nothing happens here, we need to proceed
            }
            else -> {
                call.respond(HttpStatusCodeContent(result.statusCode))
                return
            }
        }

        val headers = Headers.build {
            entry.contentVersion.appendHeadersTo(this)
        }

        val responseHeaders = call.response.headers
        headers.forEach { headerName, values ->
            values.forEach { responseHeaders.append(headerName, it) }
        }

        call.respond(ByteArrayContent(entry.bytes, entry.contentType, HttpStatusCode.OK))
    } catch (e: NotFoundException) {
        logger.info(e) { "Didn't find this..." }
        throw MissingJavadocException()
    }
}

