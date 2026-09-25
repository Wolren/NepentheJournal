import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.kover)
}

// Force latest stable Netty to fix Dependabot vulnerabilities
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("io.netty")) {
            useVersion("4.2.17.Final")
        }
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework { baseName = "composeApp"; isStatic = true }
        // SecKeyCopyKeyExchangeResult is missing from Kotlin's platform.Security bindings;
        // bind it from the SDK header under its own package. The CommonCrypto GCM
        // oneshot SPI has no public SDK header, so it is declared by a local shim.
        iosTarget.compilations.getByName("main").cinterops.create("securityEx") {
            defFile(project.file("src/nativeInterop/cinterop/SecurityEx.def"))
            packageName = "platform.SecurityEx"
        }
        iosTarget.compilations.getByName("main").cinterops.create("ccGcm") {
            defFile(project.file("src/nativeInterop/cinterop/CCGcm.def"))
            packageName = "platform.CCCryptoGcm"
            compilerOpts.add("-I" + project.file("src/nativeInterop/cinterop").absolutePath)
        }
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
                implementation(libs.koalaplot.core)
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

        // wave4: local JVM unit tests for the Android actuals. The
        // androidUnitTest source set is created by the android target; it is
        // wired to commonTest by hand because the default hierarchy template
        // is disabled in this project (same reason iosMain is wired below).
        // Run with: ./gradlew :composeApp:testDebugUnitTest
        val androidUnitTest = getByName("androidUnitTest") {
            dependsOn(commonTest)
            dependencies { implementation(kotlin("test")) }
        }

        // Wiring androidUnitTest -> commonTest runs the WHOLE commonTest
        // suite against the Android actuals in :composeApp:testDebugUnitTest
        // (409 tests instead of 2: every common test now exercises the
        // Android actuals, which audit 4 scored as zero-covered).
        // One class cannot run there and is excluded below: the Android
        // actual of readBundledResource reads NepentheApp.appContext.assets,
        // and a local JVM unit test has no AssetManager/Context (AGP's
        // mockable android.jar stubs every Context method; Robolectric is
        // deliberately not used here). The class keeps running green in
        // desktopTest, so excluding it from the Android task loses no
        // coverage, it only stops a platform-impossible assertion from
        // failing the gate.
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
                // wave4 stretch: compose ui-test infra for the desktop smoke
                // tests (ComposeScreenSmokeTest); the first ui-test dependency
                // this project has had (audit 4, item 9). Catalog coordinate,
                // not compose.uiTest, which is a deprecated accessor.
                implementation(libs.compose.ui.test)
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
        // Wire iosMain explicitly (default hierarchy template disabled)
        val iosMain = getByName("iosMain") { dependsOn(commonMain) }
        // Connect iOS leaf targets to iosMain
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            getByName("${it.name}Main").dependsOn(iosMain)
        }

        // wave4: intermediate iosTest source set, mirroring the manual
        // iosMain wiring above (default hierarchy template disabled).
        // Config-only verification on Windows: this host must never run an
        // ios compile/test task. Its smoke test (composeApp/src/iosTest)
        // is compiled and run by mac CI only (iosSimulatorArm64Test), never
        // here.
        val iosTest = create("iosTest") {
            dependsOn(commonTest)
            dependencies { implementation(kotlin("test")) }
        }
        // Connect iOS leaf targets to iosTest
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            getByName("${it.name}Test").dependsOn(iosTest)
        }
    }
}

