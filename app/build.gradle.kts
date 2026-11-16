plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "club.taptappers.telly"
    compileSdk = 35

    defaultConfig {
        applicationId = "club.taptappers.telly"
        minSdk = 29
        targetSdk = 35
        // 2.x is the playlist canvas; 1.x was the tale runner (git tag tales-final). Same id and signing key, so the
        // new app installs over the old one.
        versionCode = 240
        versionName = "2.4.0"
    }

    // Releases are signed with telly's own key, kept only in GitHub's encrypted secrets (backed up in Bitwarden as
    // telly/android_release_keystore). It used to be the debug key checked into this public repo, which meant anyone
    // could sign an "update" that installs over telly and reads its token. Debug builds (CI screenshots) use the
    // throwaway key the build tools generate.
    val releaseKeystore = System.getenv("TELLY_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("TELLY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TELLY_KEY_ALIAS")
                keyPassword = System.getenv("TELLY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 on: Compose without it runs unoptimised (and noticeably less smooth). CI building this variant proves it
            // compiles, not that it runs: a missing keep rule only shows on a phone, so open the release APK after
            // touching anything found by name (proguard-rules.pro lists those).
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
}
