import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Konfigurasi per kampus dibaca dari everest.properties (lihat everest.properties.example)
val cfg = Properties().apply {
    val f = rootProject.file("everest.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfgStr(key: String, def: String = "") = "\"" + (cfg.getProperty(key) ?: def).replace("\"", "\\\"") + "\""

android {
    namespace = "id.ac.uika.everest"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.ac.uika.everest"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        buildConfigField("String", "SERVER_URL", cfgStr("SERVER_URL", "http://10.0.2.2:8080"))
        buildConfigField("String", "OIDC_ISSUER", cfgStr("OIDC_ISSUER"))
        buildConfigField("String", "OIDC_CLIENT_ID", cfgStr("OIDC_CLIENT_ID"))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", cfgStr("GOOGLE_SERVER_CLIENT_ID"))
        buildConfigField("String", "FCM_PROJECT_ID", cfgStr("FCM_PROJECT_ID"))
        buildConfigField("String", "FCM_APP_ID", cfgStr("FCM_APP_ID"))
        buildConfigField("String", "FCM_API_KEY", cfgStr("FCM_API_KEY"))
        buildConfigField("String", "FCM_SENDER_ID", cfgStr("FCM_SENDER_ID"))
        buildConfigField("boolean", "DEV_LOGIN", (cfg.getProperty("DEV_LOGIN") ?: "false").toBoolean().toString())

        // Skema redirect AppAuth untuk login SSO: id.ac.uika.everest:/oauth2redirect
        manifestPlaceholders["appAuthRedirectScheme"] = "id.ac.uika.everest"

        ndk {
            // whisper.cpp hanya dibangun untuk ABI ini (HP Android modern)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["usesCleartext"] = "false"
            // Ganti dengan signingConfig kampus sebelum distribusi
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.androidx.exifinterface)

    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.appauth)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
