plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hindsight.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hindsight.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 15
        versionName = "0.2.10"

        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing comes ONLY from the environment (CI secrets or local
    // keystore.properties). Nothing secret is ever committed — see README.
    val keystoreFile: java.io.File? =
        System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed only when a keystore is provided (CI tag builds). Otherwise
            // falls back to the debug key — fine for local testing, NOT for the store.
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    // lintVitalAnalyzeRelease crashes deterministically in the lint
    // analyzer on PaperMoonApp.kt (NegativeArraySizeException — a lint
    // bug, reproduced twice on CI 2026-10-19). Vital lint is a release
    // gate, not a build step; disabling release lint checks lets the
    // signed build proceed. Debug lint is unaffected.
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    // Compose BOM 2026.06.01 is the BOM of record for the Connect
    // build (Gate 0 supplementary ruling S-1): seeker-connect-compose
    // 0.2.0's own published metadata declares 2026.06.01, and the
    // sitting adopted the vendor-resolved graph rather than forcing
    // the held 2026.06.00 against it.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    // NOTE (T1-S24): this project has no instrumented or unit tests
    // (no src/androidTest, no src/test), so there is deliberately no
    // androidTestImplementation / testImplementation of anything here.

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Seeker Connect (Gate 0, v0.2.10): the wallet seam. The §2.3
    // dependency proof (Gate 0 proof run 41148451407) established that
    // clientlib arrives ONLY as seeker-connect's transitive runtime
    // dependency at exactly 2.1.1 — not api-exposed, not bundled in
    // either AAR. The direct clientlib-ktx:2.0.8 declaration is
    // therefore REMOVED (C-04: one wallet stack, transitive-only);
    // app code talks to the Connect seam only.
    // The androidx.test/junit exclusions that used to hang on the
    // clientlib declaration re-attach HERE (ARCHITECT DEP-01 §2):
    // clientlib's published metadata puts androidx.test.ext:junit-ktx
    // in its release runtime variant, which drags androidx.test:core
    // (exported InstrumentationActivityInvoker activities),
    // monitor/services, and junit into a consumer's production APK
    // (the T1-S24 contamination, shipped inside v0.1.0). The cure is
    // an exclusion on the seeker-connect declaration — never a
    // re-added direct clientlib declaration kept alive to hang
    // exclusions on.
    implementation("com.solanamobile:seeker-connect:0.2.0") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.ext")
        exclude(group = "androidx.test.services")
        exclude(group = "junit")
    }
    implementation("com.solanamobile:seeker-connect-compose:0.2.0")

    // AC-02: Keystore-backed EncryptedSharedPreferences for the MWA
    // auth token, entitlement cache, receipts, and claim ledger.
    implementation("androidx.security:security-crypto:1.1.0-alpha07")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
