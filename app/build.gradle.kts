plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vr2dapplauncher"
    // Cardboard SDK v1.36.0 is compiled against API 35.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vr2dapplauncher"
        // Cardboard SDK AAR declares minSdk 26 -> app must be >= 26.
        minSdk = 26
        // Keep target at 33 so MediaProjection does not require the Android 14
        // foreground-service flow. This is a tech-demo, not a Play release.
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cardboard SDK AAR is built from googlevr/cardboard@v1.36.0 by CI and
    // copied to app/libs/cardboard-sdk.aar before the app build starts.
    implementation(files("libs/cardboard-sdk.aar"))
    // Transitive requirements of the Cardboard SDK (a flat AAR has no POM).
    implementation("com.google.android.gms:play-services-vision:20.1.3")
    implementation("com.google.protobuf:protobuf-javalite:3.19.4")
    implementation("org.jspecify:jspecify:1.0.0")

    // Shizuku: official Maven Central coordinates (moe.shizuku:* does not exist).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
