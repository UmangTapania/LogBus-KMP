import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    listOf(
        iosX64(),               // Intel Mac simulator
        iosArm64(),             // device
        iosSimulatorArm64()     // Apple Silicon simulator
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "LogBus"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "io.github.umangtapania.logbus"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    coordinates("io.github.umangtapania", "logbus", "0.1.0")

    // Uploads to the Central Portal. Needs mavenCentralUsername / mavenCentralPassword
    // (a Portal user token, not the account password) in ~/.gradle/gradle.properties.
    publishToMavenCentral()

    // Central rejects unsigned artifacts, but local verification has no key to sign with,
    // so only engage signing once one is actually configured.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    ) {
        signAllPublications()
    }

    pom {
        name.set("LogBus")
        description.set(
            "A small logging library for Kotlin Multiplatform (Android and iOS). " +
                "Routes logs to any destination, with per-route filtering, formatting and " +
                "ordered background delivery."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/UmangTapania/LogBus-KMP")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("UmangTapania")
                name.set("Umang Tapania")
                url.set("https://github.com/UmangTapania")
            }
        }

        scm {
            url.set("https://github.com/UmangTapania/LogBus-KMP")
            connection.set("scm:git:git://github.com/UmangTapania/LogBus-KMP.git")
            developerConnection.set("scm:git:ssh://git@github.com/UmangTapania/LogBus-KMP.git")
        }
    }
}