import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework { baseName = "composeApp"; isStatic = true }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content)
                implementation(libs.ktor.serialization)
                implementation(libs.multiplatform.settings)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(libs.vico.compose)
                implementation(libs.vico.compose.m3)
                implementation(libs.kermit.core)
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain = getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.cio)
            }
        }
        val desktopMain = getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.logback.classic)
            }
        }
        val desktopTest = getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.serialization)
            }
        }
        iosMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.serialization)
                implementation(libs.kermit.core)
            }
        }

        // Shared JVM source set for both Android and Desktop
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.serialization)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.cio)
                implementation(libs.jmdns)
                implementation(libs.bouncycastle.bcpkix)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.cio)
                implementation(libs.kermit.io)
            }
        }
        // Wire jvmMain into both Android and Desktop
        androidMain.dependsOn(jvmMain)
        desktopMain.dependsOn(jvmMain)
    }
}

// KMP test tasks (desktopTest) are NOT standard Gradle Test tasks,
// so tasks.withType<Test>() does not match them. Test exclusions
// are done in scripts/run-tests.sh via --tests filters.
//
// Usage:
//   ./scripts/run-tests.sh           -- 18 fast unit tests (~11s warm)
//   ./scripts/run-tests.sh --all     -- 28 tests incl integration (~11s warm)
//   ./gradlew composeApp:desktopTest --no-daemon --tests "app.journal.data.JournalRepositoryTest"
//
// Prerequisites:
//   - gradle.properties sets org.gradle.daemon=false and in-process Kotlin compiler
//   - run-tests.sh raises ulimit -u 16384 (git-bash default 256 is too low)
//   - SessionListViewModelTest is excluded (combine + runBlocking deadlock)

android {
    namespace = "app.journal"
    compileSdk = 36
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].assets.srcDirs("src/androidMain/assets")
    defaultConfig {
        applicationId = "app.journal.nepenthe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,*.properties}" } }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "app.journal.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
            )
            packageName = "Nepenthe Journal"
            packageVersion = "0.1.0"
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
            }
        }
    }
}
