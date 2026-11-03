package local.voxfix.screentest;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same image and detected lines for both candidates; never reads the user's display. */
final class ScreenTextOfflineCompare {
    private static final String[][] EXTRA = {
        {"本月营业收入128,600.00元。", "应交税费与管理费用需分别核对。", "凭证日期：2026-11-03；附件共12张。"},
        {"请把屏幕上的这段文字复制下来。", "重选失败时保留已经编辑的内容。", "不联网、不上传图片、不保存识别历史。"},
        {"姓名：陈嘉欣、林晓燕、王梓涵", "地址：广东省广州市天河区中山大道西", "备注：请勿将未付款订单标为已付款。"},
        {"模型加载完成，识别结果仅供核对。", "端口8080；内存256MB；版本v1.17.3", "路径 D:/Projects/VoxFix / 文件名 report.xlsx"},
        {"辨辩瓣辫 赢嬴羸 汤烫 托拖", "零售总额、账户余额、累计折旧", "己已巳、未末、日目、土士、由甲申"},
        {"Email: hello+ocr@example.org", "https://example.com/a?id=120&v=3", "Total: CNY 1,280.50 / Discount: 8.5%"}
    };

    static String exportFixtures(Instrumentation test) throws Exception {
        var corpus = new ArrayList<String[]>(Arrays.asList(ScreenTextOcrBenchmark.TEXT));
        corpus.addAll(Arrays.asList(EXTRA));
        File directory = new File(test.getTargetContext().getFilesDir(), "ocr-fixtures");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create fixture directory");
        var manifest = new JSONArray();
        for (int size : new int[]{16,24,36,64}) for (boolean dark : new boolean[]{false,true}) {
            for (int sample = 0; sample < corpus.size(); sample++) {
                Bitmap image = fixture(size, dark, corpus.get(sample));
                String name = "sample-" + sample + "-" + size + "-" + dark + ".png";
                try (var stream = new java.io.FileOutputStream(new File(directory,name))) {
                    if (!image.compress(Bitmap.CompressFormat.PNG,100,stream)) throw new IllegalStateException("PNG export failed");
                } finally { image.recycle(); }
                manifest.put(new JSONObject().put("file",name).put("sample",sample).put("size",size).put("dark",dark).put("expected",String.join("\n",corpus.get(sample))));
            }
        }
        Files.write(new File(directory,"manifest.json").toPath(),manifest.toString().getBytes(StandardCharsets.UTF_8));
        return "SCREEN_TEXT_FIXTURE_EXPORT_PASS images=" + manifest.length();
    }

