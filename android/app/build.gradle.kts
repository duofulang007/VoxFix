plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "local.voxfix.debug"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    signingConfigs.getByName("debug") { storeFile = file("../../.android/app-tools/debug.keystore") }
    defaultConfig {
        applicationId = "local.voxfix.debug"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-debug"
        testInstrumentationRunner = "local.voxfix.debug.PolishSmokeInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("modelAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("keyboardAssets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("nativeLibs"))
    androidResources { noCompress += listOf("onnx", "wav") }
    // The static sherpa archive keeps a shared ORT only for x86; this app ships ARM64.
    packaging { jniLibs.pickFirsts += "**/x86/libonnxruntime.so" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
val stageModels by tasks.registering(Sync::class) {
    from("../../models") { include("streaming/**", "final/**", "punctuation/**", "text/**", "handwriting/**") }
    into(layout.buildDirectory.dir("modelAssets"))
}
val stageKeyboard by tasks.registering(Sync::class) {
    from("../../preview") { include("index.html", "*.css", "app.mjs", "core.mjs", "voice.mjs", "pinyin.mjs", "ink.mjs", "pinyin-data.json", "phrase-packs.json") }
    from("src/main/cpp/vendor/handwriting") { include("LICENSE", "ONNXRUNTIME-LICENSE", "UPSTREAM.md"); into("licenses/handwriting") }
    into(layout.buildDirectory.dir("keyboardAssets/keyboard"))
}
tasks.named("preBuild") { dependsOn(stageModels, stageKeyboard) }
dependencies {
    implementation(files("../../.android/app-tools/sherpa-onnx-static-link-onnxruntime-1.13.6.aar", "../../.android/app-tools/onnxruntime-android-1.27.0.aar"))
    testImplementation("junit:junit:4.13.2")
}
