import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* ---------------------------------------------------------------------------
 * THE ONE KEYSTORE
 *
 * ModMorpher is signed with exactly one signing identity, for both debug and
 * release. There is deliberately no fallback to Gradle's auto-generated debug
 * keystore: if a second identity ever signs this app, Android treats it as a
 * different app and refuses to install updates ("App not installed"), and
 * published builds can never be updated again.
 *
 * The keystore and its passwords are NOT in git (this repo is public).
 * Generate it once with:  python3 scripts/make_keystore.py
 * and back up keystore/modmorpher.keystore + keystore/keystore.properties.
 * ------------------------------------------------------------------------- */
val keystoreDir = rootProject.file("keystore")
val keystorePropertiesFile = File(keystoreDir, "keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val missingSigning = !keystorePropertiesFile.isFile ||
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .any { keystoreProperties.getProperty(it).isNullOrBlank() }
val signingReady = !missingSigning

/**
 * Escape hatch for CI machines that legitimately cannot hold the keystore.
 * It only allows an *unsigned* build to be produced. An unsigned APK cannot be
 * installed at all, so this can never create a second signing identity -- which
 * is the thing the rule below exists to prevent.
 */
val allowUnsigned = System.getenv("MODMORPHER_ALLOW_UNSIGNED_CI").toBoolean()

if (!signingReady && !allowUnsigned) {
    throw GradleException(
        """
        |Missing the ModMorpher signing keystore.
        |
        |This project intentionally refuses to fall back to another keystore,
        |so the build cannot continue without ${keystorePropertiesFile.absolutePath}
        |
        |Fix it with one of these:
        |  1. You already have it: copy modmorpher.keystore + keystore.properties
        |     into android/keystore/.
        |  2. First-time setup: python3 scripts/make_keystore.py
        |
        |See android/KEYSTORE.md -- and back the keystore up. Losing it means
        |you can never publish an update to this app again.
        """.trimMargin()
    )
}

android {
    namespace = "modmorpher.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "modmorpher.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingReady) {
            create("modmorpher") {
                storeFile = File(keystoreDir, keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeType = keystoreProperties.getProperty("storeType") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingReady) {
                signingConfig = signingConfigs.getByName("modmorpher")
            } else {
                logger.lifecycle(
                    "WARNING: no keystore -- building an UNSIGNED release APK. " +
                        "It cannot be installed until you sign it with the one keystore."
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Same identity as release, and no applicationIdSuffix: one app,
            // one package, one keystore.
            if (signingReady) {
                signingConfig = signingConfigs.getByName("modmorpher")
            }
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
