plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.frigate.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frigate.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        // A release build gets its version from the tag (`-PappVersionName=0.0.1`); CI appends a build suffix
        // (`-PappVersionSuffix=-test.12+abc1234`) to tell test builds apart.
        // versionCode stays 1 on purpose: a growing code would block `adb install -r` of local builds.
        versionName = (providers.gradleProperty("appVersionName").orNull ?: "0.1.0") + (providers.gradleProperty("appVersionSuffix").orNull ?: "")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only ARM devices: no x86/x86_64 native libraries in the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // BlackBox-сборка: копия debug с отдельным application id. Данные (профиль сервера,
    // сессия, credentials) отделены от основного приложения, поэтому clearState безопасен.
    buildTypes {
        // Test build for publishing: R8 shrinks dex, which is almost the whole APK. It is signed with
        // the debug key on purpose: only that key lets it replace the installed app via `adb install -r`.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("blackbox") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".blackbox"
            matchingFallbacks += "debug"
        }
    }
    sourceSets.getByName("blackbox") {
        manifest.srcFile("src/debug/AndroidManifest.xml")
        res.srcDir("src/debug/res")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
