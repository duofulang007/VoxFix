package local.voxfix.screentest;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic fixtures only; lifecycle matches the service's recognizer per request. */
final class ScreenTextOcrBenchmark {
    static final String[][] TEXT = {
        {"离线屏幕取字，复制选中文字。", "请检查屏幕亮度和识别结果。", "会议时间：2026年11月3日 下午三点"},
        {"订单金额 1,280.50 元", "优惠 80.00 元，实付 1,200.50 元", "发票号码：12345678901234567890"},
        {"VoxFix Desktop and Android", "Email: test@example.com", "OpenAI API v2.3.1 / Wi-Fi 6"},
        {"未末 由甲申 土士 日目 己已巳", "幕募暮慕 金额颜色领导", "请勿付款，不要删除，尚未提交。"},
        {"广东省湛江市赤坎区人民大道北123号", "联系人：张晓明，电话 13800138000", "收件备注：工作日送达，请先联系。"},
        {"收入 5000.00 支出 3280.50", "应收账款、应付账款、累计折旧", "余额 = 1719.50；税率 13%"}
    };
    static String run() throws Exception {
        var rows = new JSONArray();
        var memory = new JSONArray();
        long started = SystemClock.elapsedRealtime();
        for (int round = 0; round < 5; round++) {
            for (int size : new int[]{16, 24, 36, 64}) {
                for (boolean dark : new boolean[]{false, true}) {
                    for (int sample = 0; sample < TEXT.length; sample++) {
                        rows.put(recognize(round, sample, size, dark, TEXT[sample]));
                    }
                }
            }
            for (boolean dark : new boolean[]{false, true}) {
                rows.put(recognize(round, -1, 36, dark, new String[0]));
            }
            System.gc();
            memory.put(new JSONObject().put("round", round).put("pssKb", Debug.getPss()));
        }
        if (rows.length() != 250) throw new AssertionError("Incomplete benchmark");
        return new JSONObject().put("rows", rows).put("memory", memory)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).toString();
    }
    private static JSONObject recognize(int round, int sample, int size, boolean dark, String[] lines) throws Exception {
        Bitmap image = Bitmap.createBitmap(size * 34, size * 6, Bitmap.Config.ARGB_8888);
        var recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try {
            var canvas = new Canvas(image);
            canvas.drawColor(dark ? 0xff202124 : Color.WHITE);
            var paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(dark ? 0xfff1f3f4 : Color.BLACK);
            paint.setTextSize(size);
            for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], size, size * (1.5f + i * 1.5f), paint);
            long start = SystemClock.elapsedRealtime();
            String actual = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 20, TimeUnit.SECONDS).getText();
            return new JSONObject().put("round", round).put("sample", sample).put("size", size).put("dark", dark)
                .put("expected", String.join("\n", lines)).put("actual", actual)
                .put("ms", SystemClock.elapsedRealtime() - start);
        } finally { recognizer.close(); image.recycle(); }
    }
}
