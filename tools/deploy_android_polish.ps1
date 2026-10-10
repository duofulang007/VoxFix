param([string]$Serial = '6959dba1')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $taskRoot '.android/sdk/platform-tools/adb.exe'
$taskModel = Join-Path $taskRoot 'models/polish/Qwen3.5-2B-Q4_K_M.gguf'
if ((Get-FileHash -LiteralPath $taskModel -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223') { throw '整理模型校验失败，未传输' }
$taskDestination = 'files/polish/Qwen3.5-2B-Q4_K_M.gguf'
& $taskAdb -s $Serial shell run-as local.voxfix.debug mkdir -p files/polish
if ($LASTEXITCODE) { throw '无法创建独立应用模型目录' }
$taskTransfer = @'
import subprocess, sys
adb,serial,model,destination=sys.argv[1:]
with open(model,'rb') as source:
    subprocess.run([adb,'-s',serial,'exec-in','run-as','local.voxfix.debug','sh','-c','cat > '+destination],stdin=source,check=True)
'@
$taskTransfer | & (Join-Path $taskRoot '.venv/Scripts/python.exe') - $taskAdb $Serial $taskModel $taskDestination
if ($LASTEXITCODE) { throw '模型传输失败' }
$taskHash = & $taskAdb -s $Serial shell run-as local.voxfix.debug sha256sum $taskDestination
if (-not ($taskHash -match '^aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223\s')) { throw '手机模型校验失败' }
Write-Output 'PHONE_MODEL_HASH_VERIFIED'
