// Open the K60 landscape test field with VoxFix before running.
import assert from 'node:assert/strict';
import {adbShell} from './device-adb.mjs';
const windows=await adbShell('dumpsys window windows');
const block=windows.split(/  Window #/).find(s=>s.includes('ty=INPUT_METHOD ')&&s.includes('package=local.voxfix.debug'));
assert(block,'VoxFix must be the current keyboard');
assert(block.includes('isVisible=true'),'Open the keyboard before checking');
assert(/\bland\b/.test(block),'Use landscape before checking');
const frame=block.match(/Frames:.*? frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
assert(frame,'IME window frame must be available');
const bounds=block.match(/mBounds=Rect\(0, 0 - (\d+), (\d+)\)/);
assert(bounds,'Display bounds must be available');
assert.equal(+frame[1],0,'IME must cover the left edge without exposing the host');
assert.equal(+frame[3],+bounds[1],'IME must cover the right edge without exposing the host');
assert.equal(+frame[4],+bounds[2],'IME must reach the bottom');
console.log(`PASS: native landscape IME covers both edges [${frame.slice(1).join(',')}]`);
