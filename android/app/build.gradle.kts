import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись релиза: параметры берутся из keystore.properties (в .gitignore)
// или из переменных окружения KS_FILE / KS_PASS / KS_ALIAS / KS_KEYPASS.
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { this.load(it) }
}
fun ks(key: String, env: String): String? = System.getenv(env) ?: ksProps.getProperty(key)

android {
    namespace = "ru.vdl.catalog"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.vdl.catalog"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        resourceConfigurations += listOf("ru", "en")
    }

    val ksFile = ks("storeFile", "KS_FILE")
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = rootProject.file(ksFile)
            storePassword = ks("storePassword", "KS_PASS")
            keyAlias = ks("keyAlias", "KS_ALIAS")
            keyPassword = ks("keyPassword", "KS_KEYPASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging {
        resources.excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Camera + offline barcode/QR recognition (bundled model, no Google Play needed)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // SMTP
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
