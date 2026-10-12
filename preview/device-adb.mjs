// 真机验收用的 adb 通道。
//
// 正常情况下直接调用仓库自带的 adb.exe；本机沙箱会以 EBUSY 拒绝 Node 启动任何子进程，
// 这时自动回退到 .android/adb-bridge.py 起的本地 HTTP 桥（Node 只能走 HTTP 出去）。
// 用 VOXFIX_ADB_BRIDGE 可以指定桥的地址，默认 http://127.0.0.1:18751。
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const ADB = fileURLToPath(new URL('../.android/sdk/platform-tools/adb.exe', import.meta.url));
const BRIDGE = process.env.VOXFIX_ADB_BRIDGE || 'http://127.0.0.1:18751';
let execUsable = true;

export async function adbShell(command, {serial = process.env.VOXFIX_SERIAL} = {}) {
  if (execUsable) {
    try {
      return execFileSync(ADB, [...(serial ? ['-s', serial] : []), 'shell', command], {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024});
    } catch (error) {
      if (error.code !== 'EBUSY') throw error;
      execUsable = false;
    }
  }
  const query = `cmd=${encodeURIComponent(command)}${serial ? `&serial=${encodeURIComponent(serial)}` : ''}`;
  const response = await fetch(`${BRIDGE}/shell?${query}`);
  if (!response.ok) throw new Error(`adb bridge returned ${response.status}`);
  return response.text();
}
