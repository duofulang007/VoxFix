"""Install a project-local official Android Emulator and Android 15 image."""
import concurrent.futures
import json
import zipfile
from pathlib import Path
from setup_polish import download

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT/'.android/sdk'
SPECS = [
    ('https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip', 'e03e78b1d80b396f1c3358e31251cb31740e1110', SDK),
    ('https://dl.google.com/android/repository/emulator-windows_x64-16428233.zip', '488ed747e82de7e9bb5247becd1ac043c7e5e85d', SDK),
    ('https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip', '0103e6dab21290c4b9d16550a3ce99476f884eef', SDK/'system-images/android-35/google_apis'),
]


def install(spec):
    url, checksum, folder = spec
    archive = ROOT/'.model-downloads'/url.rsplit('/',1)[1]
    download(url,archive,checksum,'sha1')
    folder.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for name in package.namelist():
            if not (folder/name).resolve().is_relative_to(folder.resolve()):
                raise ValueError('下载档案路径超出安装目录')
        package.extractall(folder)
    print('INSTALLED '+archive.name,flush=True)


if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(install,SPECS))
    assert (SDK/'emulator/emulator.exe').is_file()
    assert (SDK/'platform-tools/adb.exe').is_file()
    assert (SDK/'system-images/android-35/google_apis/x86_64/system.img').is_file()
    (ROOT/'.android/install-manifest.json').write_text(json.dumps({'source':'Google official Android repository','packages':[{'url':u,'sha1':h} for u,h,_ in SPECS]},indent=2))
    print('ANDROID_EMULATOR_INSTALLED',flush=True)
