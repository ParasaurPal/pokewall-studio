package com.parasaurpal.pokewall;

import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.webkit.WebView;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OfflineAIPlugin.class);
        super.onCreate(savedInstanceState);
        WebView webView = getBridge().getWebView();
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("data:")) {
                try {
                    String b64 = url.substring(url.indexOf(",") + 1);
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    File file = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "pokewall-" + System.currentTimeMillis() + ".png"
                    );
                    try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(bytes); }
                    Toast.makeText(this, "Saved to Downloads: " + file.getName(), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}