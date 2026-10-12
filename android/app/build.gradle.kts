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
        testInstrumentationRunner = "local.voxfix.debug.VoxFixSmokeInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("modelAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("keyboardAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("pinyinAssets"))
    sourceSets["main"].jniLibs.srcDir("../../.android/pinyin-rime/jni")
    androidResources { noCompress += listOf("onnx", "wav") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
val stageModels by tasks.registering(Sync::class) {
    from("../../models") { include("streaming/**", "vad/**", "sensevoice/**") }
    // getVadModelConfig hardcodes the asset name "silero_vad.onnx", so the VAD model has to sit at
    // the assets root instead of under vad/. Keeping the source under vad/ stays tidy on disk.
    from("../../models/vad") { include("*.onnx") }
    into(layout.buildDirectory.dir("modelAssets"))
}
val stageKeyboard by tasks.registering(Sync::class) {
    from("../../preview") { include("index.html", "*.css", "app.mjs", "core.mjs", "voice.mjs", "voice-orb.mjs", "pinyin.mjs", "ink.mjs", "english.mjs", "english-words.txt", "pinyin-data.json", "pinyin-lite.json", "phrase-packs.json", "vendor/rime-ice/LICENSE", "vendor/rime-ice/UPSTREAM.md") }
    from("src/main/licenses/sensevoice") { include("LICENSE", "UPSTREAM.md"); into("licenses/sensevoice") }
    from("src/main/licenses/sherpa") { include("LICENSE", "UPSTREAM.md"); into("licenses/sherpa") }
    into(layout.buildDirectory.dir("keyboardAssets/keyboard"))
}
val buildPinyinNative by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/build_pinyin_rime.py")
    inputs.files("src/main/cpp/rime_jni.cpp", "src/main/cpp/rime/rime_api.h", "../../tools/build_pinyin_rime.py", "../../tools/pinyin-rime-dependencies.json")
    outputs.files("../../.android/pinyin-rime/jni/arm64-v8a/libvoxfix_rime.so", "../../.android/pinyin-rime/jni/arm64-v8a/libfeelime_rime.so", "../../.android/pinyin-rime/jni/arm64-v8a/libc++_shared.so")
}
val stagePinyin by tasks.registering(Sync::class) {
    from("../../.android/pinyin-rime/data") { include("luna_pinyin.*.bin", "opencc/**"); into("pinyin") }
    from("src/main/pinyin") { into("pinyin") }
    from("../../.android/pinyin-rime/personal") { include("voxfix_personal.dict.yaml", "personal-english.txt"); into("pinyin-personal") }
    from("../../.android/pinyin-rime/licenses") { into("licenses/pinyin") }
    from("src/main/licenses/pinyin") { into("licenses/pinyin") }
    into(layout.buildDirectory.dir("pinyinAssets"))
}
tasks.named("preBuild") { dependsOn(stageModels, stageKeyboard, buildPinyinNative, stagePinyin) }
dependencies {
    // 1.13.8 brings the silence-hallucination fixes from 1.13.7/1.13.8; its Kotlin API is
    // identical to 1.13.6 for everything this app calls (Vad, OnlineRecognizer, SenseVoice).
    implementation(files("../../.android/app-tools/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
}
