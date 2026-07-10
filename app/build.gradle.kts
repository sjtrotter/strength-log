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

    // The release signing key is user-held and never enters this repo (public
    // repo, CLAUDE.md data principles) — these four values only exist as
    // Gradle properties supplied locally (~/.gradle/gradle.properties, a
    // -P flag, or an ORG_GRADLE_PROJECT_ env var), never as project files.
    // Absent (the common case: CI, or any non-signing dev build), the
    // release build type is simply left unsigned — see docs/RELEASE.md.
    val releaseStoreFile = providers.gradleProperty("STRENGTHLOG_RELEASE_STORE_FILE")
    signingConfigs {
        create("release") {
            releaseStoreFile.orNull?.let { storeFile = file(it) }
            providers.gradleProperty("STRENGTHLOG_RELEASE_STORE_PASSWORD").orNull?.let { storePassword = it }
            providers.gradleProperty("STRENGTHLOG_RELEASE_KEY_ALIAS").orNull?.let { keyAlias = it }
            providers.gradleProperty("STRENGTHLOG_RELEASE_KEY_PASSWORD").orNull?.let { keyPassword = it }
        }
    }

    buildTypes {
        release {
            // R8 on for the shipping build (M6 #23/A9); :wear stays unminified
            // (its build.gradle.kts) since it's a small watch face-adjacent app
            // and shrinking there hasn't earned its keep yet.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
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
