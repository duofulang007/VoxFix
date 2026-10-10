"""Run the selected GGUF inside Android. This is shell inference, not an IME APK."""
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'voice'))
from polish import SYSTEM, guarded_result
from setup_polish import MODEL_HASH
ADB=ROOT/'.android/sdk/platform-tools/adb.exe'
REMOTE='/data/local/tmp/voxfix-native-model'


def adb(*args,timeout=120):
    result=subprocess.run([str(ADB),'-s','emulator-5554',*args],capture_output=True,timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8',errors='replace')[-1500:])
    return result.stdout.decode('utf-8',errors='replace')


if __name__=='__main__':
    build=ROOT/'.android/native-build-x86_64/bin';binary=build/'llama-completion'
    raw=binary.read_bytes();assert raw[:4]==b'\x7fELF' and int.from_bytes(raw[18:20],'little')==62
    adb('shell',f'mkdir -p {REMOTE} && chmod 700 {REMOTE}')
    for path in [binary,*build.glob('*.so')]:
        adb('push',str(path),REMOTE+'/'+path.name)
    adb('shell',f'chmod 755 {REMOTE}/llama-completion')
    version=adb('shell',f'LD_LIBRARY_PATH={REMOTE} {REMOTE}/llama-completion --version')
    print('ANDROID_ELF_EXECUTED',flush=True)
    model=ROOT/'models/polish/Qwen3.5-2B-Q4_K_M.gguf';remote_model=REMOTE+'/'+model.name
    current=adb('shell',f'if [ -f {remote_model} ]; then sha256sum {remote_model}; fi',timeout=180)
    if not current.startswith(MODEL_HASH):
        adb('push',str(model),remote_model,timeout=180)
    assert adb('shell','sha256sum '+remote_model,timeout=180).startswith(MODEL_HASH)
    print('ANDROID_MODEL_HASH_VERIFIED',flush=True)
    work=ROOT/'.android/native-input';work.mkdir(exist_ok=True)
    original='我明天跑10公里不要跑15公里然后然后打开Obsidian记录一下'
    for name,text in [('system.txt',SYSTEM),('input.txt',original)]:
        path=work/name;path.write_text(text,encoding='utf-8');adb('push',str(path),REMOTE+'/'+name)
    args=[REMOTE+'/llama-completion','-m',remote_model,'-c','2048','-t','4','-ngl','0','-n','160','--temp','0.2','--jinja','--single-turn','--simple-io','--no-display-prompt','--reasoning','off','-sysf',REMOTE+'/system.txt','-f',REMOTE+'/input.txt']
    command=f'LD_LIBRARY_PATH={REMOTE} /system/bin/time -v '+shlex.join(args)
    started=time.monotonic();result=subprocess.run([str(ADB),'-s','emulator-5554','shell',command],capture_output=True,timeout=240)
    elapsed=time.monotonic()-started;stdout=result.stdout.decode('utf-8',errors='replace');stderr=result.stderr.decode('utf-8',errors='replace')
    (ROOT/'docs/android-native-model-output.txt').write_text(stdout,encoding='utf-8')
    (ROOT/'docs/android-native-model-runtime.txt').write_text(stderr,encoding='utf-8')
    assert result.returncode==0,stderr[-2000:]
    assert all(word in stdout for word in ['10','15','不要','Obsidian']), 'Native model output must contain protected test content'
    memory=re.search(r'Max RSS \(KiB\):\s*(\d+)',stderr)
    report={'scope':'GGUF and native ELF execute inside Android 15 x86_64 emulator CPU; not Windows inference, app sandbox, native IME or K60 performance','abi':adb('shell','getprop ro.product.cpu.abi').strip(),'android':adb('shell','getprop ro.build.version.release').strip(),'sourceCommit':'8ae386707bed05f02354dbfc6fa5ee0cf58e5f0f','modelSha256':MODEL_HASH,'original':original,'rawOutput':stdout.strip(),'elapsedSeconds':round(elapsed,2),'maxResidentKiB':int(memory.group(1)) if memory else None,'exitCode':result.returncode,'runnerVersion':version.strip()}
    (ROOT/'docs/android-native-model-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
