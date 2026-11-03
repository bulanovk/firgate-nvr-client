buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
