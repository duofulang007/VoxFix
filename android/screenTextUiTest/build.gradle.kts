plugins { id("com.android.application") }
android {
    namespace = "local.voxfix.screentest"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    signingConfigs.getByName("debug") { storeFile = file("../../.android/app-tools/debug.keystore") }
    defaultConfig {
        applicationId = "local.voxfix.screentest"
        minSdk = 30
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.srcDir("../../.android/screen-ocr-eval-20261103/assets")
    androidResources { noCompress += "onnx" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation(files("../../.android/app-tools/onnxruntime-android-1.20.0.aar"))
}
