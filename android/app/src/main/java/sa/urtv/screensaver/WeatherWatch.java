package sa.yourtv.v2.screensaver;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Dangerous weather, over whatever is on the TV: every 30 minutes the National Center for Meteorology's early
 * warnings for this city (new.ncm.gov.sa, its governorate by name or the nearest within 40 km, as on the screen);
 * an orange or red one shows a quiet notice for 25 seconds in the corner, once per warning. When the Center does
 * not answer, the city's forecast and dust (Open-Meteo, the screen's old rules) stand in. Extreme heat is always
 * the forecast's. No sound; Back or OK on the remote hides it.
 */
final class WeatherWatch {
    private final Context c;
    private final Handler handler = new Handler(Looper.getMainLooper());

    WeatherWatch(Context c) { this.c = c.getApplicationContext(); }

    final Runnable check = new Runnable() {
        public void run() {
            new Thread(WeatherWatch.this::look).start();
            handler.postDelayed(this, 30 * 60_000);
        }
    };

    void start() { handler.postDelayed(check, 90_000); }

    void stop() { handler.removeCallbacks(check); }

    /** a test from the computer: the notice, now */
    void test() { handler.post(() -> show("غبار كثيف (اختبار)", 3, "")); }

    private void look() {
        try {
            String[] city = new Prefs(c).city();
            String q = "latitude=" + city[1] + "&longitude=" + city[2] + "&timezone=Asia%2FRiyadh";
            JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?" + q
                + "&current=weather_code&daily=temperature_2m_max,weather_code&forecast_days=1"));
            int now = w.getJSONObject("current").optInt("weather_code", -1);
            int day = w.getJSONObject("daily").getJSONArray("weather_code").optInt(0, -1);
            double hi = w.getJSONObject("daily").getJSONArray("temperature_2m_max").optDouble(0, 0);
            boolean ncm = ncm(city);   // the Center's warnings shown (or none in force): its word stands
            double pm = 0;
            try {
                JSONObject a = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?" + q + "&current=pm10,dust"))
                    .getJSONObject("current");
                pm = Math.max(a.optDouble("pm10", 0), a.optDouble("dust", 0));
            } catch (Exception ignored) { }
            String what = null;
            int level = 0;
            if (!ncm) { if (pm >= 400) { what = "غبار كثيف"; level = 3; } else if (pm >= 150) { what = "غبار"; level = 2; } }
            int[] codes = ncm ? new int[0] : new int[] {now, day};
            for (int code : codes) {
                if (code >= 95 && level < 3) { what = "عواصف رعدية"; level = 3; }
                else if ((code == 65 || code == 67 || code == 82) && level < 3) { what = "أمطار غزيرة"; level = 3; }
                else if (((code >= 61 && code <= 66) || code == 80 || code == 81) && level < 2) { what = "أمطار"; level = 2; }
                else if ((code == 45 || code == 48) && level < 2) { what = "ضباب"; level = 2; }
            }
            if (hi >= 48 && level < 3) { what = "حرارة شديدة " + Math.round(hi) + "°"; level = 3; }
            else if (hi >= 45 && level < 2) { what = "حرارة عالية " + Math.round(hi) + "°"; level = 2; }
            if (what == null || level < 2) return;
            String key = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()) + what + level;
            if (key.equals(ControlStore.prefs(c).getString("wxShown", ""))) return;
            ControlStore.prefs(c).edit().putString("wxShown", key).apply();
            final String text = what;
            final int lvl = level;
            handler.post(() -> show(text, lvl, ""));
        } catch (Exception ignored) { }   // offline: next time
    }

    /** the Center's warnings for this city: shows each orange or red one in force once; false when it did not answer */
    private boolean ncm(String[] city) {
        try {
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            day.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
            long nowMs = System.currentTimeMillis();
            String body = new JSONObject().put("from_date", day.format(new java.util.Date(nowMs)))
                .put("to_date", day.format(new java.util.Date(nowMs + 2 * 86400000L))).put("lang", "ar").toString();
            JSONObject j = new JSONObject(post("https://new.ncm.gov.sa/manned-alerts", body));
            if (!j.optBoolean("success")) return false;
            org.json.JSONArray a = j.optJSONArray("data");
            String name = city[0].split("\\s+[-–]\\s+|،")[0].trim();
            double lat = Double.parseDouble(city[1]), lon = Double.parseDouble(city[2]);
            java.text.SimpleDateFormat t = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            t.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
            String shown = ControlStore.prefs(c).getString("ncmShown", "");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                String color = x.optJSONObject("alert_type") == null ? "" : x.getJSONObject("alert_type").optString("color");
                int level = "red".equals(color) ? 3 : "orange".equals(color) ? 2 : 1;
                long from = t.parse(x.optString("fromDate")).getTime(), to = t.parse(x.optString("toDate")).getTime();
                if (level < 2 || to < nowMs || from > nowMs || !here(x.optJSONArray("governorates"), name, lat, lon)) continue;
                String id = "," + x.optString("id") + ",";
                if (shown.contains(id)) continue;
                shown = (shown.length() > 400 ? shown.substring(shown.length() - 200) : shown) + id;
                ControlStore.prefs(c).edit().putString("ncmShown", shown).apply();
                final String what = x.optJSONObject("alert_status") == null ? "" : x.getJSONObject("alert_status").optString("ar");
                final String sub = "إنذار " + (level >= 3 ? "أحمر" : "برتقالي") + " · المركز الوطني للأرصاد · حتى " + time(to);
                final int lvl = level;
                handler.post(() -> show(what, lvl, sub));
                break;   // one at a time; the next look shows the next
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean here(org.json.JSONArray gs, String name, double lat, double lon) {
        for (int i = 0; gs != null && i < gs.length(); i++) {
            JSONObject g = gs.optJSONObject(i);
            if (g == null) continue;
            if (name.equals(g.optString("name_ar"))) return true;
            double glat = g.optDouble("latitude", 0), glon = g.optDouble("longitude", 0), r = Math.PI / 180;
            double x = (glon - lon) * r * Math.cos((glat + lat) / 2 * r), y = (glat - lat) * r;
            if (glat != 0 && Math.hypot(x, y) * 6371 <= 40) return true;
        }
        return false;
    }

    private static String time(long ms) {
        java.util.Calendar k = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Riyadh"));
        k.setTimeInMillis(ms);
        int h = k.get(java.util.Calendar.HOUR_OF_DAY), m = k.get(java.util.Calendar.MINUTE);
        return (h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m + " " + (h < 12 ? "ص" : "م");
    }

    private void show(String what, int level, String note) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(12), pad, dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xEE141008);
        bg.setStroke(dp(2), level >= 3 ? 0xFFF06A5A : 0xFFF2A24B);
        box.setBackground(bg);
        TextView t = new TextView(c);
        t.setText("⚠️ " + (note.isEmpty() ? "تنبيه طقس: " : "") + what);
        t.setTextColor(0xFFFFF3D6);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        TextView s = new TextView(c);
        s.setText(note.isEmpty() ? "درجة الخطورة: " + (level >= 3 ? "عالية" : "متوسطة") : note);
        s.setTextColor(level >= 3 ? 0xFFFFB3A8 : 0xFFFFD49A);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(t);
        box.addView(s);
        TextView hint = new TextView(c);
        hint.setText("زر الرجوع أو OK يخفيه");
        hint.setTextColor(0x99FFD49A);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(hint);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = dp(28);
        lp.y = dp(22);
        Runnable gone = () -> { try { wm.removeView(box); } catch (Exception ignored) { } };
        Dismiss.byRemote(box, gone);
        try { wm.addView(box, lp); } catch (Exception e) { return; }
        box.requestFocus();
        handler.postDelayed(gone, 25_000);
    }

    private static String post(String url, String json) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        try (java.io.OutputStream o = con.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            con.disconnect();
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
            while ((n = in.read(buf)) > 0 && b.size() < 500_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private int dp(int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
