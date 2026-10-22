package id.rfl.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Cangkang: layar muat hitam minimalis — merek "RFL-KSPULLX" beranimasi
 * di tengah (huruf naik satu-satu, berdenyut, memudar, mengulang) dan
 * gugus kemajuan di bawah. Urutan tampil DISENGAJA: animasi tampil dulu
 * ±2 detik, baru tahap hidup (ekstrak -> server -> update) berjalan;
 * unduhan komponen melaporkan byte asli ke bar kemajuan.
 * Semua fitur hidup di app/ (JS); cangkang jarang berubah.
 */
public class MainActivity extends Activity {
    private static final String HOME = "http://127.0.0.1:3007/";
    private static final String BRAND = "RFL-KSPULLX";
    private static final long INTRO_MS = 2000;

    private WebView web;
    private TextView status, percent, detail;
    private ProgressBar bar;
    private LinearLayout brandRow;
    private TextView[] letters = new TextView[0];
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean splashAlive = true;
    private volatile int lastPct = 0;

    // Mesin animasi merek: ENTER (huruf naik berurutan) -> HOLD (denyut
    // halus) -> EXIT (pudar) -> ulang. Semua dijaga splashAlive.
    private final Runnable animLoop = new Runnable() {
        int phase = 0;
        @Override public void run() {
            if (!splashAlive || brandRow == null) return;
            switch (phase) {
                case 0: // ENTER
                    for (int i = 0; i < letters.length; i++) {
                        letters[i].animate().alpha(1f).translationY(0f)
                                .setDuration(300).setStartDelay(i * 60).start();
                    }
                    phase = 1;
                    ui.postDelayed(this, letters.length * 60 + 420);
                    break;
                case 1: // HOLD — denyut lembut
                    brandRow.animate().scaleX(1.035f).scaleY(1.035f).setDuration(650).start();
                    phase = 2;
                    ui.postDelayed(this, 700);
                    break;
                case 2: // HOLD — kembali
                    brandRow.animate().scaleX(1f).scaleY(1f).setDuration(650).start();
                    phase = 3;
                    ui.postDelayed(this, 900);
                    break;
                default: // EXIT lalu reset untuk siklus berikutnya
                    brandRow.animate().alpha(0.15f).setDuration(420).start();
                    phase = 0;
                    ui.postDelayed(() -> {
                        if (!splashAlive || brandRow == null) return;
                        brandRow.setAlpha(1f);
                        float dip = 18 * getResources().getDisplayMetrics().density;
                        for (TextView t : letters) { t.setAlpha(0f); t.setTranslationY(dip); }
                        ui.postDelayed(animLoop, 60);
                    }, 470);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        percent = findViewById(R.id.percent);
        detail = findViewById(R.id.detail);
        bar = findViewById(R.id.bar);
        brandRow = findViewById(R.id.brandRow);
        buildLetters();
        ui.post(animLoop);

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }

        final Layout layout = new Layout(this);
        new Thread(() -> {
            setProgress("Menyiapkan…", 0, "Cangkang " + Updater.SHELL_VERSION);
            // SENGAJA ditahan: animasi tengah tampil dulu, unduhan menyusul.
            try { Thread.sleep(INTRO_MS); } catch (InterruptedException e) { return; }
            setProgress("Menyiapkan berkas awal…", 4, "Cangkang " + Updater.SHELL_VERSION);
            try {
                Payload.extractIfNeeded(MainActivity.this, layout);
            } catch (Exception e) {
                fail("Gagal menyiapkan berkas awal: " + e.getMessage());
                return;
            }
            setProgress("Menyalakan server…", 18, "");
            ServerService.start(MainActivity.this);
            setProgress("Memeriksa pembaruan…", 30, "");
            boolean changed = new Updater(layout, new Updater.Listener() {
                @Override public void onStatus(String msg) {
                    setProgress(msg, lastPct, "");
                }
                @Override public void onDownload(String component, long got, long total) {
                    int pct = total > 0 ? 30 + (int) (55 * got / total) : lastPct;
                    String det = total > 0
                            ? fmtMB(got) + " dari " + fmtMB(total) + "  •  komponen " + component
                            : fmtMB(got) + " terunduh  •  komponen " + component;
                    setProgress("Mengunduh pembaruan…", pct, det);
                }
            }).checkAndUpdate();
            if (changed) ServerService.restart(MainActivity.this);
            setProgress("Menghubungi server…", 88,
                    changed ? "Pembaruan terpasang" : "Sudah versi terbaru");
            waitServerThenOpen();
        }, "rfl-boot").start();
    }

    private void buildLetters() {
        float dip = 18 * getResources().getDisplayMetrics().density;
        letters = new TextView[BRAND.length()];
        for (int i = 0; i < BRAND.length(); i++) {
            TextView t = new TextView(this);
            t.setText(String.valueOf(BRAND.charAt(i)));
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setPadding(1, 0, 1, 0);
            t.setAlpha(0f);
            t.setTranslationY(dip);
            brandRow.addView(t);
            letters[i] = t;
        }
    }

    private static String fmtMB(long bytes) {
        return String.format("%.1f", bytes / 1048576.0).replace('.', ',') + " MB";
    }

    private void setProgress(String statusText, int pct, String detailText) {
        lastPct = pct;
        ui.post(() -> {
            if (status != null) status.setText(statusText);
            if (percent != null) percent.setText(pct + "%");
            if (bar != null) bar.setProgress(pct);
            if (detail != null) detail.setText(detailText == null ? "" : detailText);
        });
    }

    private void fail(String msg) {
        splashAlive = false;
        ui.post(() -> {
            if (status != null) status.setText(msg);
            if (percent != null) percent.setText("");
            if (detail != null) detail.setText("Log: Download/RFL-ASSISTANT/server.log");
        });
    }

    private void waitServerThenOpen() {
        for (int i = 0; i < 60; i++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(HOME + "api/ping").openConnection();
                c.setConnectTimeout(1000);
                c.setReadTimeout(1000);
                if (c.getResponseCode() == 200) {
                    c.disconnect();
                    setProgress("Siap", 100, "");
                    ui.post(this::openWeb);
                    return;
                }
                c.disconnect();
            } catch (Exception ignored) { }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        fail("Server belum menyala. Tutup dan buka lagi aplikasinya.");
    }

    private void openWeb() {
        splashAlive = false;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.setAlpha(0f);
        web.animate().alpha(1f).setDuration(350).start();
        web.loadUrl(HOME);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        splashAlive = false;
        // Server SENGAJA dibiarkan hidup di latar (ServerService).
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
