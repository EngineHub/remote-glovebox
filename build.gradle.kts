plugins {
    kotlin("jvm") version "1.9.0"
    application
    id("org.cadixdev.licenser") version "0.6.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

license {
    ext {
        set("name", project.name)
        set("organization", project.property("organization"))
        set("url", project.property("url"))
    }
    header(rootProject.file("HEADER.txt"))
}

kotlin.target.compilations.configureEach {
    kotlinOptions {
        jvmTarget = "17"
        options.optIn = listOf("kotlin.RequiresOptIn")
    }
}

application.mainClass.set("org.enginehub.rglovebox.MainKt")

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(kotlin("bom")))
    implementation(kotlin("stdlib-jdk8"))

    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.2"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")

    implementation(platform("io.ktor:ktor-bom:2.3.2"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-caching-headers")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-okhttp")

    implementation("com.github.ajalt.clikt:clikt:4.1.0")

    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("ch.qos.logback:logback-classic:1.4.8")
    implementation("ch.qos.logback:logback-core:1.4.8")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    implementation("com.google.guava:guava:32.1.1-jre")
    implementation("com.google.jimfs:jimfs:1.3.0")
    implementation("org.mapdb:mapdb:3.0.9")

    implementation(platform("com.fasterxml.jackson:jackson-bom:2.15.2"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    runtimeOnly("com.fasterxml.woodstox:woodstox-core:6.5.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
