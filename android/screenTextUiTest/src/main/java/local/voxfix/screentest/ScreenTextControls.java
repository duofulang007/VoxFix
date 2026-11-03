package local.voxfix.screentest;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/** Detached fixture views: no host touch injection, screenshot capture, clipboard change or IME restart. */
final class ScreenTextControls {
    static String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        ClassLoader loader = context.createPackageContext("local.voxfix.debug", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Class<?> f0 = loader.loadClass("kotlin.jvm.functions.Function0");
        Class<?> f1 = loader.loadClass("kotlin.jvm.functions.Function1");
        Object noop = Proxy.newProxyInstance(loader, new Class<?>[]{f0}, (p, m, a) -> unit);
        Object recognize = Proxy.newProxyInstance(loader, new Class<?>[]{f1}, (p, m, a) -> unit);
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            Bitmap source = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888);
            source.eraseColor(Color.WHITE);
            try {
                Class<?> type = loader.loadClass("local.voxfix.debug.ScreenTextOverlay");
                Object overlay = type.getConstructor(Context.class, Bitmap.class, f1, f0, f0).newInstance(context, source, recognize, noop, noop);
                type.getMethod("result", String.class, String.class).invoke(overlay, "第一行文字\n第二行文字", null);
                EditText editor = (EditText) field(overlay, "text");
                editor.getText().replace(0, 3, "已修改");
                editor.setSelection(1, 8);
                check(editor.getText().toString().startsWith("已修改"), "Result is not editable");
                check(editor.getText().subSequence(editor.getSelectionStart(), editor.getSelectionEnd()).toString().contains("\n"), "Cross-line selection failed");
                ViewGroup root = (ViewGroup) field(overlay, "root");
                android.view.WindowManager recordingWindow = (android.view.WindowManager) Proxy.newProxyInstance(loader,
                    new Class<?>[]{android.view.WindowManager.class}, (p, m, a) -> {
                        if (m.getName().equals("addView")) {
                            var params = (android.view.WindowManager.LayoutParams) a[1];
                            check((params.flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) == 0,
                                "Screen text overlay blocks screenshots and screen recordings");
                        }
                        return null;
                    });
                type.getMethod("show", android.view.WindowManager.class).invoke(overlay, recordingWindow);
                var labels = new ArrayList<String>();
                labels(root, labels);
                check(labels.size() == 3 && labels.contains("重选") && labels.contains("全选") && labels.contains("复制选中"), "Duplicate/missing controls: " + labels);
                float unitSize = context.getResources().getDisplayMetrics().density;
                int width = (int) (412 * unitSize);
                int height = (int) (915 * unitSize);
                layout(root, width, height);
                check(editor.getHeight() > height / 5, "Editor too short");
                View sheet = (View) field(overlay, "sheet");
                View toolbar = (View) field(overlay, "toolbar");
                check(sheet.getTranslationY() > height * .35f && sheet.getTranslationY() < height * .55f, "Portrait must start at half height: y=" + sheet.getTranslationY() + " height=" + height + " available=" + field(overlay, "availableHeight"));
                check(toolbar.getBottom() <= root.getHeight(), "Toolbar outside screen");
                save(context, root, "screen-text-editor.png");
                var fullScreen = type.getDeclaredField("fullScreen"); fullScreen.setAccessible(true); fullScreen.set(overlay, true);
                // A geometry change runs the same layout path used by IME insets and rotation.
                layout(root, width, height - 2); layout(root, width, height);
                check(sheet.getTranslationY() == 0, "Full screen did not expand");
                check(editor.getSelectionStart() == 1 && editor.getSelectionEnd() == 8, "Resizing lost the selection");
                check(editor.getText().toString().startsWith("已修改"), "Resizing changed the edited text");
                String edited = editor.getText().toString();
                type.getMethod("loading").invoke(overlay);
                check(editor.getText().toString().equals(edited) && !editor.isEnabled(), "Recognition must retain and freeze the previous result");
                type.getMethod("result", String.class, String.class).invoke(overlay, "", "识别失败，请框选重试");
                check(editor.getText().toString().equals(edited) && editor.isEnabled(), "Failed reselect lost edited text");
                check(editor.getSelectionStart() == 1 && editor.getSelectionEnd() == 8, "Failed reselect lost selection");
                type.getMethod("loading").invoke(overlay);
                type.getMethod("result", String.class, String.class).invoke(overlay, "", null);
                check(editor.getText().toString().equals(edited), "Empty reselect lost edited text");
                Object retained = type.getMethod("retainedResult").invoke(overlay);
                Object textOnly = type.getConstructor(Context.class, Bitmap.class, f1, f0, f0).newInstance(context, null, recognize, noop, noop);
                type.getMethod("restoreResult", retained.getClass()).invoke(textOnly, retained);
                View restoredRoot = (View) field(textOnly, "root");
                layout(restoredRoot, height, width);
                EditText restoredEditor = (EditText) field(textOnly, "text");
                check(restoredEditor.getText().toString().equals(edited) && restoredEditor.getSelectionStart() == 1 && restoredEditor.getSelectionEnd() == 8, "Text-only restoration lost text/selection");
                check(!((View) field(textOnly, "region")).isEnabled() && ((View) field(textOnly, "copy")).isEnabled(), "Released screenshot must prevent stale crops but allow copying");
                type.getMethod("startRegion").invoke(textOnly);
                check(field(textOnly, "cropView") == null, "Text-only result must not crop a released image");
                check(((android.widget.ImageView) field(textOnly, "background")).getDrawable() == null, "Text-only result retained a screenshot");
                save(context, root, "screen-text-editor-full.png");
                layout(root, height, width);
                check(sheet.getTranslationY() == 0 && editor.getHeight() > 0, "Landscape must use full available space");
                layout(root, width, height);
                fullScreen.set(overlay, false);
                layout(root, (int) (375 * unitSize), (int) (667 * unitSize));
                float half = sheet.getTranslationY();
                var insets = new android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, (int) (24 * unitSize), 0, (int) (24 * unitSize)))
                    .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, (int) (280 * unitSize)))
                    .setVisible(android.view.WindowInsets.Type.ime(), true).build();
                root.dispatchApplyWindowInsets(insets);
                layout(root, (int) (375 * unitSize), (int) (667 * unitSize));
                check(sheet.getTranslationY() == 0 && editor.getHeight() > 0, "IME must leave editable space on a small phone");
                check(toolbar.getBottom() <= root.getHeight() - (int) (280 * unitSize), "Toolbar behind the IME");
                root.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder().build());
                layout(root, (int) (375 * unitSize), (int) (667 * unitSize));
                check(sheet.getTranslationY() == half, "Hiding IME must restore the half-screen position");
                View grip = (View) field(overlay, "grip");
                long now = android.os.SystemClock.uptimeMillis();
                touch(grip, now, now, android.view.MotionEvent.ACTION_DOWN, 400);
                touch(grip, now, now + 16, android.view.MotionEvent.ACTION_MOVE, 340);
                check(Math.abs(sheet.getTranslationY() - (half - 60)) < 1, "Grip must track the finger one to one");
                touch(grip, now, now + 32, android.view.MotionEvent.ACTION_CANCEL, 340);
                finishMotion(overlay);
                check(sheet.getTranslationY() == half, "Cancelled drag must return to half screen");
                grip.performClick();
                finishMotion(overlay);
                check(sheet.getTranslationY() == 0, "Grip tap must expand");
                grip.performClick();
                sheet.setTranslationY(half * .4f);
                float interrupted = sheet.getTranslationY();
                grip.performClick();
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) check(Math.abs(sheet.getTranslationY() - interrupted) < 1, "Reversing an animation must retain its current position");
                finishMotion(overlay);
                check(sheet.getTranslationY() == 0, "Reversed animation must reach full screen");
                type.getMethod("result", String.class, String.class).invoke(overlay, "", null);
                check(!((View) field(overlay, "copy")).isEnabled(), "Empty copy should be disabled");
                editor.setText("新内容");
                check(((View) field(overlay, "copy")).isEnabled(), "Edited content should enable copy");
                type.getMethod("startRegion").invoke(overlay);
                check(((View) field(overlay, "content")).getVisibility() == View.GONE, "Region must hide editor");
                check(((View) field(overlay, "scrim")).getVisibility() == View.GONE, "Region must preserve the source image brightness");
                layout(root, width, height);
                save(context, root, "screen-text-region.png");
                type.getMethod("loading").invoke(overlay);
                check(((View) field(overlay, "content")).getVisibility() == View.VISIBLE, "Result sheet did not return");
                Class<?> handleType = loader.loadClass("local.voxfix.debug.ScreenTextHandle");
                Object handle = handleType.getConstructor(Context.class, f1).newInstance(context, recognize);
                ViewGroup menu = (ViewGroup) field(handle, "root");
                check(menu.getAlpha() == 0f && menu.getVisibility() == View.VISIBLE, "New arc must stay transparent until its positioned first frame");
                menu.setAlpha(1f); // Draw a detached fixture without adding a system window.
                float density = context.getResources().getDisplayMetrics().density;
                var params = (android.view.WindowManager.LayoutParams) field(handle, "params");
                layout(menu, params.width, params.height);
                save(context, menu, "screen-text-collapsed.png");
                params.width = (int) (104 * density); params.height = (int) (144 * density);
                var render = handleType.getDeclaredMethod("render", float.class); render.setAccessible(true); render.invoke(handle, .5f);
                View arc = (View) field(handle, "handle");
                check(arc.getVisibility() == View.VISIBLE && arc.getAlpha() > 0, "Arc must remain visible during the transition");
                render.invoke(handle, 1f);
                layout(menu, params.width, params.height);
                for (int i = 0; i < 3; i++) {
                    View child = menu.getChildAt(i);
                    check(child.getTranslationX() >= 0 && child.getTranslationX() + child.getWidth() <= menu.getWidth(), "Radial action clipped horizontally: " + i + " x=" + child.getTranslationX() + " width=" + child.getWidth() + " parent=" + menu.getWidth());
                    check(child.getTranslationY() >= 0 && child.getTranslationY() + child.getHeight() <= menu.getHeight(), "Radial action clipped vertically");
                }
                save(context, menu, "screen-text-expanded.png");
                var side = handleType.getDeclaredField("right"); side.setAccessible(true); side.set(handle, false);
                render.invoke(handle, 1f);
                for (int i = 0; i < 3; i++) {
                    View child = menu.getChildAt(i);
                    check(child.getTranslationX() >= 0 && child.getTranslationX() + child.getWidth() <= menu.getWidth(), "Left-side radial action clipped");
                }
                handleType.getMethod("dismiss").invoke(handle);
                check(menu.getVisibility() == View.GONE, "Removed arc must not leave a drawable frame");
                for (boolean outside : new boolean[]{false, true}) {
                    int[] closed = {0};
                    Object closeAction = Proxy.newProxyInstance(loader, new Class<?>[]{f0}, (p, m, a) -> { closed[0]++; return unit; });
                    Object dismissible = type.getConstructor(Context.class, Bitmap.class, f1, f0, f0).newInstance(context, source, recognize, closeAction, noop);
                    type.getMethod("result", String.class, String.class).invoke(dismissible, "用于检查关闭的固定文字", null);
                    View dismissRoot = (View) field(dismissible, "root");
                    layout(dismissRoot, width, height);
                    if (outside) ((View) field(dismissible, "scrim")).performClick();
                    else {
                        View drag = (View) field(dismissible, "grip");
                        long time = android.os.SystemClock.uptimeMillis();
                        touch(drag, time, time, android.view.MotionEvent.ACTION_DOWN, 100);
                        touch(drag, time, time + 400, android.view.MotionEvent.ACTION_MOVE, 100 + 140 * unitSize);
                        touch(drag, time, time + 800, android.view.MotionEvent.ACTION_UP, 100 + 140 * unitSize);
                    }
                    finishMotion(dismissible);
                    check(closed[0] == 1, "Drag/outside must dismiss exactly once: " + outside);
                    check(((EditText) field(dismissible, "text")).getText().toString().equals("用于检查关闭的固定文字"), "Dismissal must not mutate text");
                }
                var configuration = new android.content.res.Configuration(context.getResources().getConfiguration());
                configuration.fontScale = 1.6f;
                Context largeText = context.createConfigurationContext(configuration);
                Object accessible = type.getConstructor(Context.class, Bitmap.class, f1, f0, f0).newInstance(largeText, source, recognize, noop, noop);
                type.getMethod("result", String.class, String.class).invoke(accessible, "放大字体的固定检查文字", null);
                View a11yRoot = (View) field(accessible, "root");
                layout(a11yRoot, (int) (375 * unitSize), (int) (667 * unitSize));
                check(((View) field(accessible, "text")).getHeight() > 0, "Large text leaves no editor space");
                for (String name : new String[]{"all", "copy", "region"}) {
                    TextView action = (TextView) field(accessible, name);
                    check(action.getHeight() >= 48 * unitSize - 1 && action.getLayout().getLineCount() == 1, "Large-font action clipped/wrapped: " + name);
                    Bitmap label = Bitmap.createBitmap(action.getWidth(), action.getHeight(), Bitmap.Config.ARGB_8888);
                    try {
                        label.eraseColor(Color.MAGENTA);
                        Canvas labelCanvas = new Canvas(label);
                        labelCanvas.translate(-action.getScrollX(), -action.getScrollY());
                        action.draw(labelCanvas);
                        int ink = 0;
                        for (int y = 0; y < label.getHeight(); y++) for (int x = 0; x < label.getWidth(); x++) if (label.getPixel(x, y) == action.getCurrentTextColor()) ink++;
                        check(ink > 10, "Action label not rendered: " + name + " size=" + action.getTextSize() + " scroll=" + action.getScrollX() + "," + action.getScrollY() + " layout=" + action.getLayout().getWidth());
                    } finally { label.recycle(); }
                }
                save(context, a11yRoot, "screen-text-large-font.png");
                checkRegions(context, loader, source, f0, f1, noop, unit);
            } catch (Throwable error) { failure[0] = error; }
            finally { source.recycle(); }
        });
        if (failure[0] != null) throw new AssertionError("Native controls fixture failed", failure[0]);
        return "SCREEN_TEXT_NATIVE_HALF_FULL_DRAG_CANCEL_REVERSE_IME_SELECTION_DISMISS_LARGE_FONT_RADIAL_BOUNDS_RESELECT_RECOVERY_TEXT_RETENTION_REGION_MULTITOUCH_REFINE_SMALL_LINE_PASS";
    }
    private static void checkRegions(Context context, ClassLoader loader, Bitmap source, Class<?> f0, Class<?> f1, Object noop, Object unit) throws Exception {
        Class<?> type = loader.loadClass("local.voxfix.debug.ScreenTextOverlay");
        Object[] holder = {null};
        var picked = new ArrayList<android.graphics.Rect>();
        Object recognize = Proxy.newProxyInstance(loader, new Class<?>[]{f1}, (p, m, a) -> {
            picked.add(new android.graphics.Rect((android.graphics.Rect) a[0]));
            type.getMethod("loading").invoke(holder[0]);
            return unit;
        });
        holder[0] = type.getConstructor(Context.class, Bitmap.class, f1, f0, f0).newInstance(context, source, recognize, noop, noop);
        Object overlay = holder[0];
        View root = (View) field(overlay, "root");
        type.getMethod("startRegion").invoke(overlay); layout(root, 800, 1200);
        View crop = (View) field(overlay, "cropView");
        long now = android.os.SystemClock.uptimeMillis();
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 100, 100);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_MOVE, 500, 500);
        var properties = new android.view.MotionEvent.PointerProperties[2];
        var coords = new android.view.MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new android.view.MotionEvent.PointerProperties(); properties[i].id = i;
            properties[i].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new android.view.MotionEvent.PointerCoords(); coords[i].x = 500 + i * 100; coords[i].y = 500 + i * 100;
        }
        var multi = android.view.MotionEvent.obtain(now, now + 20, android.view.MotionEvent.ACTION_POINTER_DOWN | (1 << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { crop.dispatchTouchEvent(multi); } finally { multi.recycle(); }
        regionTouch(crop, now, android.view.MotionEvent.ACTION_MOVE, 700, 900);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 700, 900);
        check(picked.isEmpty(), "A second pointer must cancel the entire crop gesture");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 100, 100);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_MOVE, 500, 500);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 500, 500);
        check(picked.size() == 1 && picked.get(0).equals(new android.graphics.Rect(100, 100, 500, 500)), "Next single-pointer crop failed");
        type.getMethod("result", String.class, String.class).invoke(overlay, "区域结果", null);
        type.getMethod("startRegion").invoke(overlay); layout(root, 800, 1200);
        crop = (View) field(overlay, "cropView");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 300, 300);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_MOVE, 400, 400);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 400, 400);
        check(picked.get(1).equals(new android.graphics.Rect(200, 200, 600, 600)), "Reselect must move the old rectangle");
        type.getMethod("result", String.class, String.class).invoke(overlay, "区域结果", null);
        type.getMethod("startRegion").invoke(overlay); layout(root, 800, 1200);
        crop = (View) field(overlay, "cropView");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 600, 400);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 700, 400);
        check(picked.get(2).equals(new android.graphics.Rect(200, 200, 700, 600)), "Reselect must resize an edge");
        type.getMethod("result", String.class, String.class).invoke(overlay, "区域结果", null);
        type.getMethod("startRegion").invoke(overlay); layout(root, 800, 1200);
        crop = (View) field(overlay, "cropView");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 100, 1000);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 500, 1008);
        check(picked.get(3).equals(new android.graphics.Rect(100, 1000, 500, 1008)), "A narrow line with deliberate horizontal movement must be accepted");
        type.getMethod("result", String.class, String.class).invoke(overlay, "区域结果", null);
        type.getMethod("startRegion").invoke(overlay); layout(root, 800, 1200);
        crop = (View) field(overlay, "cropView");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 100, 200);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_CANCEL, 400, 800);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 400, 800);
        check(picked.size() == 4, "Cancelled crop must not recognize");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 100, 200);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 100, 200);
        check(picked.size() == 4, "A stationary tap must not recognize");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 300, 300);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 700, 700);
        check(picked.get(4).equals(new android.graphics.Rect(300, 300, 700, 700)), "A fresh rectangle after cancellation failed");
        type.getMethod("result", String.class, String.class).invoke(overlay, "区域结果", null);
        type.getMethod("startRegion").invoke(overlay); layout(root, 800, 1200);
        crop = (View) field(overlay, "cropView");
        regionTouch(crop, now, android.view.MotionEvent.ACTION_DOWN, 500, 500);
        regionTouch(crop, now, android.view.MotionEvent.ACTION_UP, 900, 1500);
        check(picked.get(5).equals(new android.graphics.Rect(400, 800, 800, 1200)), "Moving a rectangle must clamp to image bounds without resizing it");
    }
    private static void regionTouch(View view, long now, int action, float x, float y) {
        var event = android.view.MotionEvent.obtain(now, now + 40, action, x, y, 0);
        try { check(view.dispatchTouchEvent(event), "Region fixture refused touch"); } finally { event.recycle(); }
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void touch(View view, long down, long time, int action, float y) {
        var event = android.view.MotionEvent.obtain(down, time, action, 20, y, 0);
        try { check(view.dispatchTouchEvent(event), "Fixture gesture refused"); } finally { event.recycle(); }
    }
    private static void finishMotion(Object overlay) throws Exception {
        Object motion = field(overlay, "motion");
        if (motion instanceof android.animation.ValueAnimator) ((android.animation.ValueAnimator) motion).end();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void labels(View view, ArrayList<String> labels) {
        if (view instanceof android.widget.Button) labels.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) labels(((ViewGroup) view).getChildAt(i), labels);
    }
    private static void layout(View view, int width, int height) {
        for (int i = 0; i < 2; i++) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
        }
        preDraw(view);
    }
    private static void preDraw(View view) {
        view.getViewTreeObserver().dispatchOnPreDraw();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) preDraw(((ViewGroup) view).getChildAt(i));
    }
    private static void save(Context context, View view, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (var output = new FileOutputStream(new File(context.getCacheDir(), name))) {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE); view.draw(canvas);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
}
