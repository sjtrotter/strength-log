plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "cloud.trotter.log.strength"
    compileSdk = 37

    defaultConfig {
        applicationId = "cloud.trotter.log.strength"
        minSdk = 26
        targetSdk = 37
        // CI supplies these for Play uploads (publish-internal.yml); absent,
        // local builds stay at the dev defaults. Phone takes the even code,
        // :wear the odd (base + 1) — every artifact in a release needs a
        // distinct, monotonic versionCode.
        versionCode = providers.gradleProperty("STRENGTHLOG_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("STRENGTHLOG_VERSION_NAME").orNull ?: "0.1"
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
            // R8 on for the shipping build (M6 #23/A9); :wear shrinks too
            // (its build.gradle.kts) — the unminified watch APK hit 25 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // AndroidX ships two native libs (graphics.path, datastore); the
            // symbol table rides inside the bundle so Play can symbolicate
            // their crash frames — no separate symbols upload.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
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

// The baselineprofile plugin adds two more release variants to this module
// (`nonMinifiedRelease`, which the generator runs against, and
// `benchmarkRelease`, for macrobenchmarks this repo doesn't have). `assemble`
// would build both on every CI run and every `./gradlew build` here, for a
// profile that is generated a handful of times a year — so they only exist when
// asked for. Consuming the profile needs none of this: the release build reads
// the checked-in `src/release/generated/baselineProfiles` either way.
androidComponents {
    val generatingProfile = providers.gradleProperty("baselineProfileGeneration").orNull == "true"
    beforeVariants(selector().withBuildType("nonMinifiedRelease")) { it.enable = generatingProfile }
    beforeVariants(selector().withBuildType("benchmarkRelease")) { it.enable = false }
}

// The disabled variants above are deliberate (the beforeVariants block is the
// mechanism the warning suggests checking), so the configure-time notice is
// suppressed rather than repeated on every build.
baselineProfile {
    warnings {
        disabledVariants = false
    }
}

kotlin {
    jvmToolchain(17)
}

// Recomposition hygiene evidence (#156): `./gradlew :app:compileDebugKotlin
// -PcomposeMetrics=true` writes the stability/skippability reports to
// app/build/compose-metrics. Off by default — the reports cost a full
// non-incremental Kotlin compile, and nothing in a normal build reads them.
composeCompiler {
    if (providers.gradleProperty("composeMetrics").orNull == "true") {
        val dir = layout.buildDirectory.dir("compose-metrics")
        reportsDestination = dir
        metricsDestination = dir
    }
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

    // Installs the baseline profile below on first run for devices that don't
    // get it from Play's cloud profiles (sideloads, and the release APK
    // docs/RELEASE.md builds).
    implementation(libs.androidx.profileinstaller)
    // The generator module's output, compiled AOT into the release build (#156).
    baselineProfile(project(":baselineprofile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.core)
    // Wear Data Layer: publish snapshots + receive set-edit deltas (#20). The
    // play-services adapter gives Task.await() so the flow pipeline stays suspend.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    // DataModule constructs the Room DB and Preferences DataStore, so the app
    // module depends on them directly (they are not part of :data's API).
    implementation(libs.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    // First WorkManager use in the app: quiet, deferrable daily SAF backups.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric (JUnit4, run via the vintage engine under the JUnit platform)
    // for ViewModel wiring tests against a real in-memory Room DB.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    // compose-ui-test on Robolectric (A7): semantics smoke tests for the
    // TalkBack-facing content descriptions/state without a device.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
