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
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * The phone's message, a reminder at its time and a task when it falls due, over whatever is on the TV (YouTube,
 * a channel, another app). Not while the household's screen is up: it shows them itself, and asks this TV for its
 * settings every 10 seconds (/api/public), which is how this knows. Needs «العرض فوق التطبيقات» (optimize/install.sh
 * grants it; the settings screen opens it where the TV offers it).
 */
final class NoticeOverlay {
    private static final long TICK_MS = 15_000, SHOW_MS = 45_000, MESSAGE_MAX_MS = 5 * 60_000;
    static volatile long screenSeen = 0;   // ControlServer: the screen page asked for its settings

    private final Context c;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> shown = new HashSet<>();
    private View box;
    private String boxKey = "";
    private long boxUntil = 0;

    NoticeOverlay(Context c) { this.c = c.getApplicationContext(); }

    private final Runnable tick = new Runnable() {
        public void run() {
            try { look(); } catch (Exception ignored) { }
            handler.postDelayed(this, TICK_MS);
        }
    };

    void start() { handler.postDelayed(tick, 20_000); }

    void stop() { handler.removeCallbacks(tick); hide(); }

    /** a test from the computer or the phone: a notice, now */
    void test() { handler.post(() -> show("test", "🔔", "تنبيه", "هذا تنبيه فوق التطبيقات (اختبار)", "النظام", System.currentTimeMillis() + 20_000)); }

    private void look() throws Exception {
        long now = System.currentTimeMillis();
        if (box != null && now > boxUntil) hide();
        if (now - screenSeen < 25_000) { hide(); return; }   // the screen is up and shows them itself
        JSONObject st = ControlStore.state(c);

        JSONObject m = st.optJSONObject("message");
        if (m != null && !m.optString("text").isEmpty()) {
            long at = m.optLong("at"), until = Math.min(m.optLong("until", at + MESSAGE_MAX_MS), now + MESSAGE_MAX_MS);
            String key = "m" + at;
            if (until > now && !shown.contains(key)) {
                String[] kind = kind(m.optString("style"));
                show(key, kind[0], kind[1], m.optString("text"), m.optString("sender", "النظام"), until);
                return;
            }
        }

        Calendar cal = Calendar.getInstance();
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), wd = cal.get(Calendar.DAY_OF_WEEK) - 1;
        String today = String.format(java.util.Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        JSONArray rems = st.optJSONArray("reminders");
        for (int i = 0; rems != null && i < rems.length(); i++) {
            JSONObject r = rems.optJSONObject(i);
            if (r == null || today.equals(r.optString("doneOn")) || !hasDay(r.optJSONArray("days"), wd)) continue;
            String[] hm = r.optString("time", "").split(":");
            if (hm.length < 2) continue;
            int at = Integer.parseInt(hm[0].trim()) * 60 + Integer.parseInt(hm[1].trim());
            String key = "r" + r.optString("id") + today;
            if (mins >= at && mins < at + 3 && !shown.contains(key)) {
                show(key, "⏰", "تذكير", r.optString("text"), time12(at), now + SHOW_MS);
                return;
            }
        }
        JSONArray tasks = st.optJSONArray("tasks");
        for (int i = 0; tasks != null && i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null || t.optBoolean("done") || t.optString("due").isEmpty()) continue;
            long due = parse(t.optString("due"));
            String key = "t" + t.optString("id") + due;
            if (due > 0 && now >= due - 60_000 && now < due + 3 * 60_000 && !shown.contains(key)) {
                show(key, "✅", "حان وقت المهمة", t.optString("text"), "", now + SHOW_MS);
                return;
            }
        }
    }

    private void show(String key, String icon, String title, String text, String sub, long until) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
        shown.add(key);
        if (key.equals(boxKey) && box != null) { boxUntil = until; return; }
        hide();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        b.setPadding(dp(22), dp(14), dp(22), dp(16));
        b.setMinimumWidth(dp(420));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xF0081410);
        bg.setStroke(dp(2), 0xFFE8C46F);
        b.setBackground(bg);
        b.addView(text(icon + "  " + title, 17, 0xFFE8C46F, true));
        b.addView(text(text, 26, 0xFFFFF3D6, true));
        if (!sub.isEmpty()) b.addView(text(sub, 15, 0xFFC6BBA4, false));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(28);
        lp.windowAnimations = android.R.style.Animation_Toast;   // fades in and out, no jump
        try { wm.addView(b, lp); } catch (Exception e) { return; }
        box = b; boxKey = key; boxUntil = until;
        handler.postDelayed(() -> { if (box == b && System.currentTimeMillis() >= boxUntil) hide(); }, Math.max(1000, until - System.currentTimeMillis()));
    }

    private void hide() {
        if (box == null) return;
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).removeView(box); } catch (Exception ignored) { }
        box = null; boxKey = "";
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private static String[] kind(String style) {
        switch (style) {
            case "alert": return new String[]{"🔔", "تنبيه"};
            case "welcome": return new String[]{"👋", "ترحيب"};
            case "urgent": return new String[]{"⚠️", "تحذير"};
            case "reminder": return new String[]{"⏰", "تذكير"};
            case "congrats": return new String[]{"🎉", "تهنئة"};
            case "announcement": return new String[]{"📢", "إعلان"};
            case "thanks": return new String[]{"💐", "شكر وتقدير"};
            default: return new String[]{"✉️", "رسالة"};
        }
    }

    private static boolean hasDay(JSONArray days, int wd) {
        for (int i = 0; days != null && i < days.length(); i++) if (days.optInt(i, -1) == wd) return true;
        return false;
    }

    /** the phone page writes a task's time as 2026-10-25T14:30 (this TV's time) or as ISO with Z */
    private static long parse(String s) {
        try {
            if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:\\d\\d$")) {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(s.substring(0, 19)).getTime();
            }
            return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).parse(s.substring(0, 16)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String time12(int mins) {
        int h = mins / 60, m = mins % 60, h12 = h % 12 == 0 ? 12 : h % 12;
        String x = h12 + ":" + (m < 10 ? "0" : "") + m;
        StringBuilder out = new StringBuilder();
        for (char ch : x.toCharArray()) out.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return "⁦" + out + "⁩ " + (h < 12 ? "ص" : "م");
    }

    private int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
