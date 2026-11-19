plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.liubai.guard"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.liubai.guard"
        minSdk = 26
        targetSdk = 34
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
        // 测试机（模拟器）是 x86：传 -PallAbi 时不过滤
        if (!project.hasProperty("allAbi")) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("boolean", "PLAY", "false")
    }
    flavorDimensions += "ch"
    productFlavors {
        // GitHub 发布的完整版：自更新、无线调试保活、卸载锁
        create("full") { dimension = "ch"; targetSdk = 34 }
        // Google Play 版：不自更新、不用无线调试和设备管理，目标 API 跟 Play 要求走
        create("play") { dimension = "ch"; targetSdk = 36; buildConfigField("boolean", "PLAY", "true") }
    }
    signingConfigs {
        create("rel") {
            storeFile = file("../liubai.p12")
            storePassword = System.getenv("KS_PASS") ?: "liubai2026"
            keyAlias = "liubai"
            keyPassword = System.getenv("KS_PASS") ?: "liubai2026"
            storeType = "pkcs12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
