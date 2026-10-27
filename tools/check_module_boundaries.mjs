#!/usr/bin/env node
// 模块边界体检：依赖方向、循环依赖、单文件体积红线。
// 用法：node tools/check_module_boundaries.mjs
// 退出码 0 = 通过（可能带 WARN），1 = 越线，需要处理。
import {readFileSync, readdirSync, statSync, existsSync} from 'node:fs';
import {join, dirname, basename} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const preview = join(root, 'preview');

// 前端运行时模块的根：只有从 app.mjs 可达的模块才进 APK / 试用页。
const ROOT = 'app.mjs';
// 叶子层：允许依赖 L0_ONLY，不允许反向依赖 app.mjs。
const L0_ONLY = new Set(['core.mjs']);
// 体积红线
const FAIL_LINES = 3000, FAIL_BYTES = 250 * 1024;
const WARN_LINES = 2200, WARN_BYTES = 180 * 1024;

const files = readdirSync(preview).filter(f => f.endsWith('.mjs') && !f.includes('.test.'));
const importsOf = file => {
  const text = readFileSync(join(preview, file), 'utf8');
  return [...text.matchAll(/^import[^'"]*from\s*['"]\.\/([\w.-]+\.mjs)['"]/gm)].map(m => m[1]);
};

// 从 app.mjs 做可达性分析，得到"进产物的模块集合"
const reachable = new Set([ROOT]);
const queue = [ROOT];
while (queue.length) {
  for (const dep of importsOf(queue.shift())) {
    if (!reachable.has(dep)) { reachable.add(dep); queue.push(dep); }
  }
}

const problems = [], warnings = [];
// 1) 环检测（DFS + 栈）
const state = new Map();
const walk = (node, stack) => {
  state.set(node, 'visiting');
  for (const dep of importsOf(node)) {
    if (!reachable.has(dep)) continue;
    if (state.get(dep) === 'visiting') problems.push(`循环依赖：${[...stack, node, dep].join(' → ')}`);
    else if (!state.has(dep)) walk(dep, [...stack, node]);
  }
  state.set(node, 'done');
};
walk(ROOT, []);

// 2) 依赖方向：叶子层只能依赖 L0_ONLY，且不得反向依赖 app.mjs
for (const file of reachable) {
  if (file === ROOT) continue;
  for (const dep of importsOf(file)) {
    if (dep === ROOT) problems.push(`反向依赖：${file} 依赖了聚合层 ${ROOT}`);
    if (dep !== file && !L0_ONLY.has(dep)) problems.push(`越层依赖：${file} → ${dep}（叶子层只允许依赖 core.mjs）`);
  }
}

// 3) 体积红线
const sizes = [...reachable].map(f => {
  const bytes = statSync(join(preview, f)).size;
  const lines = readFileSync(join(preview, f), 'utf8').split('\n').length;
  return {f, bytes, lines};
}).sort((a, b) => b.lines - a.lines);
for (const {f, bytes, lines} of sizes) {
  if (lines > FAIL_LINES || bytes > FAIL_BYTES) problems.push(`体积越线：${f} ${lines} 行 / ${(bytes/1024).toFixed(0)}KB（上限 ${FAIL_LINES} 行 / ${FAIL_BYTES/1024}KB）`);
  else if (lines > WARN_LINES || bytes > WARN_BYTES) warnings.push(`体积预警：${f} ${lines} 行 / ${(bytes/1024).toFixed(0)}KB（预警线 ${WARN_LINES} 行 / ${WARN_BYTES/1024}KB）`);
}

// 4) 未接入产物的模块（写了但没被 app.mjs 引用，可能是忘了接）
const orphans = files.filter(f => !reachable.has(f) && !['device-adb.mjs'].includes(f));

console.log('前端运行时模块（app.mjs 可达）：');
for (const {f, lines, bytes} of sizes) console.log(`  ${f.padEnd(18)} ${String(lines).padStart(5)} 行  ${(bytes/1024).toFixed(0).padStart(4)}KB`);
if (orphans.length) console.log(`\n未接入（不被 app.mjs 引用，不计入产物）：${orphans.join(', ')}`);
for (const w of warnings) console.log(`WARN  ${w}`);
for (const p of problems) console.log(`FAIL  ${p}`);
console.log(problems.length ? `\n结论：${problems.length} 处越线` : '\n结论：边界与体积均在红线内');
process.exit(problems.length ? 1 : 0);
