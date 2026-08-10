plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cloud.trotter.log.strength.wear"
    compileSdk = 37

    defaultConfig {
        // MUST equal the phone's applicationId (cloud.trotter.log.strength),
        // not the wear namespace above. The Wearable Data Layer only delivers
        // DataItems/messages to an app with the same installed package name (+
        // signature) on the paired node — a mismatched suffix here silently
        // breaks all phone<->watch sync (the watch never receives a snapshot and
        // sits frozen on the loading screen). `namespace` stays wear-suffixed;
        // components are resolved relative to it and are unaffected.
        applicationId = "cloud.trotter.log.strength"
        minSdk = 30
        targetSdk = 37
        versionCode = (providers.gradleProperty("STRENGTHLOG_VERSION_CODE").orNull?.toInt() ?: 0) + 1
        versionName = providers.gradleProperty("STRENGTHLOG_VERSION_NAME").orNull ?: "0.1"
    }

    // Same four locally-supplied properties as :app (docs/RELEASE.md) — one
    // upload key signs both form factors; absent, release stays unsigned.
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
            // R8 on (ledger item ":wear R8"): the unminified wear APK weighed
            // 25 MB, which made every wireless-adb deploy to the watch a
            // multi-minute affair; shrunk it is a few MB. Rules mirror :app's
            // (kotlinx.serialization only — see wear/proguard-rules.pro).
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
        unitTests.isIncludeAndroidResources = true // Robolectric Compose semantics tests
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
    implementation(libs.androidx.wear.ambient)
    // Wear OngoingActivity: wraps the "workout in progress" notification so the
    // watch face shows a one-tap re-entry chip after a stem press / ambient
    // timeout (redesign §1.4 / R6). Local-only; no INTERNET implication.
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.remote.interactions)
    // The two glance surfaces (glance-surfaces brief §2/§3): a tile rendered with
    // ProtoLayout and a watch-face complication. Both read the snapshot DataItem the
    // Data Layer already persists here, so neither adds state or a permission.
    // protolayout-material is deliberately absent — the tile is one hand-built layout
    // in this app's own vocabulary, and the Material component set would only drag in
    // a look we don't use.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx)
    // SuspendToFutureAdapter: TileService hands back a ListenableFuture and the
    // snapshot read is a suspend function. Already a transitive runtime dep of
    // tiles; declared so it's on the compile classpath too.
    implementation(libs.androidx.concurrent.futures.ktx)
    // Wear Data Layer client (#20): read snapshots, send set-edit deltas; the
    // play-services adapter gives Task.await(). The pending-edit queue persists to
    // a Preferences DataStore.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose semantics run on Robolectric (JUnit4 through the vintage engine),
    // matching :app's device-free accessibility harness.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
