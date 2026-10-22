package id.rfl.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Cangkang: satu WebView ke server lokal + urutan hidup pertama
 * (ekstrak payload -> nyalakan server -> cek update berkala).
 * Cangkang ini dirancang TIDAK berubah lama; semua fitur hidup di app/ (JS).
 */
public class MainActivity extends Activity {
    private static final String HOME = "http://127.0.0.1:3000/";
    private WebView web;
    private TextView splash;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        splash = new TextView(this);
        splash.setText("RFL-ASSISTANT — menyiapkan server…");
        splash.setPadding(48, 48, 48, 48);
        setContentView(splash);

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }

        final Layout layout = new Layout(this);
        new Thread(() -> {
            try {
                Payload.extractIfNeeded(MainActivity.this, layout);
            } catch (Exception e) {
                showText("Gagal menyiapkan berkas awal: " + e.getMessage());
                return;
            }
            ServerService.start(MainActivity.this);
            // Cek update komponen di latar; bila ada yang ditukar, server
            // direstart dan WebView dimuat ulang.
            boolean changed = new Updater(layout, msg -> { }).checkAndUpdate();
            if (changed) ServerService.restart(MainActivity.this);
            waitServerThenOpen();
        }, "rfl-boot").start();
    }

    private void waitServerThenOpen() {
        for (int i = 0; i < 60; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(HOME + "api/ping").openConnection();
                c.setConnectTimeout(1000);
                c.setReadTimeout(1000);
                if (c.getResponseCode() == 200) {
                    c.disconnect();
                    ui.post(this::openWeb);
                    return;
                }
                c.disconnect();
            } catch (Exception ignored) { }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        showText("Server belum menyala. Tutup dan buka lagi aplikasinya;\n" +
                "log ada di berkas data aplikasi (server.log).");
    }

    private void openWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl(HOME);
    }

    private void showText(String msg) {
        ui.post(() -> splash.setText(msg));
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // Server SENGAJA dibiarkan hidup di latar (ServerService).
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
