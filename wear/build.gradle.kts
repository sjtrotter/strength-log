plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sjtrotter.strengthlog.wear"
    compileSdk = 37

    defaultConfig {
        // MUST equal the phone's applicationId (io.github.sjtrotter.strengthlog),
        // not the wear namespace above. The Wearable Data Layer only delivers
        // DataItems/messages to an app with the same installed package name (+
        // signature) on the paired node — a mismatched suffix here silently
        // breaks all phone<->watch sync (the watch never receives a snapshot and
        // sits frozen on the loading screen). `namespace` stays wear-suffixed;
        // components are resolved relative to it and are unaffected.
        applicationId = "io.github.sjtrotter.strengthlog"
        minSdk = 30
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
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // LifecycleEventEffect (flush the coalesced edit on ON_STOP). Same lib :app uses.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.ambient)
    // Wear OngoingActivity: wraps the "workout in progress" notification so the
    // watch face shows a one-tap re-entry chip after a stem press / ambient
    // timeout (redesign §1.4 / R6). Local-only; no INTERNET implication.
    implementation(libs.androidx.wear.ongoing)
    // Wear Data Layer client (#20): read snapshots, send set-edit deltas; the
    // play-services adapter gives Task.await(). The pending-edit queue persists to
    // a Preferences DataStore.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.horologist.compose.layout) {
        // horologist-compose-layout depends on the full androidx ui-tooling artifact
        // (not debug-scoped), which ships androidx.compose.ui.tooling.PreviewActivity
        // (exported, unguarded) into the release APK's manifest. We don't use
        // horologist's tooling/preview surface, and ui-tooling is separately pulled in
        // below as debugImplementation, so drop this transitive copy entirely.
        exclude(group = "androidx.compose.ui", module = "ui-tooling")
    }
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
}
