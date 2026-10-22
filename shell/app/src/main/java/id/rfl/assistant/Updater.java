package id.rfl.assistant;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Updater komponen dari GitHub.
 *
 * Manifes (manifest.json di akar repo) memuat per komponen — app, node,
 * ffmpeg — versi + url zip + sha256. Komponen yang lebih baru dari yang
 * terpasang: unduh ke data/staging -> verifikasi SHA-256 -> bongkar ->
 * tukar folder (cadangan 1 generasi) -> server perlu restart.
 * Versi terpasang disimpan di data/state.json. Cangkang tidak pernah
 * mengupdate dirinya; manifes boleh memuat minShell sebagai pagar.
 */
public final class Updater {
    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/KazumiAS011/RFL-ASSISTANT/main/manifest.json";
    public static final String SHELL_VERSION = "1.0.9";

    public interface Listener {
        void onStatus(String msg);
        /** Kemajuan unduhan satu komponen, dalam byte. total <= 0 = tak diketahui. */
        default void onDownload(String component, long downloaded, long total) { }
    }

    private final Layout layout;
    private final Listener listener;

    public Updater(Layout layout, Listener listener) {
        this.layout = layout;
        this.listener = listener;
    }

    /** @return true bila ada komponen yang ditukar (server harus restart). */
    public boolean checkAndUpdate() {
        try {
            JSONObject manifest = new JSONObject(readUrl(MANIFEST_URL));
            String minShell = manifest.optString("minShell", "0");
            if (compare(minShell, SHELL_VERSION) > 0) {
                status("Versi cangkang terlalu tua untuk update ini — perlu APK baru.");
                return false;
            }
            JSONObject state = readState();
            JSONObject comps = manifest.getJSONObject("components");
            boolean changed = false;
            Iterator<String> keys = comps.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject comp = comps.getJSONObject(name);
                String want = comp.getString("version");
                String have = state.optString(name, "0");
                if (compare(want, have) <= 0) continue;
                status("Memperbarui " + name + " " + have + " -> " + want + " …");
                if (installComponent(name, comp)) {
                    state.put(name, want);
                    changed = true;
                    status(name + " kini " + want + ".");
                } else {
                    status("Gagal memperbarui " + name + " — versi lama tetap dipakai.");
                }
            }
            if (changed) writeState(state);
            return changed;
        } catch (Exception e) {
            status("Cek update gagal: " + e.getMessage());
            return false;
        }
    }

    private boolean installComponent(String name, JSONObject comp) {
        File target = targetDir(name);
        File stage = new File(layout.stagingDir, name + "-new");
        File backup = new File(layout.stagingDir, name + "-old");
        File zipFile = new File(layout.stagingDir, name + ".zip");
        try {
            download(comp.getString("url"), zipFile, name, comp.optLong("size", 0));
            String sha = sha256(zipFile);
            if (!sha.equalsIgnoreCase(comp.getString("sha256"))) return false;
            deleteRec(stage);
            unzip(zipFile, stage);
            // Isi zip boleh berupa isi folder langsung, atau satu folder akar.
            File inner = singleChildDir(stage);
            if (inner != null) stage = inner;
            deleteRec(backup);
            if (target.exists() && !target.renameTo(backup)) return false;
            if (!stage.renameTo(target)) {
                // Gagal tukar: kembalikan cadangan.
                if (backup.exists()) backup.renameTo(target);
                return false;
            }
            deleteRec(backup);
            if (name.equals("node") || name.equals("ffmpeg")) {
                // komponen runtime = satu folder bin utuh per rilis
                setExec(new File(layout.binDir, "node"));
                setExec(new File(layout.binDir, "ffmpeg"));
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
        }
    }

    private File targetDir(String name) {
        if (name.equals("app")) return layout.appDir;
        return layout.binDir; // node & ffmpeg sama-sama tinggal di bin/
    }

    // ---------- util ----------

    private void status(String s) { if (listener != null) listener.onStatus(s); }

    static int compare(String a, String b) {
        String[] pa = a.split("[.-]"), pb = b.split("[.-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? parseNum(pa[i]) : 0;
            int nb = i < pb.length ? parseNum(pb[i]) : 0;
            if (na != nb) return na < nb ? -1 : 1;
        }
        return 0;
    }

    private static int parseNum(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private JSONObject readState() {
        try {
            if (layout.stateFile.exists()) {
                return new JSONObject(readFile(layout.stateFile));
            }
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private void writeState(JSONObject state) throws Exception {
        try (FileOutputStream os = new FileOutputStream(layout.stateFile)) {
            os.write(state.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while ((n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static String readUrl(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private void download(String url, File dest, String component, long declaredTotal) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream();
             FileOutputStream os = new FileOutputStream(dest)) {
            long total = Math.max(c.getContentLengthLong(), declaredTotal);
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (listener != null) {
                    int pct = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pct != lastPct) {
                        lastPct = pct;
                        listener.onDownload(component, got, total);
                    }
                }
            }
        } finally { c.disconnect(); }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void unzip(File zipFile, File destDir) throws Exception {
        destDir.mkdirs();
        String root = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(destDir, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    private static File singleChildDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null && kids.length == 1 && kids[0].isDirectory()) return kids[0];
        return null;
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void setExec(File f) {
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false);
        }
    }
}
