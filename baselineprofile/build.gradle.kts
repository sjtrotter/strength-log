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
    // An emulator or attached device. CI uses the AOSP API 34 image in
    // .github/workflows/baseline-profile.yml, since collection needs `adb root`.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit4)
}