// KMP test tasks (desktopTest) are NOT standard Gradle Test tasks,
// so tasks.withType<Test>() does not match them. BOTH test entry points
// (scripts/run-tests.sh and the CI test step in .github/workflows/ci.yml)
// therefore run the BARE desktopTest task. Hand-inlined --tests filter
// lists are banned (audit C6): the old FAST/ALL lists drifted from the
// real test tree and hid live classes from CI. Do not reintroduce them.
//
// Usage:
//   ./scripts/run-tests.sh           -- whole desktopTest suite (all classes)
//   ./scripts/run-tests.sh --list    -- list test source files
//   ./gradlew composeApp:desktopTest --no-daemon   (unfiltered, same as CI)
//
// Prerequisites:
//   - gradle.properties sets org.gradle.daemon=false and in-process Kotlin compiler
//   - run-tests.sh raises ulimit -u 16384 (git-bash default 256 is too low)
//
// NOTE: SessionListViewModelTest was previously excluded because it appeared
// to deadlock (combine + runBlocking). Root cause was a bad test predicate:
// it waited for 3 substances while the fixture had no dose for Cannabis.
// Fixed 2026-07-31; the class now runs in the suite.

// Google Play upload signing: applied ONLY when keystore.properties exists
// (local dev or Play CI). F-Droid builds from source without secrets and must
// stay unsigned here; they sign with their own key. See docs/GOOGLE-PLAY.md.
val uploadKeystoreFile = rootProject.file("keystore.properties")
val hasUploadKeystore = uploadKeystoreFile.exists()

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
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,LICENSE.md,LICENSE.txt,NOTICE.md,*.properties}" } }
    signingConfigs {
        if (hasUploadKeystore) {
            create("release") {
                val props = Properties().apply {
                    uploadKeystoreFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // No signingConfig when the keystore is absent: F-Droid signs the
            // unsigned build with its own key.
            if (hasUploadKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// wave4: exclusions for AGP local unit test tasks ONLY (name ends with
// UnitTest, so desktopTest/allTests are untouched and still run everything).
// See the androidUnitTest comment above: DoseWikiLookupTest needs a real
// Android Context (assets), which a JVM unit test cannot provide.
tasks.withType<Test>().configureEach {
    if (name.endsWith("UnitTest")) {
        filter {
            excludeTestsMatching("app.journal.data.DoseWikiLookupTest")
        }
    }
}

// Gradle Play Publisher: uploads AABs + fastlane metadata to Google Play.
// Credentials are only set when the service account file exists, so plain
// builds (and F-Droid) never touch them.
play {
    if (rootProject.file("play-service-account.json").exists()) {
        serviceAccountCredentials.set(rootProject.file("play-service-account.json"))
    }
    track.set("internal")
    defaultToAppBundles.set(true)
}

compose.desktop {
    application {
        mainClass = "app.journal.MainKt"
        // Empty assistive_technologies: a stale system-wide Java Access Bridge
        // registration makes AWT abort startup (AWTError, no window). Empty
        // means load no legacy bridge; native accessibility is unaffected.
        jvmArgs("-Djavax.accessibility.assistive_technologies=")
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
            )
            packageName = "Nepenthe Journal"
            packageVersion = "0.1.0"
            vendor = "Wolren"
            description = "Offline-first psychoactive substance session tracker"
            copyright = "Copyright (C) 2026 Wolren"
            // Shown as the installer license step (jpackage converts to RTF).
            licenseFile.set(rootProject.file("LICENSE"))
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                // Without these, the MSI installs with no Start Menu entry and
                // no desktop shortcut: the app is unlaunchable from the UI.
                menu = true
                shortcut = true
                // jpackage otherwise derives the MSI upgrade code from vendor +
                // name; pinning keeps future installs upgrading in place even if
                // vendor or package name is ever edited.
                upgradeUuid = "D07ABD38-858F-464A-ABF6-9405C622BB14"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                // jpackage mirrors Apple's CFBundleShortVersionString rule and
                // rejects a leading zero ("The first number in an app-version
                // cannot be zero"), which kills the app-image before DMG
                // creation. The macOS-scoped override feeds only the Apple
                // bundlers; global packageVersion, versionName, the About card
                // and the release tag stay 0.1.0.
                packageVersion = "1.0.0"
            }
        }
    }
}
