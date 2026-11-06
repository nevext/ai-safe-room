import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A chave que assina as versões de verdade: android/keystore.properties e o .jks ficam fora do git (guarde cópia:
// sem ela não dá para atualizar o app instalado)
val signing = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
// The XM / JFTech cloud keys (open platform → the "AI Safe Room" app): android/xm.properties, outside git.
// xm.uuid, xm.appKey, xm.appSecret, xm.moveCard. Without the file the app builds, and seeing from outside by the
// camera's cloud says it isn't on yet.
val xm = Properties().apply { rootProject.file("xm.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }

android {
    namespace = "io.nevext.aisaferoom"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.nevext.aisaferoom"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.3.7"
        manifestPlaceholders += mapOf(
            "xmUuid" to xm.getProperty("xm.uuid", ""), "xmAppKey" to xm.getProperty("xm.appKey", ""),
            "xmAppSecret" to xm.getProperty("xm.appSecret", ""), "xmMoveCard" to xm.getProperty("xm.moveCard", ""))
    }

    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    // XM's native library is 23 MB per processor type: compressed inside the APK the download is much smaller (the
    // Play Store also sends each phone only its own type)
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // The camera's own video, direct over RTSP (the app without the AI, like the TV)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    // The account: Entrar com o Google (Credential Manager) into Firebase Auth; the house and the family in Firestore
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // XM / JFTech cloud (iCSee, XMEye): the camera by serial from anywhere, P2P, no computer (XmCloud.kt)
    implementation("io.github.xmcamera:libxmfunsdk:2.1")
}
