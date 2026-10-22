package id.rfl.assistant;

import android.content.Context;

import java.io.File;

/**
 * Tata letak berkas di dalam folder data aplikasi.
 *
 *   <files>/bin/    runtime (node, ffmpeg)      — ditukar updater (komponen)
 *   <files>/app/    aplikasi JS (server+UI)     — ditukar updater (komponen app)
 *   <files>/data/   data user + staging update  — TIDAK pernah ditukar
 *
 * Data user sengaja di luar folder aplikasi: update komponen menukar folder
 * app/bin utuh tanpa menyentuh data/.
 */
public final class Layout {
    public final File binDir;
    public final File appDir;
    public final File dataDir;
    public final File stagingDir;
    public final File stateFile;

    public Layout(Context ctx) {
        File files = ctx.getFilesDir();
        binDir = new File(files, "bin");
        appDir = new File(files, "app");
        dataDir = new File(files, "data");
        stagingDir = new File(dataDir, "staging");
        stateFile = new File(dataDir, "state.json");
    }

    public File nodeBin() { return new File(binDir, "node"); }
    public File serverJs() { return new File(appDir, "server.js"); }

    public void ensureDirs() {
        //noinspection ResultOfMethodCallIgnored
        binDir.mkdirs(); appDir.mkdirs(); dataDir.mkdirs(); stagingDir.mkdirs();
    }
}
