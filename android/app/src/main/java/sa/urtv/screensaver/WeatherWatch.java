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
 * Dangerous weather, over whatever is on the TV: every 30 minutes the city's weather and dust (Open-Meteo, the
 * same rules as the screen's alerts); a new medium or high alert shows a quiet notice for 25 seconds in the
 * corner, with its level. No sound. Once per alert and day.
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
    void test() { handler.post(() -> show("غبار كثيف (اختبار)", 3)); }

    private void look() {
        try {
            String[] city = new Prefs(c).city();
            String q = "latitude=" + city[1] + "&longitude=" + city[2] + "&timezone=Asia%2FRiyadh";
            JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?" + q
                + "&current=weather_code&daily=temperature_2m_max,weather_code&forecast_days=1"));
            int now = w.getJSONObject("current").optInt("weather_code", -1);
            int day = w.getJSONObject("daily").getJSONArray("weather_code").optInt(0, -1);
            double hi = w.getJSONObject("daily").getJSONArray("temperature_2m_max").optDouble(0, 0);
            double pm = 0;
            try {
                JSONObject a = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?" + q + "&current=pm10,dust"))
                    .getJSONObject("current");
                pm = Math.max(a.optDouble("pm10", 0), a.optDouble("dust", 0));
            } catch (Exception ignored) { }
            String what = null;
            int level = 0;
            if (pm >= 400) { what = "غبار كثيف"; level = 3; } else if (pm >= 150) { what = "غبار"; level = 2; }
            int[] codes = {now, day};
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
            handler.post(() -> show(text, lvl));
        } catch (Exception ignored) { }   // offline: next time
    }

    private void show(String what, int level) {
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
        t.setText("⚠️ تنبيه طقس: " + what);
        t.setTextColor(0xFFFFF3D6);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        TextView s = new TextView(c);
        s.setText("درجة الخطورة: " + (level >= 3 ? "عالية" : "متوسطة"));
        s.setTextColor(level >= 3 ? 0xFFFFB3A8 : 0xFFFFD49A);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(t);
        box.addView(s);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = dp(28);
        lp.y = dp(22);
        try { wm.addView(box, lp); } catch (Exception e) { return; }
        handler.postDelayed(() -> { try { wm.removeView(box); } catch (Exception ignored) { } }, 25_000);
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
