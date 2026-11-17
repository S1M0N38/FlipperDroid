plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.flipperdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.flipperdroid"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing read from the environment (CI: GitHub secrets). Without these
    // variables, the release build is unsigned and must not be published.
    signingConfigs {
        create("release") {
            val ksFile = System.getenv("RELEASE_KEYSTORE_FILE")
            if (!ksFile.isNullOrBlank()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a worktree build live next to another one on the same
            // emulator/phone: the release id stays com.example.flipperdroid.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (!System.getenv("RELEASE_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // java.time (LocalDateTime…) only exists from API 26 on: desugaring
        // makes it available on minSdk 24.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Errors fail the build locally and in CI. Warnings stay report-only (the CI
        // workflow uploads build/reports/), so dependency-update nags and unused
        // resources never block a fix.
        abortOnError = true
        warningsAsErrors = false
        // Lint the release variant too, so an issue that only exists there cannot
        // reach a tag build.
        checkReleaseBuilds = true
    }
    testOptions {
        // JVM tests sometimes touch android.util.Log: default values are returned
        // instead of throwing "Method ... not mocked". Prefer Robolectric for
        // anything that needs real Android behaviour (see RootShellTest).
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Material Icons (versions managed by the Compose BOM)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.zxing.core)
    // QR/barcode scanner with the camera (provides ScanContract + CaptureActivity)
    implementation(libs.zxing.android.embedded)
}
