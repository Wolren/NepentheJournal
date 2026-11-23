import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.kover)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.androidx.baselineprofile)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework { baseName = "composeApp"; isStatic = true }
        // SecKeyCopyKeyExchangeResult is missing from Kotlin's platform.Security bindings; bind it from the SDK header under its own package.
        iosTarget.compilations.getByName("main").cinterops.create("securityEx") {
            defFile(project.file("src/nativeInterop/cinterop/SecurityEx.def"))
            packageName = "platform.SecurityEx"
            compilerOpts.add("-I" + project.file("src/nativeInterop/cinterop").absolutePath)
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
                implementation(libs.sqldelight.runtime)
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain = getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.cio)
            }
        }

        // wave4: local JVM unit tests for the Android actuals.
        val androidUnitTest = getByName("androidUnitTest") {
            dependsOn(commonTest)
            dependencies { implementation(kotlin("test")) }
        }

        // Wiring androidUnitTest runs the whole commonTest suite against the Android actuals in :composeApp:testDebugUnitTest (409 tests instead of 2).
        val desktopMain = getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.logback.classic)
                // zstd for the sealed backup; this jar carries the desktop natives only.
                implementation(libs.zstd.jni)
                // user32 calls for borderless full screen; AWT cannot undecorate a live window.
                implementation(libs.jna.platform)
                implementation(libs.sqldelight.jdbc)
            }
        }
        val desktopTest = getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.cio)
                // ktor-server-netty is declared at project level (bottom of this file) with an exclude for netty-codec-http3: don't add another edge.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.serialization)
                // wave4 stretch: compose ui-test infra for the desktop smoke tests (ComposeScreenSmokeTest)
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
                implementation(libs.sqldelight.native)
            }
        }

        // Shared JVM source set for both Android and Desktop
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
            dependencies {
                // ktor-server-netty is declared at project level (bottom of this file) with an exclude for netty-codec-http3: don't add another edge.
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
        getByName("androidMain") {
            dependencies {
                // Android natives ship in the -jni AAR, not in the jar the desktop uses.
                implementation("com.github.luben:zstd-jni:1.5.7-2@aar")
                implementation(libs.sqldelight.android)
            }
        }
        androidMain.dependsOn(jvmMain)
        desktopMain.dependsOn(jvmMain)
        val iosMain = getByName("iosMain") { dependsOn(commonMain) }
        // The reference catalog is one build artifact shared by every target, never a checked-in copy.
        val catalogDir = rootProject.file("build/substance-db")
        desktopMain.resources.srcDir(catalogDir)
        iosMain.resources.srcDir(catalogDir)
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            getByName("${it.name}Main").dependsOn(iosMain)
        }

        // Its smoke test (composeApp/src/iosTest) is compiled and run by mac CI only (iosSimulatorArm64Test), never here.
        val iosTest = create("iosTest") {
            dependsOn(commonTest)
            dependencies { implementation(kotlin("test")) }
        }
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            getByName("${it.name}Test").dependsOn(iosTest)
        }
    }
}

// LAN sync uses plain http/ws: http/3 is unused.
dependencies {
    "jvmMainImplementation"(libs.ktor.server.netty) {
        exclude(group = "io.netty", module = "netty-codec-http3")
    }
    "desktopTestImplementation"(libs.ktor.server.netty) {
        exclude(group = "io.netty", module = "netty-codec-http3")
    }
}

// NOTE: SessionListViewModelTest was previously excluded because it appeared to deadlock (combine + runBlocking); fixed 2026-07-31, the class now runs in the suite.
sqldelight {
    databases {
        create("CatalogDatabase") {
            packageName.set("app.journal.catalog.db")
        }
    }
}

val uploadKeystoreFile = rootProject.file("keystore.properties")
val hasUploadKeystore = uploadKeystoreFile.exists()

