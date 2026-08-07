plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "cloud.trotter.log.strength.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark's floor, not the app's (:app ships to 26) — profile
        // capture needs the shell profiling this module drives.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    // Emulator or attached phone, never a rooted-shell requirement — the one
    // machine that runs this is CI's API 34 x86_64 image (.github/workflows/ci.yml).
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit4)
}
