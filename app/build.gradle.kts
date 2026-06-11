plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.joshit.phocus"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }

    }

    defaultConfig {
        applicationId = "com.joshit.phocus"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.6.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // Turns on code scrambling & shrinking (R8)
            isShrinkResources = true    // Deletes unused images/XML files to save space
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