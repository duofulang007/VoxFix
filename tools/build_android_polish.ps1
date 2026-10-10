$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $taskRoot '.venv/Scripts/python.exe') (Join-Path $PSScriptRoot 'setup_text_normalization.py')
if ($LASTEXITCODE) { throw '上游文本规范化组件准备失败' }
$taskCmake = Join-Path $taskRoot '.android/native-tools/cmake/bin/cmake.exe'
$taskNinja = Join-Path $taskRoot '.android/native-tools/cmake/bin/ninja.exe'
$taskNdk = Join-Path $taskRoot '.android/native-tools/ndk/android-ndk-r28b'
$taskSource = Join-Path $taskRoot '.android/native-source/ggml-org-llama.cpp-8ae3867'
$taskBuild = Join-Path $taskRoot '.android/native-polish-arm64'
& $taskCmake -S (Join-Path $taskRoot 'android/app/src/main/cpp') -B $taskBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$taskNinja" "-DCMAKE_TOOLCHAIN_FILE=$taskNdk/build/cmake/android.toolchain.cmake" "-DLLAMA_SOURCE=$taskSource" "-DOPENFST_ANDROID=$taskRoot/.android/third-party/openfst-android" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE) { throw 'ARM64 整理库配置失败' }
& $taskCmake --build $taskBuild --target voxfix-polish --parallel 2
if ($LASTEXITCODE) { throw 'ARM64 整理库编译失败' }
$taskLibs = Join-Path $taskRoot 'android/app/build/nativeLibs/arm64-v8a'
New-Item -ItemType Directory -Path $taskLibs -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskBuild 'libvoxfix-polish.so') -Destination $taskLibs -Force
