package local.voxfix.screentest;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** Offline evaluation only. RapidOCR preprocessing/CTC contract is pinned in UPSTREAM.md. */
final class ScreenTextPaddleRecognizer implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String input;
    private final ArrayList<String> characters = new ArrayList<>();
    final long loadMs;

    ScreenTextPaddleRecognizer(Context context, String model) throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        File file = new File(context.getCacheDir(), model);
        try (var source = context.getAssets().open(model); var output = new FileOutputStream(file)) { source.transferTo(output); }
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(file.getAbsolutePath(), options);
        }
        try {
            input = session.getInputNames().iterator().next();
            var shape = ((TensorInfo) session.getInputInfo().get(input).getInfo()).getShape();
            if (shape.length != 4 || shape[1] != 3 || (shape[2] > 0 && shape[2] != 48)) throw new IllegalStateException("Unexpected input: " + Arrays.toString(shape));
            String dictionary = session.getMetadata().getCustomMetadata().get("character");
            if (dictionary == null) throw new IllegalStateException("Missing embedded dictionary");
            characters.add("");
            characters.addAll(Arrays.asList(dictionary.split("\\r?\\n")));
            characters.add(" ");
            loadMs = android.os.SystemClock.elapsedRealtime() - start;
        } catch (Exception error) { session.close(); throw error; }
    }

    String recognize(Bitmap image) throws Exception {
        int height = 48;
        int resizedWidth = (int) Math.ceil(height * image.getWidth() / (double) image.getHeight());
        int width = Math.max(320, resizedWidth);
        if (width > 8192) throw new IllegalArgumentException("Evaluation line too wide");
        Bitmap scaled = Bitmap.createScaledBitmap(image, resizedWidth, height, true);
        float[] values = new float[3 * height * width];
        int[] pixels = new int[resizedWidth * height];
        try { scaled.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, height); }
        finally { if (scaled != image) scaled.recycle(); }
        // BGR, CHW, [-1,1]; zero padding matches RapidOCR's default [3,48,320].
        for (int y = 0; y < height; y++) for (int x = 0; x < resizedWidth; x++) {
            int color = pixels[y * resizedWidth + x], at = y * width + x;
            values[at] = (color & 255) / 127.5f - 1;
            values[height * width + at] = ((color >> 8) & 255) / 127.5f - 1;
            values[2 * height * width + at] = ((color >> 16) & 255) / 127.5f - 1;
        }
        try (var tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3, height, width});
             var result = session.run(Collections.singletonMap(input, tensor))) {
            var output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            if (shape.length != 3 || shape[0] != 1 || shape[2] != characters.size()) throw new IllegalStateException("Unexpected output/dictionary: " + Arrays.toString(shape) + "/" + characters.size());
            var probabilities = output.getFloatBuffer();
            int steps = (int) shape[1], vocabulary = (int) shape[2];
            int[] ids = new int[steps];
            for (int t = 0; t < steps; t++) {
                int best = 0, offset = t * vocabulary;
                for (int c = 1; c < vocabulary; c++) if (probabilities.get(offset + c) > probabilities.get(offset + best)) best = c;
                ids[t] = best;
            }
            return decode(ids, characters);
        }
    }

    static String decode(int[] ids, java.util.List<String> dictionary) {
        StringBuilder text = new StringBuilder();
        int previous = -1;
        for (int id : ids) {
            if (id < 0 || id >= dictionary.size()) throw new IllegalArgumentException("Invalid CTC id");
            if (id != 0 && id != previous) text.append(dictionary.get(id));
            previous = id;
        }
        return text.toString();
    }
    static void selfCheck() {
        if (!decode(new int[]{0,1,1,0,1,2,2}, Arrays.asList("", "a", "b")).equals("aab")) throw new AssertionError("CTC repeat/blank decoding");
        try { decode(new int[]{3}, Arrays.asList("", "a")); throw new AssertionError("CTC boundary"); }
        catch (IllegalArgumentException expected) { }
    }
    @Override public void close() throws Exception { session.close(); }
}
