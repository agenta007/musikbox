import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// Shares version.properties with the Android app so both ship the same version number.
val appVersion: String = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}.getProperty("versionName") ?: "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "app.musikbox.desktop.MainKt"
        jvmArgs += listOf("-Dmusikbox.version=$appVersion", "-Xmx768m")

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "musikbox"
            packageVersion = appVersion
            description = "Playlist-first music player for Navidrome and Subsonic"
            vendor = "musikbox"
            // jlink'd runtime: suggestRuntimeModules' picks plus modules used reflectively
            // (TLS EC ciphers for HTTPS servers, OkHttp logging, D-Bus Unix-socket creds, locales).
            modules(
                "java.instrument", "jdk.security.auth", "jdk.unsupported",
                "java.logging", "java.management", "java.naming", "java.xml",
                "jdk.crypto.ec", "jdk.net", "jdk.charsets", "jdk.localedata",
            )
            linux {
                iconFile.set(project.file("src/main/resources/musikbox.png"))
                shortcut = true
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "sound"
                debMaintainer = "musikbox@localhost"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(true)
            version.set("7.5.0") // the bundled 7.2 can't read Java 21 jmods
            obfuscate.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}
