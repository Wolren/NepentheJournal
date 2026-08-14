plugins {
    kotlin("multiplatform")           version libs.versions.kotlin.get() apply false
    kotlin("plugin.serialization")    version libs.versions.kotlin.get() apply false
    kotlin("plugin.compose")          version libs.versions.kotlin.get() apply false
    id("org.jetbrains.compose")       version "1.11.1" apply false
    id("com.android.application")     version "9.3.1" apply false
}

buildscript {
    configurations.classpath {
        resolutionStrategy {
            force(
                "org.jdom:jdom2:2.0.6.1",
                "org.bitbucket.b_c:jose4j:0.9.6"
            )
        }
    }
}

subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force(
                // Netty 4.1.x - fixes 20+ CVEs (SNI, HTTP/2 rapid reset, request smuggling,
                // decompression bomb, CRLF injection, ipv6 filter bypass, LZ4 OOM, etc.)
                "io.netty:netty-handler:4.2.17.Final",
                "io.netty:netty-codec-http2:4.2.17.Final",
                "io.netty:netty-codec-http:4.2.17.Final",
                "io.netty:netty-codec:4.2.17.Final",
                "io.netty:netty-common:4.2.17.Final",
                "io.netty:netty-buffer:4.2.17.Final",
                "io.netty:netty-transport:4.2.17.Final",
                "io.netty:netty-resolver:4.2.17.Final",
                "io.netty:netty-transport-native-unix-common:4.2.17.Final",
                "io.netty:netty-handler-proxy:4.2.17.Final",
                "io.netty:netty-codec-socks:4.2.17.Final",
                // Bouncy Castle - GOST CTR keystream reuse (critical), broken algorithm
                "org.bouncycastle:bcprov-jdk18on:1.85",
                "org.bouncycastle:bcpkix-jdk18on:1.85",
                "org.bouncycastle:bcutil-jdk18on:1.85",
                // Apache HTTP Components - XSS in HttpClient
                "org.apache.httpcomponents:httpclient:4.5.14",
                "org.apache.httpcomponents:httpmime:4.5.14",
                "org.apache.httpcomponents:httpcore:4.4.16",
                // Commons Lang - uncontrolled recursion
                "org.apache.commons:commons-lang3:3.20.0"
            )
        }
    }
}
