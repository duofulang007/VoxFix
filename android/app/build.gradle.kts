import java.security.MessageDigest

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
val streamingModelFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx")
val optimizeStreamingModels by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/prepare_streaming_models.py")
    inputs.files("../../tools/prepare_streaming_models.py", "../../tools/setup_voice.py", "../../tools/streaming-optimization-requirements.txt")
    streamingModelFiles.forEach {
        inputs.file("../../models/streaming/$it")
        outputs.file("../../.android/streaming-optimized/$it")
    }
    inputs.file("../../models/sensevoice/model.int8.onnx")
    outputs.file("../../.android/streaming-optimized/model.int8.onnx")
    outputs.file("../../.android/streaming-optimized/receipt.json")
}
val stageModels by tasks.registering(Sync::class) {
    dependsOn(optimizeStreamingModels)
    doFirst {
        val vocab = file("../../models/streaming/bpe.vocab")
        check(vocab.isFile) { "Missing hotword vocabulary; run tools/setup_voice.py" }
        val digest = MessageDigest.getInstance("SHA-256").digest(vocab.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "d0b642f3a2eacd5fadefdeff9e0e1358cab729647cbb7fe58cf738e1f7407029") { "Hotword vocabulary checksum mismatch" }
    }
    from("../../models") { include("streaming/**", "vad/**", "sensevoice/**", "punctuation/*.onnx"); exclude("streaming/*.onnx", "sensevoice/*.onnx") }
    from("../../.android/streaming-optimized") { include(streamingModelFiles); into("streaming") }
    from("../../.android/streaming-optimized") { include("model.int8.onnx"); into("sensevoice") }
    // getVadModelConfig hardcodes the asset name "silero_vad.onnx", so the VAD model has to sit at
    // the assets root instead of under vad/. Keeping the source under vad/ stays tidy on disk.
    from("../../models/vad") { include("*.onnx") }
    into(layout.buildDirectory.dir("modelAssets"))
}
val stageKeyboard by tasks.registering(Sync::class) {
    from("../../preview") { include("index.html", "*.css", "app.mjs", "core.mjs", "lexicon.mjs", "voice-orb.mjs", "pinyin.mjs", "english.mjs", "split.mjs", "english-words.txt", "pinyin-data.json", "pinyin-lite.json", "phrase-packs.json", "vendor/rime-ice/LICENSE", "vendor/rime-ice/UPSTREAM.md") }
    from("src/main/licenses/sensevoice") { include("LICENSE", "UPSTREAM.md"); into("licenses/sensevoice") }
    from("src/main/licenses/sherpa") { include("LICENSE", "UPSTREAM.md"); into("licenses/sherpa") }
    from("src/main/licenses/punctuation") { include("LICENSE", "UPSTREAM.md"); into("licenses/punctuation") }
    into(layout.buildDirectory.dir("keyboardAssets/keyboard"))
}
val buildPinyinNative by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/build_pinyin_rime.py")
    inputs.files("src/main/cpp/rime_jni.cpp", "src/main/cpp/rime/rime_api.h", "src/main/cpp/rime/rime_levers_api.h", "../../tools/build_pinyin_rime.py", "../../tools/pinyin-rime-dependencies.json")
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
