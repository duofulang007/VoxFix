plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// 版本单一来源：defaultConfig 与 APK 命名、发版 manifest.json 都引用它。
val appVersionName = "0.4.0"
val appVersionCode = 4
android {
    namespace = "local.voxfix.debug"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    signingConfigs.getByName("debug") { storeFile = file("../../.android/app-tools/debug.keystore") }
    defaultConfig {
        applicationId = "local.voxfix.debug"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "local.voxfix.debug.VoxFixSmokeInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("modelAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("keyboardAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("pinyinAssets"))
    sourceSets["main"].assets.srcDir("../../.android/screen-ocr/assets")
    sourceSets["main"].java.srcDir("src/voice/java")
    sourceSets["main"].jniLibs.srcDir("../../.android/pinyin-rime/jni")
    sourceSets["main"].jniLibs.srcDir("../../.android/translate/jni")
    sourceSets["main"].jniLibs.srcDir("../../.android/snow-auto-region/jni")
    sourceSets["main"].jniLibs.srcDir("../../.android/snow-scroll-capture/jni")
    androidResources { noCompress += listOf("onnx", "wav") }
    // sherpa's AAR also ships an x86 emulator onnxruntime; this build is arm64-only and the
    // pinned rescue AAR supplies its own arm64 runtime, so other ABIs must not collide.
    packaging { jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

// APK 命名（2026-11-14 定稿）：VoxFix-<versionName>-<versionCode>-<yyyyMMdd>-<git短sha7>.apk
// 例 VoxFix-0.4.0-4-20261114-a1b2c3d.apk。无 git 环境回退 nosha/nodate，保证打包机也能构建；
// manifest.json 里的 apkUrls 记录实际文件名，不依赖固定 APK 名（docs/关于与应用内更新-2026-11-14.md）。
// 实现走 Copy 任务：AGP VariantOutput.outputFileName 与 java.time/java.util 在本机
// Kotlin DSL 脚本编译里均不可解析（AGP 8.7.3 实测），Copy 方案零 AGP API 依赖。
val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrElse { "nosha" }
val buildDate: String = runCatching {
    providers.exec { commandLine("git", "log", "-1", "--format=%cd", "--date=format:%Y%m%d") }
        .standardOutput.asText.get().trim()
}.getOrElse { "nodate" }

val renameDebugApk by tasks.registering(Copy::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) { include("app-debug.apk") }
    // 不能复制回 debug 目录本身——AGP 的 createDebugApkListingFileRedirect 也把它当输出；
    // 规范命名产物放 named/，装机与发布都从这里取。
    into(layout.buildDirectory.dir("outputs/apk/named"))
    rename { "VoxFix-$appVersionName-$appVersionCode-$buildDate-$gitSha.apk" }
}
// AGP 的 assembleDebug 是延迟注册，named() 严格查找不到——用 matching 安全挂接。
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("renameDebugApk") }
val verifyVoiceModels by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/prepare_desktop_voice.py")
}
val stageModels by tasks.registering(Sync::class) {
    dependsOn(verifyVoiceModels)
    from("../../models/desktop-sensevoice") { include("model.int8.onnx", "tokens.txt"); into("sensevoice") }
    from("../../models/punctuation") { include("model.int8.onnx"); into("punctuation") }
    from("../../models/vad") { include("silero_vad.onnx") }
    into(layout.buildDirectory.dir("modelAssets"))
}
val stageKeyboard by tasks.registering(Sync::class) {
    from("../../preview") { include("index.html", "*.css", "app.mjs", "core.mjs", "lexicon.mjs", "voice-orb.mjs", "pinyin.mjs", "english.mjs", "split.mjs", "meme-search.mjs", "image-fit.mjs", "english-words.txt", "pinyin-data.json", "pinyin-lite.json", "phrase-packs.json", "vendor/rime-ice/LICENSE", "vendor/rime-ice/UPSTREAM.md") }
    from("src/main/licenses/sensevoice") { include("LICENSE", "UPSTREAM.md"); into("licenses/sensevoice") }
    from("src/main/licenses/sherpa") { include("LICENSE", "UPSTREAM.md"); into("licenses/sherpa") }
    from("src/main/licenses/punctuation") { include("LICENSE", "UPSTREAM.md"); into("licenses/punctuation") }
    from("src/main/licenses/meme") { include("UPSTREAM.md"); into("licenses/meme") }
    from("src/main/licenses/llama") { include("LICENSE", "UPSTREAM.md"); into("licenses/llama") }
    from("src/main/licenses/hy-mt2") { include("LICENSE", "UPSTREAM.md"); into("licenses/hy-mt2") }
    from("src/main/licenses/snow-region") { into("licenses/snow-region") }
    into(layout.buildDirectory.dir("keyboardAssets/keyboard"))
}
val buildPinyinNative by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/build_pinyin_rime.py")
    inputs.files("src/main/cpp/rime_jni.cpp", "src/main/cpp/rime/rime_api.h", "src/main/cpp/rime/rime_levers_api.h", "../../tools/build_pinyin_rime.py", "../../tools/pinyin-rime-dependencies.json")
    outputs.files("../../.android/pinyin-rime/jni/arm64-v8a/libvoxfix_rime.so", "../../.android/pinyin-rime/jni/arm64-v8a/libfeelime_rime.so", "../../.android/pinyin-rime/jni/arm64-v8a/libc++_shared.so")
}
val buildTranslateNative by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/build_translate_native.py")
    inputs.files("src/main/cpp/translate/translate_jni.cpp", "src/main/cpp/translate/CMakeLists.txt",
        "../../tools/build_translate_native.py", "../../tools/translate-dependencies.json")
    outputs.files("../../.android/translate/jni/arm64-v8a/libvoxfix_translate.so")
}
val buildSnowRegion by tasks.registering(Exec::class) {
    dependsOn(buildTranslateNative)
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/build_snow_region.py")
    inputs.files("src/main/cpp/snow_region_jni.c", "src/main/cpp/snow_stitch_jni.c",
        "../../tools/build_snow_region.py", "../../tools/snow-region.Cargo.lock", "../../tools/snow-stitch.Cargo.lock")
    outputs.files("../../.android/snow-auto-region/jni/arm64-v8a/libvoxfix_snow_region.so",
        "../../.android/snow-scroll-capture/jni/arm64-v8a/libvoxfix_snow_stitch.so")
}
val stagePinyin by tasks.registering(Sync::class) {
    from("../../.android/pinyin-rime/data") { include("luna_pinyin.*.bin", "opencc/**"); into("pinyin") }
    from("src/main/pinyin") { into("pinyin") }
    from("../../.android/pinyin-rime/personal") { include("voxfix_personal.dict.yaml", "personal-english.txt"); into("pinyin-personal") }
    from("../../.android/pinyin-rime/licenses") { into("licenses/pinyin") }
    from("src/main/licenses/pinyin") { into("licenses/pinyin") }
    into(layout.buildDirectory.dir("pinyinAssets"))
}
val prepareScreenOcr by tasks.registering(Exec::class) {
    workingDir("../..")
    commandLine(file("../../.venv/Scripts/python.exe"), "tools/prepare_screen_ocr.py")
}
tasks.named("preBuild") { dependsOn(stageModels, stageKeyboard, buildPinyinNative, buildTranslateNative, buildSnowRegion, stagePinyin, prepareScreenOcr) }
dependencies {
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 1.13.8 brings the silence-hallucination fixes from 1.13.7/1.13.8; its Kotlin API is
    // identical to 1.13.6 for everything this app calls (Vad, OnlineRecognizer, SenseVoice).
    implementation(files("../../.android/app-tools/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    // Decode-time hotword rescue needs per-frame logits, which the sherpa API does not
    // expose; this is the pinned onnxruntime build the desktop prototype used.
    implementation(files("../../.android/app-tools/onnxruntime-android-1.20.0.aar"))
    testImplementation("junit:junit:4.13.2")
    // 让 UpdateCore 的 org.json 解析在 JVM 单测里跑真实现（android.jar 里的 org.json 是 stub）。
    testImplementation("org.json:json:20231013")
}
