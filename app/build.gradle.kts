import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun loadProps(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use(::load)
}
val versionProps = loadProps("version.properties")
val keystoreProps = loadProps("keystore.properties")

android {
    namespace = "app.musikbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.musikbox"
        minSdk = 31
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode")?.toInt() ?: 1
        versionName = versionProps.getProperty("versionName") ?: "1.0.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release key from keystore.properties (created by deploy.sh). Without it the APK is
            // left unsigned, which is what F-Droid builds and signs itself.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The dependency metadata block is encrypted with a Google key; F-Droid asks for it to be left out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        // Media3 marks much of its surface @UnstableApi; we opt in deliberately.
        disable += "UnsafeOptInUsageError"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.palette)

    testImplementation(libs.junit)
}
