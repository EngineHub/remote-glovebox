plugins {
    val kotlinVersion = "1.5.10"
    kotlin("jvm") version kotlinVersion
    application
    id("org.cadixdev.licenser") version "0.6.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(16))
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
        jdkHome = javaToolchains.compilerFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
    }
}

application.mainClass.set("org.enginehub.rglovebox.MainKt")

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(kotlin("bom")))
    implementation(kotlin("stdlib-jdk8"))

    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.5.0"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")

    implementation(platform("io.ktor:ktor-bom:1.6.0"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-okhttp")

    implementation("com.github.ajalt.clikt:clikt:3.2.0")

    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("ch.qos.logback:logback-core:1.2.3")
    implementation("io.github.microutils:kotlin-logging-jvm:2.0.8")

    implementation("com.google.guava:guava:30.1.1-jre")
    implementation("com.google.jimfs:jimfs:1.2")
    implementation("org.mapdb:mapdb:3.0.8")

    implementation(platform("com.fasterxml.jackson:jackson-bom:2.12.3"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    runtimeOnly("com.fasterxml.woodstox:woodstox-core:6.2.6")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
