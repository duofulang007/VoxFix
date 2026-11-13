import assert from 'node:assert/strict';
import {imageFit} from './image-fit.mjs';

// 与安卓侧 ScreenshotCanvasFitTest 跑同一张矩阵。
const cases = [
  {name: 'screen-sized', size: [1080, 2400], view: [1080, 2400], contain: 1, width: 1, canFit: false},
  {name: 'tall', size: [916, 10192], view: [1080, 2400], contain: 0.2355, width: 1, canFit: true},
  {name: 'small', size: [200, 100], view: [1080, 2400], contain: 1, width: 1, canFit: false},
  {name: 'wide', size: [1600, 900], view: [1080, 2400], contain: 0.675, width: 0.675, canFit: false},
  {name: 'moderately-tall', size: [1080, 4000], view: [1080, 2400], contain: 0.6, width: 1, canFit: true},
  {name: 'invalid', size: [0, 0], view: [1080, 2400], contain: 1, width: 1, canFit: false},
];

for (const item of cases) {
  const fit = imageFit(item.size[0], item.size[1], item.view[0], item.view[1]);
  assert.ok(Math.abs(fit.containScale - item.contain) < 1e-4, `${item.name} contain=${fit.containScale}`);
  assert.ok(Math.abs(fit.widthScale - item.width) < 1e-4, `${item.name} width=${fit.widthScale}`);
  assert.equal(fit.canFitWidth, item.canFit, `${item.name} canFitWidth=${fit.canFitWidth}`);
}

// 切换收益太小时不给入口（1080×2450 在 1080×2400 里只多 2%）。
assert.equal(imageFit(1080, 2450, 1080, 2400).canFitWidth, false);
assert.equal(imageFit(1080, 2400, 0, 0).canFitWidth, false);
console.log('PASS: image-fit matrix matches the Android formula');
