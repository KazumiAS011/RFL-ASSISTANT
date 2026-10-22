package id.rfl.assistant;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ekstraksi pertama: assets/payload.zip (bin/node, bin/ffmpeg, app/ awal)
 * dibongkar ke folder data aplikasi, biner diberi izin eksekusi.
 * Jalan hanya bila app/ belum ada — update berikutnya urusan Updater.
 */
public final class Payload {
    public static boolean extractIfNeeded(Context ctx, Layout layout) throws IOException {
        layout.ensureDirs();
        if (layout.serverJs().exists() && layout.nodeBin().exists()) return false;

        AssetManager am = ctx.getAssets();
        try (InputStream raw = am.open("payload.zip");
             ZipInputStream zip = new ZipInputStream(raw)) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = safeTarget(layout, e.getName());
                if (out == null) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        // Biner harus bisa dieksekusi (targetSdk 28 membolehkan exec dari sini).
        setExec(layout.nodeBin());
        setExec(new File(layout.binDir, "ffmpeg"));
        return true;
    }

    private static File safeTarget(Layout layout, String name) throws IOException {
        File root = layout.binDir.getParentFile();
        File out = new File(root, name);
        String canon = out.getCanonicalPath();
        if (!canon.startsWith(root.getCanonicalPath() + File.separator)) return null;
        return out;
    }

    private static void setExec(File f) {
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false);
        }
    }
}
