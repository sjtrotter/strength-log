plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sjtrotter.strengthlog.transfer"
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
        unitTests.isIncludeAndroidResources = true // Robolectric
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)

    // Robolectric (JUnit4, run via the vintage engine under the JUnit platform),
    // same setup as :data, for the CSV import/export tests that exercise a real
    // in-memory Room DB + TrackerRepository (round-trip, DB-untouched-on-
    // rejection) without needing the emulator (#16; D10 prefers JVM/Robolectric
    // over instrumented tests when the code under test allows it).
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.runtime)
    testImplementation(libs.room.ktx)
    testImplementation(libs.androidx.datastore.preferences)
    testRuntimeOnly(libs.junit.vintage.engine)

    // Instrumented round-trip and atomicity tests (A2): the export/import contract
    // runs against a real on-disk Room DB + DataStore + TrackerRepository, exactly
    // as :data's persistence tests do. CI runs these via :transfer:connectedDebugAndroidTest.
    // Room/DataStore are restated because :data declares them `implementation`
    // (non-transitive) and the test harness constructs both directly.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":data"))
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.room.ktx)
    androidTestImplementation(libs.androidx.datastore.preferences)
}