android {
    namespace = "app.journal"
    // targetSdk stays 36 deliberately (runtime behavior change, separate).
    compileSdk = 37
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].assets.srcDirs("src/androidMain/assets", rootProject.file("build/substance-db"))
    defaultConfig {
        applicationId = "app.journal.nepenthe"
        minSdk = 26
        targetSdk = 36
        // Play rejects a reused code: CI passes the run number, local builds fall back to the release code.
        versionCode = providers.gradleProperty("nepenthe.versionCode").orNull?.toIntOrNull() ?: 3
        // The alpha suffix is user-visible and Android accepts it; packageVersion stays numeric for jpackage.
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Duplicate META-INF files from netty-codec-native-quic platform jars (5 identical LICENSE.jbzip2.txt) fail mergeReleaseJavaResource since the 2026-09 ktor/netty bumps; dedupe license copies.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,LICENSE.md,LICENSE.txt,NOTICE.md,*.properties,license/*}"
            excludes += "/META-INF/license/*"
        }
    }
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
            // R8 on release. proguard-rules.pro covers serialization, ktor, jmdns and BouncyCastle.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // zstd-jni's x86_64 .so is 4 KB aligned; Play needs 16 KB, and debug keeps every ABI.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                // The baseline profile generator runs on this workstation's x86_64 emulator.
                if (providers.gradleProperty("nepenthe.emulatorAbi").isPresent) abiFilters += "x86_64"
            }
            // No upload keystore: a local release build is debug-signed for profiling; CI and Play use the real key.
            // F-Droid compares two clean builds byte for byte, and a per-runner debug key would differ,
            // so -Pnepenthe.noSign=true builds the unsigned APK that check hashes.
            signingConfig = when {
                hasUploadKeystore -> signingConfigs.getByName("release")
                providers.gradleProperty("nepenthe.noSign").isPresent -> null
                else -> signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// wave4: exclusions for agp local unit test tasks only (name ends with UnitTest, so desktopTest and allTests still run everything).
tasks.withType<Test>().configureEach {
    if (name.endsWith("UnitTest")) {
        filter {
            excludeTestsMatching("app.journal.data.DoseWikiLookupTest")
        }
    }
    // Theme, keybinds, pets and the journal all hang off user.home, so tests get their own.
    val testHome = layout.buildDirectory.dir("test-home/$name").get().asFile
    testHome.mkdirs()
    systemProperty("user.home", testHome.absolutePath)
    providers.gradleProperty("nepenthe.bench").orNull?.let { systemProperty("nepenthe.bench", it) }
    // The reference catalog is a build artifact; catalog tests read the copy the builder produced.
    rootProject.file("build/substance-db/substances.sqlite").takeIf { it.exists() }?.let {
        systemProperty("nepenthe.catalog", it.absolutePath)
    }
    systemProperty("nepenthe.substanceSources", project.file("src/desktopMain/resources").absolutePath)
}

// Device-level smoke coverage for the Android runtime path that no unit test reaches.
dependencies {
    baselineProfile(project(":baselineprofile"))
    "androidTestImplementation"("androidx.compose.ui:ui-test-junit4:1.12.1")
    "androidTestImplementation"("androidx.test.ext:junit:1.2.1")
    "androidTestImplementation"("androidx.test:runner:1.6.2")
    "debugImplementation"("androidx.compose.ui:ui-test-manifest:1.12.1")
}

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
        // Empty assistive_technologies: a stale system-wide Java Access Bridge registration makes AWT abort startup (AWTError, no window).
        jvmArgs("-Djavax.accessibility.assistive_technologies=")
        // AppCDS measured 16.3 s to the first frame and 1.5 s without it; the archive never exists at launch.
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
            )
            packageName = "Nepenthe Journal"
            packageVersion = "0.3.0"
            vendor = "Wolren"
            description = "Offline-first psychoactive substance session tracker"
            copyright = "Copyright (C) 2026 Wolren"
            // Shown as the installer license step (jpackage converts to RTF).
            licenseFile.set(rootProject.file("LICENSE"))
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                menu = true
                shortcut = true
                upgradeUuid = "D07ABD38-858F-464A-ABF6-9405C622BB14"
                // Per user: no administrator prompt, and the app lands next to the data it owns in the profile.
                perUserInstall = true
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                // The macOS-scoped override feeds only the Apple bundlers; global packageVersion, versionName, the About card and the release tag stay 0.3.0.
                packageVersion = "1.0.0"
                // Signing and notarization switch on only when CI holds a Developer ID, so other builds still package.
                val macSignIdentity = providers.environmentVariable("MACOS_SIGN_IDENTITY").orNull?.takeIf { it.isNotBlank() }
                if (macSignIdentity != null) {
                    signing {
                        sign.set(true)
                        identity.set(macSignIdentity)
                        providers.environmentVariable("MACOS_SIGN_KEYCHAIN").orNull?.takeIf { it.isNotBlank() }
                            ?.let { keychain.set(it) }
                    }
                }
                val notaryAppleId = providers.environmentVariable("MACOS_NOTARY_APPLE_ID").orNull?.takeIf { it.isNotBlank() }
                val notaryPassword = providers.environmentVariable("MACOS_NOTARY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
                val notaryTeamId = providers.environmentVariable("MACOS_NOTARY_TEAM_ID").orNull?.takeIf { it.isNotBlank() }
                if (notaryAppleId != null && notaryPassword != null && notaryTeamId != null) {
                    notarization {
                        appleID.set(notaryAppleId)
                        password.set(notaryPassword)
                        teamID.set(notaryTeamId)
                    }
                }
            }
        }
    }
}

// Hashing 13 MB of bundled JSON each launch cost ~114 ms desktop, so the build hashes it once.
val generateSeedFingerprint by tasks.registering {
    description = "Generates SEED_BUNDLED_FINGERPRINT from the bundled seed and DoseWiki resources."
    val srcRoot = file("src")
    val canonicalDir = File(srcRoot, "desktopMain/resources")
    val hashedNames = listOf("psychonautwiki_seed.json", "dosewiki_slim.json")
    // Every target reads its own copy, so a missing or drifted copy ships a different library.
    val mirroredNames = hashedNames + listOf("effects.json", "dosewiki_taxonomy.json")
    val mirroredDirs = listOf("androidMain/assets", "iosMain/resources", "jvmMain/resources")
    val outDir = layout.buildDirectory.dir("generated/seedFingerprint").get().asFile
    inputs.files(hashedNames.map { File(canonicalDir, it) })
    inputs.files(mirroredDirs.flatMap { dir -> mirroredNames.map { File(File(srcRoot, dir), it) } })
    outputs.dir(outDir)
    doLast {
        val missing = mirroredNames
            .map { File(canonicalDir, it) }
            .filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing bundled resources: ${missing.joinToString { it.path }}")
        }
        mirroredDirs.forEach { dir ->
            mirroredNames.forEach { name ->
                val copy = File(File(srcRoot, dir), name)
                if (!copy.isFile) {
                    throw GradleException("$dir/$name is missing; every target ships its own copy.")
                }
                if (!copy.readBytes().contentEquals(File(canonicalDir, name).readBytes())) {
                    throw GradleException(
                        "$dir/$name differs from desktopMain/resources/$name; keep every bundled copy byte-identical."
                    )
                }
            }
        }
        val seedText = File(canonicalDir, hashedNames[0]).readText(Charsets.UTF_8)
        val doseText = File(canonicalDir, hashedNames[1]).readText(Charsets.UTF_8)
        val hex = MessageDigest.getInstance("SHA-256")
            .digest((seedText + doseText).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> byte.toInt().and(0xFF).toString(16).padStart(2, '0') }
        val target = File(outDir, "app/journal/data/SeedFingerprint.kt")
        target.parentFile.mkdirs()
        target.writeText(
            "package app.journal.data\n\n" +
                "// Generated by generateSeedFingerprint from the bundled resources; do not edit.\n" +
                "internal const val SEED_BUNDLED_FINGERPRINT: String = " +
                "\"${seedText.length}:${doseText.length}:$hex\"\n"
        )
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateSeedFingerprint)
