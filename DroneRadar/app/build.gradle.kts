plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.droneradar"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.droneradar"
        minSdk = 26          // Android 8.0 (مطلوب لـ Wi-Fi NaN)
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        // Platform server (Supabase). The anon key is public by design; data is protected by RLS.
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        // Google Maps (satellite). Empty → the app falls back to OpenStreetMap.
        val mapsKey = (project.findProperty("MAPS_API_KEY") ?: "").toString()
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
    }
    buildFeatures { buildConfig = true }
    // Same signing key on every GitHub build, so new versions install over old ones.
    // (Debug key only — use a private release key before publishing to Google Play.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")   // خريطة OpenStreetMap بدون مفتاح API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")     // اتصال السيرفر
    implementation("com.google.android.gms:play-services-maps:19.0.0") // خريطة Google الستلايت
    val camerax = "1.3.4"                                               // كاميرا "رأيت درون"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
