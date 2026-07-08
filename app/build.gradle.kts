plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.sjtrotter.strengthlog"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sjtrotter.strengthlog"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isIncludeAndroidResources = true // Robolectric
    }
}

kotlin {
    jvmToolchain(17)
}

// Run all Hilt processing through KSP. Hilt's separate javac aggregating task
// bundles a kotlin-metadata reader that can't parse Kotlin 2.4's class metadata;
// disabling it keeps Hilt on KSP, which handles the current metadata version.
hilt {
    enableAggregatingTask = false
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    // BackupService/CsvHistoryService (:transfer's Uri-free core, D9) — the
    // Data/Backup screen supplies the SAF Uri->stream plumbing on top.
    implementation(project(":transfer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)
    // DataModule constructs the Room DB and Preferences DataStore, so the app
    // module depends on them directly (they are not part of :data's API).
    implementation(libs.room.runtime)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric (JUnit4, run via the vintage engine under the JUnit platform)
    // for ViewModel wiring tests against a real in-memory Room DB.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
}
