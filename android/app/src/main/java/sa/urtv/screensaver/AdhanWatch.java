package sa.yourtv.v2.screensaver;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The adhan while an app plays (Shahid, YouTube, a channel): at each prayer's time, if the TV is playing an app's
 * sound and the household's screen is not up (it plays the adhan itself), a notice over the app and that app's
 * sound lowered; it comes back after five minutes, or at once when the remote hides the notice, unless someone
 * changed the volume meanwhile. Today's times are the official Umm al-Qura calendar's for this city (as on the
 * screen), fetched once a day. Off with the phone page's «تنبيه الأذان فوق التطبيقات» (display.adhanOverApps).
 */
final class AdhanWatch {
    private static final long TICK_MS = 20_000, LOWERED_MS = 5 * 60_000;
    private static final String[][] PRAYERS = {{"fajr", "الفجر"}, {"dhuhr", "الظهر"}, {"asr", "العصر"}, {"maghrib", "المغرب"}, {"isha", "العشاء"}};
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Riyadh");

    private final Context c;
    private final NoticeOverlay notices;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String done = "";
    private int before = -1, lowered = -1;

    AdhanWatch(Context c, NoticeOverlay notices) { this.c = c.getApplicationContext(); this.notices = notices; }

    private final Runnable tick = new Runnable() {
        public void run() {
            new Thread(AdhanWatch.this::look).start();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable restore = this::restore;

    void start() { handler.postDelayed(tick, 30_000); }

    void stop() { handler.removeCallbacks(tick); handler.post(restore); }

    private void look() {
        try {
            if (System.currentTimeMillis() - NoticeOverlay.screenSeen < 25_000) return;   // the screen is up: its own adhan
            if (!ControlStore.display(c).optBoolean("adhanOverApps", true)) return;
            Calendar k = Calendar.getInstance(TZ);
            String today = String.format(Locale.US, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
            JSONObject t = times(today, k);
            if (t == null) return;
            int mins = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
            for (String[] p : PRAYERS) {
                String[] hm = t.optString(p[0]).split(":");
                if (hm.length < 2) continue;
                int at = Integer.parseInt(hm[0].trim()) * 60 + Integer.parseInt(hm[1].trim());
                String key = today + p[0];
                if (mins < at || mins > at + 1 || done.contains(key)) continue;
                done = key;
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (am == null || !am.isMusicActive()) return;   // nothing playing: nothing to lower, the TV is quiet
                handler.post(() -> lower(am, p[1]));
                return;
            }
        } catch (Exception ignored) { }
    }

    private void lower(AudioManager am, String prayer) {
        try {
            int st = AudioManager.STREAM_MUSIC, max = am.getStreamMaxVolume(st), now = am.getStreamVolume(st);
            int to = Math.min(now, Math.max(1, Math.round(max * 0.15f)));
            if (to < now) { before = now; lowered = to; am.setStreamVolume(st, to, 0); }
        } catch (Exception ignored) { }   // a TV with fixed volume: the notice alone
        handler.removeCallbacks(restore);
        handler.postDelayed(restore, LOWERED_MS);
        notices.adhan(prayer, System.currentTimeMillis() + LOWERED_MS, () -> handler.post(restore));
    }

    private void restore() {
        handler.removeCallbacks(restore);
        if (before < 0) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int st = AudioManager.STREAM_MUSIC;
            if (am != null && am.getStreamVolume(st) == lowered) am.setStreamVolume(st, before, 0);   // unless someone changed it
        } catch (Exception ignored) { }
        before = lowered = -1;
    }

    /** today's times for this city, kept for the day: {"fajr":"04:32",…} */
    private JSONObject times(String today, Calendar k) {
        String[] city = new Prefs(c).city();
        String key = today + "|" + city[1] + "," + city[2];
        try {
            JSONObject kept = new JSONObject(ControlStore.prefs(c).getString("adhanTimes", "{}"));
            if (key.equals(kept.optString("key"))) return kept.optJSONObject("t");
        } catch (Exception ignored) { }
        try {
            int zone = TZ.getOffset(System.currentTimeMillis()) / 3_600_000;
            JSONObject j = new JSONObject(get("https://umqserv.kacst.gov.sa/api/v1/Prayer/GetPrayers?lang=ar&format=24&yg=" + k.get(Calendar.YEAR)
                + "&mg=" + (k.get(Calendar.MONTH) + 1) + "&dg=" + k.get(Calendar.DAY_OF_MONTH) + "&lat=" + city[1] + "&lon=" + city[2] + "&zone=" + zone));
            JSONObject t = j.getJSONObject("prayerTimes");
            ControlStore.prefs(c).edit().putString("adhanTimes", new JSONObject().put("key", key).put("t", t).toString()).apply();
            return t;
        } catch (Exception e) {
            return null;   // offline: the next tick tries again
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 200_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
