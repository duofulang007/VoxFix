package local.voxfix.screentest;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** Generated text only: never capture the screen or open user files. */
final class ScreenTextOcrFixtures {
    static String run(Instrumentation test) throws Exception {
        var recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        var rows = new JSONArray();
        try {
            for (int size : new int[]{18, 24, 36, 64, 96, 128}) {
                for (boolean dark : new boolean[]{false, true}) {
                    String[] lines = {"离线屏幕取字", "订单金额 1,280.50 元", "VoxFix 12345"};
                    Bitmap source = Bitmap.createBitmap(size * 18, size * 6, Bitmap.Config.ARGB_8888);
                    try {
                        var canvas = new Canvas(source);
                        canvas.drawColor(dark ? 0xff202124 : Color.WHITE);
                        var paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        paint.setColor(dark ? 0xfff1f3f4 : Color.BLACK); paint.setTextSize(size);
                        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], size, size * (1.5f + i * 1.5f), paint);
                        var first = Tasks.await(recognizer.process(InputImage.fromBitmap(source, 0)), 20, TimeUnit.SECONDS);
                        for (int scale : new int[]{1, 2, 3}) {
                            Bitmap input = scale != 2 ? source : Bitmap.createScaledBitmap(source, source.getWidth() * scale, source.getHeight() * scale, true);
                            try {
                                long start = android.os.SystemClock.elapsedRealtime();
                                String value;
                                if (scale == 3) {
                                    var extracted = new java.util.ArrayList<String>();
                                    for (var block : first.getTextBlocks()) for (var line : block.getLines()) {
                                        Rect bounds = new Rect(line.getBoundingBox());
                                        int padding = Math.max(4, bounds.height() / 4);
                                        bounds.inset(-padding, -padding); bounds.intersect(0, 0, source.getWidth(), source.getHeight());
                                        Bitmap crop = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height());
                                        Bitmap normalized = Bitmap.createScaledBitmap(crop, Math.max(1, crop.getWidth() * 64 / crop.getHeight()), 64, true);
                                        try { extracted.add(Tasks.await(recognizer.process(InputImage.fromBitmap(normalized, 0)), 20, TimeUnit.SECONDS).getText()); }
                                        finally { if (normalized != crop) normalized.recycle(); crop.recycle(); }
                                    }
                                    value = String.join("\n", extracted);
                                } else value = Tasks.await(recognizer.process(InputImage.fromBitmap(input, 0)), 20, TimeUnit.SECONDS).getText();
                                rows.put(new JSONObject().put("size", size).put("dark", dark).put("scale", scale)
                                    .put("expected", String.join("\n", lines)).put("actual", value)
                                    .put("ms", android.os.SystemClock.elapsedRealtime() - start));
                            } finally { if (input != source) input.recycle(); }
                        }
                    } finally { source.recycle(); }
                }
            }
            return rows.toString();
        } finally { recognizer.close(); }
    }
}