    private static Bitmap fixture(int size, boolean dark, String[] lines) {
        Bitmap image = Bitmap.createBitmap(size * 34, size * 6, Bitmap.Config.ARGB_8888);
        var canvas = new Canvas(image); canvas.drawColor(dark ? 0xff202124 : Color.WHITE);
        var paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(dark ? 0xfff1f3f4 : Color.BLACK); paint.setTextSize(size);
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], size, size * (1.5f + i * 1.5f), paint);
        return image;
    }

    static String run(Instrumentation test) throws Exception {
        if (test.getTargetContext().checkSelfPermission("android.permission.INTERNET") == android.content.pm.PackageManager.PERMISSION_GRANTED) throw new AssertionError("Offline test must have no INTERNET permission");
        ScreenTextPaddleRecognizer.selfCheck();
        var corpus = new ArrayList<String[]>(Arrays.asList(ScreenTextOcrBenchmark.TEXT));
        corpus.addAll(Arrays.asList(EXTRA));
        var rows = new JSONArray();
        var memory = new JSONArray();
        long start = SystemClock.elapsedRealtime();
        try (var small = new ScreenTextPaddleRecognizer(test.getTargetContext(), "PP-OCRv6_rec_small.onnx");
             var medium = new ScreenTextPaddleRecognizer(test.getTargetContext(), "PP-OCRv6_rec_medium.onnx")) {
            int done = 0;
            for (int round = 0; round < 2; round++) {
                for (int size : new int[]{16,24,36,64}) for (boolean dark : new boolean[]{false,true}) {
                    for (int sample = 0; sample < corpus.size(); sample++) {
                        compare(rows, round, sample, size, dark, corpus.get(sample), small, medium);
                        if (++done % 8 == 0) {
                            Bundle progress = new Bundle(); progress.putString("progress", "OFFLINE_OCR " + done + "/196"); test.sendStatus(1, progress);
                        }
                    }
                }
                for (boolean dark : new boolean[]{false,true}) { compare(rows, round, -1, 36, dark, new String[0], small, medium); done++; }
                System.gc();
                memory.put(new JSONObject().put("round", round).put("pssKb", Debug.getPss()));
            }
            if (rows.length() != 588) throw new AssertionError("Incomplete comparison: " + rows.length());
            var output = new JSONObject().put("rows", rows).put("memory", memory)
                .put("smallLoadMs", small.loadMs).put("mediumLoadMs", medium.loadMs)
                .put("elapsedMs", SystemClock.elapsedRealtime() - start).put("internetPermission", false)
                .put("pipeline", "MLKit 16.0.1 line bounds + PP-OCRv6 recognition, CPU 2 threads");
            File file = new File(test.getTargetContext().getFilesDir(), "offline-compare.json");
            Files.write(file.toPath(), output.toString().getBytes(StandardCharsets.UTF_8));
            return "OFFLINE_OCR_COMPARE_PASS rows=" + rows.length() + " output=files/offline-compare.json";
        }
    }

    private static void compare(JSONArray rows, int round, int sample, int size, boolean dark, String[] lines,
                                ScreenTextPaddleRecognizer small, ScreenTextPaddleRecognizer medium) throws Exception {
        Bitmap image = fixture(size, dark, lines);
        var mlkit = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        var crops = new ArrayList<Bitmap>();
        try {
            long started = SystemClock.elapsedRealtime();
            var baseline = Tasks.await(mlkit.process(InputImage.fromBitmap(image, 0)), 20, TimeUnit.SECONDS);
            long baselineMs = SystemClock.elapsedRealtime() - started;
            rows.put(row("mlkit", round, sample, size, dark, lines, baseline.getText(), baselineMs, 0));
            for (var block : baseline.getTextBlocks()) for (var line : block.getLines()) {
                if (line.getBoundingBox() == null) throw new AssertionError("Missing line box");
                Rect box = new Rect(line.getBoundingBox());
                int margin = Math.max(1, Math.round(box.height() * .05f)); box.inset(-margin,-margin);
                if (!box.intersect(0,0,image.getWidth(),image.getHeight()) || box.isEmpty()) throw new AssertionError("Invalid line box");
                crops.add(Bitmap.createBitmap(image,box.left,box.top,box.width(),box.height()));
            }
            for (String name : new String[]{"small", "medium"}) {
                var recognizer = name.equals("small") ? small : medium;
                var recognized = new ArrayList<String>();
                started = SystemClock.elapsedRealtime();
                for (Bitmap crop : crops) recognized.add(recognizer.recognize(crop));
                long extraMs = SystemClock.elapsedRealtime() - started;
                rows.put(row(name, round, sample, size, dark, lines, String.join("\n", recognized), baselineMs + extraMs, extraMs));
            }
        } finally { for (Bitmap crop : crops) crop.recycle(); mlkit.close(); image.recycle(); }
    }

    private static JSONObject row(String engine, int round, int sample, int size, boolean dark, String[] lines, String actual, long ms, long extraMs) throws Exception {
        return new JSONObject().put("engine",engine).put("round",round).put("sample",sample).put("size",size).put("dark",dark)
            .put("expected",String.join("\n",lines)).put("actual",actual).put("ms",ms).put("extraMs",extraMs);
    }
}
