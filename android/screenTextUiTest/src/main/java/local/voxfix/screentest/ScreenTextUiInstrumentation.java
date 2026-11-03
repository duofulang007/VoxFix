package local.voxfix.screentest;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate APK: starting instrumentation must not kill the IME/accessibility process. */
public class ScreenTextUiInstrumentation extends Instrumentation {
    public static class BenchmarkActivity extends Activity {
        static BenchmarkActivity current;
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            current = this;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            var text = new android.widget.TextView(this);
            text.setText("正在测试屏幕取字，请稍候…");
            text.setTextSize(20);
            text.setGravity(android.view.Gravity.CENTER);
            setContentView(text);
        }
    }
    private static final String HANDLE = "屏幕取字，向内滑动展开，长按拖动";
    private UiAutomation ui;
    private boolean controlsOnly;
    private boolean ocrOnly;
    private boolean benchmark;
    private boolean offlineCompare;
    private boolean exportFixtures;
    private boolean screenshotOnly;
    private boolean screenshotFlow;
    private final AtomicBoolean finished = new AtomicBoolean();
    @Override public void onCreate(Bundle args) { super.onCreate(args); controlsOnly = args != null && "true".equals(args.getString("controlsOnly")); ocrOnly = args != null && "true".equals(args.getString("ocrOnly")); benchmark = args != null && "true".equals(args.getString("benchmark")); offlineCompare = args != null && "true".equals(args.getString("offlineCompare")); exportFixtures = args != null && "true".equals(args.getString("exportFixtures")); screenshotOnly = args != null && "true".equals(args.getString("screenshotOnly")); screenshotFlow = args != null && "true".equals(args.getString("screenshotFlow")); start(); }
    private void report(boolean ok, String message) {
        if (finished.compareAndSet(false, true)) {
            Bundle output = new Bundle(); output.putString(ok ? "result" : "error", message);
            finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
        }
    }
    @Override public void onStart() {
        if (screenshotFlow) {
            try { report(true,ScreenshotUiFlow.run(this)); }
            catch(Throwable error) { report(false,"SCREENSHOT_LIVE_FLOW_FAIL " + android.util.Log.getStackTraceString(error)); }
            return;
        }
        if (screenshotOnly) {
            try { report(true,ScreenshotFoundationChecks.run(this)); }
            catch(Throwable error) { report(false,"SCREENSHOT_FOUNDATION_FAIL " + android.util.Log.getStackTraceString(error)); }
            return;
        }
        if (exportFixtures) {
            try { report(true, ScreenTextOfflineCompare.exportFixtures(this)); }
            catch (Throwable error) { report(false, "SCREEN_TEXT_FIXTURE_EXPORT_FAIL " + android.util.Log.getStackTraceString(error)); }
            return;
        }
        if (benchmark || offlineCompare) {
            boolean ok = false;
            String result;
            try {
                var automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                // MIUI blocks background activity starts; launch only our fixture through the test shell.
                try (var pipe = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n local.voxfix.screentest/.ScreenTextUiInstrumentation$BenchmarkActivity"))) { pipe.readAllBytes(); }
                waitForIdleSync();
                if (BenchmarkActivity.current == null) throw new AssertionError("Benchmark page did not open");
                result = offlineCompare ? ScreenTextOfflineCompare.run(this) : ScreenTextOcrBenchmark.run();
                ok = true;
            } catch (Throwable error) { result = "SCREEN_TEXT_OCR_BENCHMARK_FAIL " + android.util.Log.getStackTraceString(error); }
            finally { if (BenchmarkActivity.current != null) runOnMainSync(() -> { BenchmarkActivity.current.finish(); BenchmarkActivity.current = null; }); }
            report(ok, result);
            return;
        }
        if (ocrOnly) {
            try { report(true, ScreenTextOcrFixtures.run(this)); }
            catch (Throwable error) { report(false, "SCREEN_TEXT_OCR_FIXTURES_FAIL " + android.util.Log.getStackTraceString(error)); }
            return;
        }
        if (controlsOnly) {
            try { report(true, ScreenTextControls.run(this)); }
            catch (Throwable error) { report(false, "SCREEN_TEXT_CONTROLS_FAIL " + android.util.Log.getStackTraceString(error)); }
            return;
        }
        new Thread(() -> { SystemClock.sleep(55000); report(false, "SCREEN_TEXT_UI_TIMEOUT"); }, "test-timeout").start();
        ClipboardManager clipboard = getTargetContext().getSystemService(ClipboardManager.class);
        ClipData original = null;
        int rotation = Settings.System.getInt(getTargetContext().getContentResolver(), Settings.System.USER_ROTATION, 0);
        boolean autoRotate = Settings.System.getInt(getTargetContext().getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        boolean changedRotation = false;
        boolean passed = false;
        String outcome = "SCREEN_TEXT_UI_FAIL";
        try {
            ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS; ui.setServiceInfo(info);
            ui.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND");
            original = clipboard.getPrimaryClip();
            try (var pipe = new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("am start -n local.voxfix.debug/.MainActivity"))) { pipe.readAllBytes(); }
            await(HANDLE);
            if (keyboardVisible()) { ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); SystemClock.sleep(500); }
            if (keyboardVisible()) throw new AssertionError("Keyboard still visible");
            open(false); click("全选"); click("复制选中"); await(HANDLE);
            String copied = clipboardText(clipboard);
            if (!copied.contains("VoxFix") || !copied.contains("输入法")) throw new AssertionError("Copied fixture incomplete");
            open(true);
            var bounds = getTargetContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            long down = SystemClock.uptimeMillis();
            touch(down, MotionEvent.ACTION_DOWN, bounds.width() * .03f, bounds.height() * .05f);
            touch(down, MotionEvent.ACTION_MOVE, bounds.width() * .97f, bounds.height() * .85f);
            touch(down, MotionEvent.ACTION_UP, bounds.width() * .97f, bounds.height() * .85f);
            click("全选"); click("复制选中"); await(HANDLE);
            String region = clipboardText(clipboard);
            if (!region.contains("VoxFix")) throw new AssertionError("Region fixture incomplete");
            open(false); await("全选");
            if (!ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) throw new AssertionError("Back refused");
            await(HANDLE);
            open(false); await("全选");
            changedRotation = true;
            if (!ui.setRotation(UiAutomation.ROTATION_FREEZE_90)) throw new AssertionError("Rotation refused");
            await(HANDLE);
            var landscape = getTargetContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            if (landscape.width() <= landscape.height()) throw new AssertionError("Landscape not applied");
            open(false); click("全选"); click("复制选中"); await(HANDLE);
            if (!clipboardText(clipboard).contains("VoxFix")) throw new AssertionError("Landscape OCR/copy failed");
            passed = true;
            outcome = "SCREEN_TEXT_UI_CAPTURE_SELECT_COPY_REGION_BACK_LANDSCAPE_PASS keyboardHidden=true chars=" + copied.length() + " regionChars=" + region.length();
        } catch (Throwable error) { outcome = "SCREEN_TEXT_UI_FAIL " + error; }
        finally {
            if (ui != null) {
                try {
                    AccessibilityNodeInfo exit = find("拖动调整面板，上拖展开，下拖关闭；点按切换半屏与全屏"); if (exit != null) exit.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                    if (changedRotation) {
                        ui.setRotation(rotation);
                        if (autoRotate) ui.setRotation(UiAutomation.ROTATION_UNFREEZE);
                    }
                    if (original != null) clipboard.setPrimaryClip(original);
                } catch (Throwable error) {
                    passed = false; outcome = "SCREEN_TEXT_UI_CLEANUP_FAIL " + error;
                } finally { ui.dropShellPermissionIdentity(); }
            }
        }
        report(passed, outcome);
    }
    private boolean keyboardVisible() {
        for (var window : ui.getWindows()) if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        return false;
    }
    private String clipboardText(ClipboardManager manager) {
        ClipData clip = manager.getPrimaryClip();
        return clip == null ? "" : clip.getItemAt(0).coerceToText(getTargetContext()).toString();
    }
    private AccessibilityNodeInfo find(String label) {
        for (var window : ui.getWindows()) {
            var root = window.getRoot(); if (root == null || !"local.voxfix.debug".equals(String.valueOf(root.getPackageName()))) continue;
            var node = findNode(root, label); if (node != null) return node;
        }
        return null;
    }
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String label) {
        if (node.isVisibleToUser() && node.isEnabled() && node.isClickable() &&
            (label.equals(String.valueOf(node.getText())) || label.equals(String.valueOf(node.getContentDescription())))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i); if (child == null) continue;
            var found = findNode(child, label); if (found != null) return found;
        }
        return null;
    }
    private void open(boolean region) { click(HANDLE); click(region ? "框选取字" : "整屏取字"); }
    private AccessibilityNodeInfo await(String label) {
        long end = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < end) {
            var node = find(label); if (node != null) return node;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing enabled control: " + label);
    }
    private void click(String label) { if (!await(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Click refused: " + label); }
    private void touch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!ui.injectInputEvent(event, true)) throw new AssertionError("Touch refused"); }
        finally { event.recycle(); }
    }
}
