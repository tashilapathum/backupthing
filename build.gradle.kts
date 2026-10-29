plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.spotless)
    application
}

group = "io.github.tashilapathum"
// Release builds pass -PreleaseVersion=x.y.z (derived from the git tag in CI).
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktoml.core)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.logback.classic)
    implementation(libs.clikt)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.status.pages)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "io.github.tashilapathum.backupthing.MainKt"
    applicationName = "backupthing"
}

sqldelight {
    databases {
        create("BackupDatabase") {
            packageName = "io.github.tashilapathum.backupthing.db"
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeader("// SPDX-License-Identifier: GPL-3.0-or-later\n\n")
    }
    kotlinGradle {
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("backupthing.properties") { expand("version" to v) }
}

distributions {
    main {
        contents {
            from("LICENSE", "README.md", "CHANGELOG.md", "THIRD_PARTY_NOTICES")
            from("packaging/install.sh") {
                filter<org.apache.tools.ant.filters.ReplaceTokens>(
                    "tokens" to mapOf("version" to project.version.toString()),
                )
                filePermissions { unix("rwxr-xr-x") }
            }
            from("packaging/systemd") { into("share/systemd/user") }
            from("packaging/waybar") { into("share/waybar") }
            from("packaging/desktop") { into("share/applications") }
            from("assets/logo.svg") {
                into("share/icons/hicolor/scalable/apps")
                rename { "backupthing.svg" }
            }
        }
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension = "tar.gz"
}

val fixturesDir = layout.buildDirectory.dir("fixtures")
val generateFixtures by tasks.registering(Exec::class) {
    description = "Generates synthetic test media (needs ffmpeg, ImageMagick, exiv2)."
    inputs.file("scripts/gen-fixtures.sh")
    outputs.dir(fixturesDir)
    commandLine("bash", "scripts/gen-fixtures.sh", fixturesDir.get().asFile.absolutePath)
}

tasks.test {
    dependsOn(generateFixtures)
    systemProperty("fixtures.dir", fixturesDir.get().asFile.absolutePath)
    useJUnitPlatform {
        // GPU encoders are not available in CI: ./gradlew test -Pgpu
        if (!project.hasProperty("gpu")) excludeTags("gpu")
    }
}
