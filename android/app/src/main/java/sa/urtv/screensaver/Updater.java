package sa.yourtv.v2.screensaver;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Pulls the latest page from GitHub so screen changes reach the TV without a new APK. */
final class Updater {
    static final String URL_PAGE =
        PhotoCache.REPO + "dist/tv.html";
    static final long INTERVAL_MS = 6L * 3600 * 1000;

    private Updater() {}

    static File pageFile(Context c) { return new File(c.getFilesDir(), "web/tv.html"); }

    /** Blocking; call off the main thread. Returns an Arabic status line for the settings screen. */
    static synchronized String check(Context c, boolean force) {
        Prefs prefs = new Prefs(c);
        SharedPreferences sp = prefs.raw();
        if (!prefs.autoUpdate() && !force) return "التحديث التلقائي متوقف";
        long now = System.currentTimeMillis();
        if (!force && now - sp.getLong("lastCheck", 0) < INTERVAL_MS) return "تم الفحص مؤخراً";

        File out = pageFile(c);
        new File(out.getParentFile(), "urtv.html").delete();   // the old, heavier page
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(URL_PAGE).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            String etag = sp.getString("etag", null);
            if (etag != null && out.isFile()) con.setRequestProperty("If-None-Match", etag);
            int code = con.getResponseCode();
            sp.edit().putLong("lastCheck", now).apply();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return "الصفحة محدثة";
            if (code != HttpURLConnection.HTTP_OK) return "تعذر التحديث (" + code + ")";

            byte[] body = readAll(con.getInputStream());
            String head = new String(body, 0, Math.min(body.length, 4096), "UTF-8");
            String tail = new String(body, Math.max(0, body.length - 4096), Math.min(body.length, 4096), "UTF-8");
            // never swap in a truncated or wrong file
            if (body.length < 30_000 || !head.contains("<html") || !tail.contains("</html>")) return "ملف التحديث غير صالح";

            if (!head.contains("yourtv-generation")) return "بانتظار نشر تحديث YourTV V2";
            File dir = out.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) return "تعذر الحفظ";
            File tmp = new File(dir, "page.tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try { fo.write(body); } finally { fo.close(); }
            if (!tmp.renameTo(out)) return "تعذر الحفظ";
            sp.edit().putString("etag", con.getHeaderField("ETag")).apply();
            return "تم التحديث";
        } catch (Exception e) {
            return "لا يوجد اتصال";
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }
}
