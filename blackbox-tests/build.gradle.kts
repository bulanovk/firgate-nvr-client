plugins {
    id("com.android.test")
}

// Black-box UI-тесты: отдельный APK, управляет установленным приложением снаружи (UiAutomator).
// Цель — сборка blackbox (applicationId com.frigate.android.blackbox), основное приложение не затрагивается.
android {
    namespace = "com.frigate.android.blackbox.tests"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("blackbox") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
        }
    }
    targetProjectPath = ":app"
    // Тест инструментирует сам себя и живёт в своём процессе: иначе `pm clear` целевого приложения убивает тест.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "blackbox" }
}

dependencies {
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(libs.androidx.test.runner)
    implementation("junit:junit:4.13.2")
}
