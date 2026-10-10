param([string]$Proxy = '', [switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $taskRoot
if (-not $Proxy) {
    $taskInternet = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
    if ($taskInternet.ProxyEnable -eq 1) { $Proxy = $taskInternet.ProxyServer }
}
$taskBootstrap = @'
import concurrent.futures, hashlib, json, pathlib, shutil, subprocess, sys, zipfile
root=pathlib.Path.cwd(); cache=root/'.model-downloads'; base=root/'.android/app-tools'
cache.mkdir(exist_ok=True);base.mkdir(parents=True,exist_ok=True)
proxy=sys.argv[1]
def fetch(url,path):
    args=['curl.exe','-fsSL','--retry','2','--connect-timeout','20','--max-time','600']
    if proxy and not url.startswith('https://dl.google.com/'): args+=['--proxy',proxy]
    subprocess.run(args+[url,'-o',str(path)],check=True)
def install(spec):
    url,digest,target,strip=spec; archive=cache/url.rsplit('/',1)[1]
    algorithm='sha256' if len(digest)==64 else 'sha1'
    required={'jdk':['bin/java.exe','bin/javac.exe','lib/modules'],
              'gradle':['lib/gradle-launcher-8.9.jar'],
              'android-35':['android.jar','source.properties'],
              '35.0.0':['aapt2.exe','d8.bat','source.properties']}.get(target.name,[])
    receipt=target/'.voxfix-source-digest'
    if strip is None and target.is_file():
        with target.open('rb') as f:
            if hashlib.file_digest(f,algorithm).hexdigest()==digest:
                print('INSTALLED '+target.name,flush=True);return
    elif strip is not None and required and receipt.is_file() and receipt.read_text().strip()==digest and all((target/p).is_file() for p in required):
        print('INSTALLED '+target.name,flush=True);return
    def valid():
        if not archive.is_file(): return False
        with archive.open('rb') as f: return hashlib.file_digest(f,algorithm).hexdigest()==digest
    if not valid():fetch(url,archive)
    if not valid():raise ValueError('Archive checksum mismatch: '+archive.name)
    if strip is None:
        shutil.copyfile(archive,target);return
    target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            parts=pathlib.PurePosixPath(member.filename).parts[strip:]
            if not parts:continue
            dest=target.joinpath(*parts)
            if not dest.resolve().is_relative_to(target.resolve()):raise ValueError('Unsafe archive path')
            if member.is_dir():dest.mkdir(parents=True,exist_ok=True)
            else:
                dest.parent.mkdir(parents=True,exist_ok=True)
                with z.open(member) as src,dest.open('wb') as out:shutil.copyfileobj(src,out)
    receipt.write_text(digest+'\n')
    print('READY '+target.name,flush=True)
dependencies=json.loads((root/'tools/android-build-dependencies.json').read_text())
jdk=dependencies['jdk'];gradle=dependencies['gradle']
specs=[
 (jdk['url'],jdk['sha256'],base/'jdk',1),
 (gradle['url'],gradle['sha256'],base/'gradle',1),
 ('https://dl.google.com/android/repository/platform-35_r02.zip','0bb560a90a7a2cbd0dd8348224d518b638fe7949',root/'.android/sdk/platforms/android-35',1),
 ('https://dl.google.com/android/repository/build-tools_r35_windows.zip','af059bb67cf7786f45ee0db85e2d24985df1b4b6',root/'.android/sdk/build-tools/35.0.0',1),
 ('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-static-link-onnxruntime-1.13.6.aar','01e87037afca2ed49085062aace5c012e60321e8e23e3a72b6d9ac02c843f66c',base/'sherpa-onnx-static-link-onnxruntime-1.13.6.aar',None),
 ('https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.27.0/onnxruntime-android-1.27.0.aar','077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb',base/'onnxruntime-android-1.27.0.aar',None)]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(install,specs))
(root/'android/local.properties').write_text('sdk.dir='+str(root/'.android/sdk').replace('\\','/')+'\n')
'@
$taskBootstrap | & .\.venv\Scripts\python.exe - $Proxy
if ($LASTEXITCODE) { throw '安卓构建工具准备失败，未开始编译' }
if ($PrepareOnly) { return }
& (Join-Path $PSScriptRoot 'build_android_polish.ps1')
$taskJava = Join-Path $taskRoot '.android\app-tools\jdk'
$taskGradle = Join-Path $taskRoot '.android\app-tools\gradle\lib\gradle-launcher-8.9.jar'
$taskKeystore = Join-Path $taskRoot '.android\app-tools\debug.keystore'
if (-not (Test-Path -LiteralPath $taskKeystore)) {
    & (Join-Path $taskJava 'bin\keytool.exe') -genkeypair -keystore $taskKeystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE) { throw '项目 Debug 签名生成失败' }
}
$taskPreviousJava = $env:JAVA_HOME
$env:JAVA_HOME = $taskJava
$taskOptions = @('--no-daemon', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process', '-g', (Join-Path $taskRoot '.android\gradle-cache'), '-p', (Join-Path $taskRoot 'android'))
if ($Proxy) {
    $taskProxyUri = [uri]$Proxy
    $taskOptions += @("-Dhttp.proxyHost=$($taskProxyUri.Host)", "-Dhttp.proxyPort=$($taskProxyUri.Port)", "-Dhttps.proxyHost=$($taskProxyUri.Host)", "-Dhttps.proxyPort=$($taskProxyUri.Port)", '-Dhttp.nonProxyHosts=localhost|127.*|*.google.com')
}
try {
    & (Join-Path $taskJava 'bin\java.exe') -classpath $taskGradle org.gradle.launcher.GradleMain @taskOptions testDebugUnitTest assembleDebug
    if ($LASTEXITCODE) { throw 'Debug 构建或测试失败，未安装到手机' }
} finally { $env:JAVA_HOME = $taskPreviousJava }
