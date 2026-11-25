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
        versionCode = 3
        versionName = "2.0"
        // Google Maps (satellite). Empty → the app falls back to OpenStreetMap.
        val mapsKey = (project.findProperty("MAPS_API_KEY") ?: "").toString()
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
    }
    buildFeatures { buildConfig = true }
    // Private release key: provided by CI secrets (never stored in the repository).
    val ksPath = System.getenv("DR_KEYSTORE") ?: ""
    signingConfigs {
        create("release") {
            if (ksPath.isNotBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("DR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DR_KEY_ALIAS")
                keyPassword = System.getenv("DR_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath.isNotBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    // demo  = built-in test server (for demos);  client = no server inside, set by the customer's setup QR.
    flavorDimensions += "edition"
    productFlavors {
        create("demo") {
            dimension = "edition"
            buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        }
        create("client") {
            dimension = "edition"
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
        }
    }
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
    implementation("com.google.zxing:core:3.5.3")                       // QR إعداد السيرفر (بدون خدمات Google)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")   // تشفير جلسة الدخول على الهاتف
}
