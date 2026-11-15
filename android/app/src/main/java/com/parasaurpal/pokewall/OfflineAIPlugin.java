package com.parasaurpal.pokewall;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "OfflineAI")
public class OfflineAIPlugin extends Plugin {
    private static final String MODEL_URL = "https://huggingface.co/genai-archive/stable-diffusion-v1-5-inpainting/resolve/main/sd-v1-5-inpainting.fp16.safetensors";
    private static final String MODEL_NAME = "sd-v1-5-inpainting.fp16.safetensors";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private File modelFile() {
        File dir = new File(getContext().getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MODEL_NAME);
    }

    @PluginMethod
    public void modelStatus(PluginCall call) {
        File model = modelFile();
        JSObject out = new JSObject();
        out.put("installed", model.exists() && model.length() > 1_000_000_000L);
        out.put("bytes", model.exists() ? model.length() : 0);
        out.put("modelName", MODEL_NAME);
        call.resolve(out);
    }

    @PluginMethod
    public void downloadModel(PluginCall call) {
        worker.execute(() -> {
            File target = modelFile();
            File partial = new File(target.getParentFile(), MODEL_NAME + ".download");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("Model download HTTP " + code);
                long total = connection.getContentLengthLong();
                long done = 0;
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        done += n;
                        JSObject progress = new JSObject();
                        progress.put("downloaded", done);
                        progress.put("total", total);
                        progress.put("percent", total > 0 ? (int)(done * 100 / total) : 0);
                        notifyListeners("modelProgress", progress);
                    }
                    out.getFD().sync();
                }
                if (partial.length() < 1_000_000_000L) throw new IOException("Downloaded model is incomplete.");
                if (target.exists()) target.delete();
                if (!partial.renameTo(target)) throw new IOException("Could not finalize model file.");
                JSObject result = new JSObject();
                result.put("installed", true);
                result.put("bytes", target.length());
                call.resolve(result);
            } catch (Exception e) {
                partial.delete();
                call.reject("Model download failed: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    @PluginMethod
    public void generate(PluginCall call) {
        String image = call.getString("image");
        String mask = call.getString("mask");
        String prompt = call.getString("prompt", "Seamlessly extend the existing fantasy illustration into a coherent full-bleed vertical wallpaper, matching its subject, palette, lighting, materials and painterly style. Continue the environment naturally. No card frame, borders, text, lettering, symbols, numbers or interface elements.");
        if (image == null || mask == null) {
            call.reject("Image and mask are required.");
            return;
        }
        worker.execute(() -> {
            try {
                File model = modelFile();
                if (!model.exists() || model.length() < 1_000_000_000L) throw new IOException("Download the Offline AI model first.");
                File work = new File(getContext().getFilesDir(), "offline-ai");
                if (!work.exists()) work.mkdirs();
                File input = new File(work, "input.png");
                File maskFile = new File(work, "mask.png");
                File output = new File(work, "output.png");
                writeBase64(input, image);
                writeBase64(maskFile, mask);
                if (output.exists()) output.delete();

                File executable = new File(getContext().getFilesDir(), "sd-cli");
                if (!executable.exists()) {
                    try (InputStream in = getContext().getAssets().open("sd-cli");
                         FileOutputStream out = new FileOutputStream(executable)) {
                        byte[] b = new byte[65536]; int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    }
                    executable.setExecutable(true, true);
                }
                if (!executable.canExecute()) throw new IOException("Offline inference engine is not installed in this APK.");

                ProcessBuilder pb = new ProcessBuilder(
                    executable.getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "-i", input.getAbsolutePath(),
                    "--mask", maskFile.getAbsolutePath(),
                    "-o", output.getAbsolutePath(),
                    "-p", prompt,
                    "--width", "512",
                    "--height", "1088",
                    "--steps", "18",
                    "--cfg-scale", "7",
                    "--strength", "0.98"
                );
                pb.directory(work);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                StringBuilder log = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (log.length() < 12000) log.append(line).append('\n');
                        JSObject event = new JSObject();
                        event.put("message", line);
                        notifyListeners("generationLog", event);
                    }
                }
                int exit = process.waitFor();
                if (exit != 0 || !output.exists() || output.length() < 1000) {
                    throw new IOException("Inference failed (exit " + exit + "). " + log.toString());
                }
                byte[] bytes = readAll(output);
                JSObject result = new JSObject();
                result.put("image", Base64.encodeToString(bytes, Base64.NO_WRAP));
                result.put("mimeType", "image/png");
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Offline AI generation failed: " + e.getMessage());
            }
        });
    }

    private static void writeBase64(File file, String value) throws IOException {
        String clean = value.contains(",") ? value.substring(value.indexOf(',') + 1) : value;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(Base64.decode(clean, Base64.DEFAULT));
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}