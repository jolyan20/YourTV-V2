package sa.yourtv.v2.screensaver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Both apps, updated from the TV itself: CI publishes them to the apk-latest release with version.txt (the build
 * number both share); when it is newer than what is installed, the home app and then this one are downloaded and
 * handed to Android's installer, which asks once on the screen («تثبيت») for each. Android only accepts an update
 * signed with the same key as the installed app; a TV whose apps came from another key installs them once by hand.
 */
final class AppUpdater {
    static final String RELEASE = "https://github.com/jolyan20/YourTV-V2/releases/download/apk-latest/";
    static final String HOME = "sa.yourtv.v2.home";
    private static volatile String status = "";

    private AppUpdater() {}

    static String status() { return status; }

    /** Blocking; call off the main thread. Returns an Arabic status line for the settings screen and the phone page. */
    static synchronized String run(Context c, boolean install) {
        try {
            long latest = Long.parseLong(new String(fetch(RELEASE + "version.txt", 64), "UTF-8").trim());
            long mine = versionOf(c, c.getPackageName()), home = versionOf(c, HOME);
            // the home app missing (removed to take the new key): it is installed with the update
            if (latest <= mine && latest <= home) return status = "التطبيق محدث";
            if (!install) return status = "يوجد تحديث للتطبيق";
            if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
                c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return status = "اسمح للتطبيق بتثبيت التحديثات ثم أعد المحاولة";
            }
            // the home app first: installing this app ends this process
            boolean both = latest > home && latest > mine;
            int parts = both ? 2 : 1;
            if (latest > home) install(c, "urtv-home.apk", 1, parts);
            if (latest > mine) install(c, "urtv-screensaver.apk", parts, parts);
            UpdateProgress.hide();
            return status = "اضغط «تثبيت» على الشاشة";
        } catch (Exception e) {
            UpdateProgress.hide();
            return status = "تعذر التحديث، تأكد من الاتصال";
        }
    }

    private static long versionOf(Context c, String pkg) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;   // not on this TV: the home app is then installed with the update
        }
    }

    private static void install(Context c, String apk, int part, int parts) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session session = pi.openSession(id)) {
            HttpURLConnection con = open(RELEASE + apk);
            try (InputStream in = con.getInputStream(); OutputStream out = session.openWrite(apk, 0, -1)) {
                byte[] buf = new byte[65536];
                long size = con.getContentLength(), got = 0;
                int n, shown = -1;
                UpdateProgress.show(c, part, parts, 0);
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int pct = size > 0 ? (int) (got * 100 / size) : 0;
                    if (pct != shown) {   // the screen and the phone page follow it
                        shown = pct;
                        status = "جارٍ تنزيل التحديث" + (parts > 1 ? " (" + UpdateProgress.arabic(part) + " من " + UpdateProgress.arabic(parts) + ")" : "") + " · " + UpdateProgress.arabic(pct) + "٪";
                        UpdateProgress.show(c, part, parts, pct);
                    }
                }
                session.fsync(out);
            } finally {
                con.disconnect();
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Done.class), flags);
            session.commit(done.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setInstanceFollowRedirects(true);   // the release asset answers with a redirect to its storage
        if (con.getResponseCode() != 200) { con.disconnect(); throw new java.io.IOException("HTTP " + con.getResponseCode()); }
        return con;
    }

    private static byte[] fetch(String url, int max) throws Exception {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() <= max) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    /** Android's answer for each install: ask on the screen, or say why it failed */
    public static class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                status = "تم تحديث التطبيق";
            } else if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                status = "توقيع التطبيق المثبت مختلف: يحتاج تثبيتاً يدوياً مرة واحدة";
            } else if (st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                status = "تعذر التثبيت";
            }
        }
    }
}
