package id.rfl.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Menjalankan server Node (app/server.js) sebagai proses anak di layanan
 * latar depan, dan menghidupkannya lagi bila mati (kecuali disuruh berhenti).
 * Server hanya mendengar di 127.0.0.1:3000.
 */
public class ServerService extends Service {
    public static final String ACTION_START = "id.rfl.assistant.START";
    public static final String ACTION_STOP = "id.rfl.assistant.STOP";
    public static final String ACTION_RESTART = "id.rfl.assistant.RESTART";

    private Process proc;
    private volatile boolean stopping;
    private Thread watchdog;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void restart(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class).setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopping = true;
            killProc();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            killProc();
        }
        stopping = false;
        startForeground(1, buildNotification());
        ensureRunning();
        return START_STICKY;
    }

    private synchronized void ensureRunning() {
        if (proc != null) {
            try { proc.exitValue(); proc = null; }
            catch (IllegalThreadStateException e) { return; } // masih hidup
        }
        Layout layout = new Layout(this);
        layout.ensureDirs();
        try {
            File log = new File(layout.dataDir, "server.log");
            ProcessBuilder pb = new ProcessBuilder(
                    layout.nodeBin().getAbsolutePath(),
                    layout.serverJs().getAbsolutePath());
            pb.directory(layout.appDir);
            pb.environment().put("PORT", "3000");
            pb.environment().put("RFL_DATA_DIR", layout.dataDir.getAbsolutePath());
            File dl = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "RFL-ASSISTANT");
            //noinspection ResultOfMethodCallIgnored
            dl.mkdirs();
            pb.environment().put("RFL_DOWNLOAD_DIR", dl.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            proc = pb.start();
        } catch (Exception e) {
            appendLog(layout, "gagal start server: " + e.getMessage());
            return;
        }
        if (watchdog == null || !watchdog.isAlive()) {
            watchdog = new Thread(() -> {
                while (!stopping) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                    if (!stopping) ensureRunning();
                }
            }, "rfl-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }

    private void killProc() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
    }

    private void appendLog(Layout layout, String msg) {
        try (FileOutputStream os = new FileOutputStream(new File(layout.dataDir, "server.log"), true)) {
            os.write((msg + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    "rfl-server", "Server RFL", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "rfl-server")
                : new Notification.Builder(this);
        return b.setContentTitle("RFL-ASSISTANT")
                .setContentText("Server jalan di HP ini (127.0.0.1:3000)")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        killProc();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
