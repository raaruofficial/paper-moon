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
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
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

    // Solana Mobile Wallet Adapter (official). Provides the native connect /
    // session / sign flow against the Seeker's Seed Vault wallet.
    // Docs: https://github.com/solana-mobile/solana-mobile-docs (android-native)
    // T1-S24 fix: the clientlib's published Gradle metadata puts
    // androidx.test.ext:junit-ktx in its RELEASE RUNTIME variant, which
    // drags androidx.test:core (whose library manifest declares the
    // exported InstrumentationActivityInvoker activities), androidx.test
    // monitor/services, and junit:junit (jar resources) into every
    // consumer's production APK — all three shipped inside v0.1.0's
    // release APK. The adapter never needs its test libraries at
    // runtime; exclude the whole test family from this dependency.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.ext")
        exclude(group = "androidx.test.services")
        exclude(group = "junit")
    }

    // AC-02: Keystore-backed EncryptedSharedPreferences for the MWA
    // auth token, entitlement cache, receipts, and claim ledger.
    implementation("androidx.security:security-crypto:1.1.0-alpha07")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
