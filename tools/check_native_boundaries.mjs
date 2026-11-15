#!/usr/bin/env node
// 原生层结构体检：依赖方向、偏好归属、单文件体积预算。
// 用法：node tools/check_native_boundaries.mjs
// 退出码 0 = 通过，1 = 越线。
//
// 为什么需要它：前端早就有 check_module_boundaries.mjs，原生 3 万行却没有任何红线，
// 结果是 Activity 被当成全局设置仓库、服务反向依赖页面、大文件持续长胖。
import {readFileSync, readdirSync, statSync, existsSync} from 'node:fs';
import {join, dirname, basename} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'android/app/src/main/java/local/voxfix/debug');
const budgetFile = join(root, 'tools/native-size-budget.json');
const budget = existsSync(budgetFile) ? JSON.parse(readFileSync(budgetFile, 'utf8')) : {};

const problems = [];
const stats = [];

const walk = dir => {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) { walk(path); continue; }
    if (name.endsWith('.kt')) stats.push({name, path, text: readFileSync(path, 'utf8')});
  }
};
walk(source);

// 设置/偏好归属：只有类型化仓库可以开 SharedPreferences，页面一律不许直接取。
const PREF_OWNER = /(Prefs|Config|Settings|Store|Memory|Checker|Mode|Picker)\.kt$/;
const codeOnly = text => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
for (const {name, path, text} of stats) {
  if (!text.includes('getSharedPreferences(')) continue;
  if (name.endsWith('Activity.kt')) {
    problems.push(`页面直接取偏好：${path.replace(root, '.').replaceAll('\\', '/')}（应走 *Prefs / *Config / *Store）`);
  } else if (!PREF_OWNER.test(name) && name !== 'Modules.kt') {
    problems.push(`偏好归属不明：${path.replace(root, '.').replaceAll('\\', '/')}（文件名需匹配 Prefs|Config|Settings|Store|Memory|Checker）`);
  }
}

// ui/ 是纯绘制层，不许反向依赖四大组件。
for (const {path, text} of stats) {
  if (!path.includes(`${join('debug', 'ui')}`)) continue;
  for (const forbidden of ['android.app.Activity', 'android.app.Service', 'android.content.BroadcastReceiver']) {
    if (text.includes(`import ${forbidden}`)) {
      problems.push(`ui 层反向依赖组件：${path.replace(root, '.').replaceAll('\\', '/')} → ${forbidden}`);
    }
  }
}

// Activity 只能被 Activity、输入法桥与清单引用；服务/接收器/View 不得为了读状态 import 页面。
// 浮层/面板/编辑器属于 feature-ui，允许导航到页面（例如翻译缺少模型时打开设置）。
const ACTIVITIES = /^(Home|About|License|Lexicon|Dictionary|Backup|PcSync|ScreenTextSettings|ScreenshotToolbarSettings|Main|DevTools)Activity$/;
const UI_NAVIGATOR = /(Overlay|View|Panel|Layer|Editor|Control|Picker)\.kt$/;
for (const {name, path, text} of stats) {
  if (name.endsWith('Activity.kt') || name === 'VoxFixImeService.kt' || UI_NAVIGATOR.test(name)) continue;
  for (const match of codeOnly(text).matchAll(/\b([A-Z]\w*Activity)\b/g)) {
    if (ACTIVITIES.test(match[1])) {
      problems.push(`非 UI 层引用页面：${path.replace(root, '.').replaceAll('\\', '/')} → ${match[1]}`);
      break;
    }
  }
}

// 体积预算：新增职责必须建新文件，不能继续往少数几个大文件里塞。
const report = [];
for (const {name, text} of stats) {
  const lines = text.split('\n').length;
  const max = budget[name];
  if (max && lines > max) problems.push(`体积越线：${name} ${lines} 行（预算 ${max}）`);
  if (max) report.push({name, lines, max});
}

console.log('原生体积预算：');
for (const {name, lines, max} of report.sort((a, b) => b.lines - a.lines)) {
  console.log(`  ${name.padEnd(34)} ${String(lines).padStart(5)} / ${max} 行`);
}
for (const p of problems) console.log(`FAIL  ${p}`);
console.log(problems.length ? `\n结论：${problems.length} 处越线` : '\n结论：依赖方向、偏好归属与体积预算均在红线内');
process.exit(problems.length ? 1 : 0);
