plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// آدرس سرور ویس داخل برنامه ثابت می‌شود. برای عوض کردن: voiceServer در gradle.properties (یا -PvoiceServer=...)
val defaultServer = ((project.findProperty("voiceServer") as String?) ?: "").trim().ifBlank { "voice.elbe.ir" }

android {
    namespace = "app.minevoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.minevoice"
        minSdk = 24
        targetSdk = 34
        versionCode = 17
        versionName = "1.12.3"
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }

    // یک کلید ثابت: همه‌ی بیلدها (debug و release) با یک امضا ساخته می‌شوند تا روی هم نصب/آپدیت شوند
    signingConfigs {
        create("royal") {
            storeFile = rootProject.file("royalvoice.jks")
            storePassword = "royalvoice"
            keyAlias = "royalvoice"
            keyPassword = "royalvoice"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("royal") }
        release {
            signingConfig = signingConfigs.getByName("royal")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
}
