plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.sjtrotter.strengthlog.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

// Room emits its versioned schema JSON here; the files are committed so schema
// changes are reviewable and migrations can be generated/tested (PLAN.md A9).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))

    // api: TrackerRepository's constructor and Flow-returning surface expose Room
    // and DataStore types, so consumers (:app) need them on the compile classpath.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)

    // Instrumented persistence-hardening tests (PLAN.md A6, issue #7): a real
    // on-disk Room DB and DataStore file exercised across close/reopen.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
}
