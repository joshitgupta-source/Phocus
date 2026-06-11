plugins {
    alias(libs.plugins.android.application)
}

// 1. We create a variable at the top so we can use it in both the app AND the file name
val appVersionName = "1.6.5"

android {
    namespace = "com.joshit.phocus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joshit.phocus"
        minSdk = 24
        targetSdk = 36
        versionCode = 6

        // 2. We use the variable here for the phone's "App Info" settings
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// --- MODERN AGP 9+ APK RENAMING ---
// 3. This safely overrides the base filename without touching locked Android APIs
base {
    archivesName.set("Phocus_v${appVersionName}")
}