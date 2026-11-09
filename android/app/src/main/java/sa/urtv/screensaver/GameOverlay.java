package sa.yourtv.v2.screensaver;

import android.content.Context;
import android.content.Intent;
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

import org.json.JSONObject;

/**
 * Game time (the PlayStation, or anything on the TV), started from the phone page: a small countdown in the
 * corner over whatever is on screen, HDMI included; amber for the last 5 minutes. At the end nothing covers the
 * screen unless the PlayStation is on it: then a full-screen «انتهى وقت اللعب» and, if chosen on the phone, the TV
 * goes back to its home screen; and so again whenever the PlayStation is chosen later that day, until a new game time.
 * Drawing over other apps needs the "display over other apps" permission; without it only the screensaver
 * page shows the countdown.
 * The lock: with it on, the PlayStation's input (the TV says which input is on screen) is closed outside game
 * time: a «يحتاج موافقة» notice, back to the home screen, and the request shows on the phone page.
 * Rules from the phone page, on top of that: allowed hours per weekday (open without asking inside them), and a
 * daily limit counted while the PlayStation is on screen. Either one closes the PlayStation outside them.
 */
final class GameOverlay {
    private static final long END_SHOW_MS = 20_000;
    private final Context c;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView chip;
    private View full;
    private long until, fullUntil;
    private boolean home, lock;
    private String port = "HDMI 4";
    private boolean blocking;
    private JSONObject schedule;   // {on, days: [ {from:"16:00", to:"19:00"} | null ] × 7, Sunday first}
    private int dailyMax;          // minutes a day, 0 = no limit
    private long lastWin;

    GameOverlay(Context c) {
        this.c = c.getApplicationContext();
        wm = (WindowManager) this.c.getSystemService(Context.WINDOW_SERVICE);
    }

    /** the phone page saved a new game time (or stopped it) */
    void update() {
        try {
            JSONObject g = new JSONObject(ControlStore.prefs(c).getString("game", "{}"));
            until = g.optLong("until");
            home = g.optBoolean("home", true);
            lock = g.optBoolean("lock", false);
            port = g.optString("port", "HDMI 4");
            schedule = g.optJSONObject("schedule");
            dailyMax = Math.max(0, g.optInt("dailyMax", 0));
        } catch (Exception e) {
            until = 0;
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stop() {
        handler.removeCallbacks(tick);
        hideChip();
        hideFull();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            long now = System.currentTimeMillis();
            boolean active = until > now, draw = canDraw();
            String src = source();
            // the TV names the input by its port while the console is off ("HDMI 4") and by the console once it is
            // on ("PlayStation 5"): either one is the PlayStation
            boolean onPort = src != null && (src.trim().equalsIgnoreCase(port.trim()) || src.toLowerCase(java.util.Locale.US).contains("playstation"));
            if (onPort && inApp()) onPort = false;   // the TV still names the console's input while an app is in front
            boolean rules = lock || dailyMax > 0 || (schedule != null && schedule.optBoolean("on"));
            // a game time that ended today keeps the PlayStation closed until the next one (only the PlayStation)
            boolean endedToday = until > 0 && !active && today().equals(day(until));
            long win = windowEnd(now);
            int used = usedToday();
            boolean overLimit = dailyMax > 0 && used >= dailyMax * 60;
            boolean allowed = (active || win > 0) && !overLimit;
            if (onPort && allowed) addUsed(1);
            // the countdown, to whichever comes first: the game time, the end of the allowed hours, the day's limit
            long left = Long.MAX_VALUE;
            if (active) left = until - now;
            if (win > 0) left = Math.min(active ? left : Long.MAX_VALUE, win - now);
            if (dailyMax > 0) left = Math.min(left, (dailyMax * 60L - used) * 1000);
            if ((active || win > 0) && draw && (onPort || src == null) && left > 0 && left != Long.MAX_VALUE) showChip(left); else hideChip();
            // no «العرض فوق التطبيقات» on this TV: short notices instead, every 15 minutes and each of the last 5
            if ((active || win > 0) && !draw && (onPort || src == null) && left > 0 && left != Long.MAX_VALUE) toastAt(left);
            if (!active && until > 0 && now <= until + END_SHOW_MS
                    && ControlStore.prefs(c).getLong("gameDone", 0) != until) {   // once per game time, even after a restart
                // the end of the game time: nothing covers the screen unless the PlayStation is on it (the household
                // found «انتهى وقت اللعب» over YouTube and the channels); on the PlayStation the closing below shows it
                ControlStore.prefs(c).edit().putLong("gameDone", until).apply();
            } else if (lastWin > 0 && win == 0 && onPort && !active) {   // the allowed hours just ended
                if (draw) showFull("🎮 انتهى وقت اللعب", "انتهت ساعات اللعب المسموحة اليوم", now);
                handler.postDelayed(GameOverlay.this::goHome, 3500);   // the message read first
            } else if ((rules || endedToday) && !allowed && onPort && !blocking) {   // the PlayStation chosen while closed
                blocking = true;
                if (now > until + 60_000) ControlStore.prefs(c).edit().putLong("gameAsk", now).apply();   // a request, not the end itself
                if (draw) showFull(overLimit ? "🎮 انتهى حد اللعب اليوم" : endedToday && !rules ? "🎮 انتهى وقت اللعب" : "🎮 البلايستيشن يحتاج موافقة",
                    endedToday && !rules && now <= until + END_SHOW_MS ? "حان وقت الراحة" : "اطلب وقت لعب من لوحة التحكم في الجوال", now);
                final boolean kick = rules || home;   // «back to the home screen» chosen on the phone (or a rule): out of it
                handler.postDelayed(() -> { if (kick) goHome(); blocking = false; }, kick ? 3500 : 60_000);
            }
            lastWin = win;
            // the closing covers the PlayStation only: gone as soon as the TV is on anything else
            if (full != null && (now > fullUntil || (src != null && !onPort))) hideFull();
            if (rules || active || endedToday || full != null || now <= until + END_SHOW_MS) handler.postDelayed(this, 1000 - now % 1000);
        }
    };

    /** the end of today's allowed hours if now is inside them, else 0 */
    private long windowEnd(long now) {
        if (schedule == null || !schedule.optBoolean("on")) return 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        org.json.JSONArray days = schedule.optJSONArray("days");
        JSONObject d = days == null ? null : days.optJSONObject(cal.get(java.util.Calendar.DAY_OF_WEEK) - 1);
        if (d == null) return 0;
        int from = mins(d.optString("from")), to = mins(d.optString("to")), m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        if (from < 0 || to <= from || m < from || m >= to) return 0;
        cal.set(java.util.Calendar.HOUR_OF_DAY, to / 60); cal.set(java.util.Calendar.MINUTE, to % 60);
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static int mins(String hm) {
        try { String[] p = hm.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); } catch (Exception e) { return -1; }
    }

    /** seconds the PlayStation was on screen today (kept for the phone page's report too) */
    private int usedToday() {
        String v = ControlStore.prefs(c).getString("gameUsed", ""), day = today();
        return (v.startsWith(day + ":") ? Integer.parseInt(v.substring(day.length() + 1)) : 0) + unsaved;
    }

    private int unsaved;   // seconds not yet written: the preferences are saved every half minute, not every second

    private void addUsed(int sec) {
        unsaved += sec;
        if (unsaved < 30) return;
        int total = usedToday();
        unsaved = 0;
        ControlStore.prefs(c).edit().putString("gameUsed", today() + ":" + total).apply();
    }

    private static String today() {
        return day(System.currentTimeMillis());
    }

    private static String day(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(ms));
    }

    // apps that are never the PlayStation, whatever input the TV still names (with «التحكم من الجوال» on, Android
    // says which app is in front)
    private static final String[] APPS = {"com.google.android.youtube", "com.google.android.apps.youtube", "com.liskovsoft.",
        "com.netflix.", "net.mbc.", "com.mbc.", "com.amazon.", "com.disney.", "com.spotify.", "com.anghami", "tv.anghami",
        "com.osn", "com.starzplay", "org.chromium.", "com.android.chrome", "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx", "com.android.tv.settings", "sa.yourtv.", "sa.urtv."};

    private boolean inApp() {
        String fg = RemoteService.foreground();
        if (fg == null) return false;
        for (String a : APPS) if (fg.startsWith(a)) return true;
        return false;
    }

    /** the input on screen, as the TV names it ("HDMI 4"), or null where the TV does not say */
    private String source() {
        try {
            String v = Settings.Global.getString(c.getContentResolver(), "multi_view_main_source_name");
            return v == null ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    private WindowManager.LayoutParams params(boolean whole) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                               : WindowManager.LayoutParams.TYPE_PHONE;
        int size = whole ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = dp(28);
        lp.y = dp(22);
        return lp;
    }

    private long lastToast = -1;
    private void toastAt(long left) {
        long min = (left + 59_999) / 60_000;
        boolean due = min <= 5 || min % 15 == 0;
        if (!due || min == lastToast) return;
        lastToast = min;
        try { android.widget.Toast.makeText(c, "🎮 باقي من وقت اللعب " + min + " دقيقة", android.widget.Toast.LENGTH_LONG).show(); } catch (Exception ignored) { }
    }

    private void showChip(long left) {
        boolean warn = left <= 5 * 60_000;
        if (chip == null) {
            chip = new TextView(c);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            try { wm.addView(chip, params(false)); } catch (Exception e) { chip = null; return; }
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(warn ? 0xE6B4561F : 0xCC1B1430);
        bg.setStroke(dp(2), warn ? 0xFFFFB15C : 0xFFC79BF2);
        chip.setBackground(bg);
        chip.setTextColor(0xFFFFFFFF);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, warn ? 26 : 18);
        chip.setText("🎮 " + (warn ? "باقي " : "وقت اللعب ") + clock(left));
    }

    private void showFull(String title, String sub, long now) {
        fullUntil = now + END_SHOW_MS;
        hideFull();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xE6060C14);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(0xFFFFE7AE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        t.setGravity(Gravity.CENTER);
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextColor(0xFFD8CDB6);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(14), 0, 0);
        box.addView(t);
        box.addView(s);
        try { wm.addView(box, params(true)); full = box; } catch (Exception ignored) { }
    }

    private void goHome() {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    private void hideChip() {
        if (chip != null) { try { wm.removeView(chip); } catch (Exception ignored) { } chip = null; }
    }

    private void hideFull() {
        if (full != null) { try { wm.removeView(full); } catch (Exception ignored) { } full = null; }
    }

    /** h:mm:ss in Arabic digits */
    private static String clock(long ms) {
        long t = (ms + 999) / 1000, h = t / 3600, m = t % 3600 / 60, s = t % 60;
        String x = (h > 0 ? h + ":" + two(m) : String.valueOf(m)) + ":" + two(s);
        StringBuilder out = new StringBuilder();
        for (char ch : x.toCharArray()) out.append(ch);   // Western digits, as on the screen
        return "⁦" + out + "⁩";   // isolated, so it reads left to right inside the Arabic text
    }

    private static String two(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private int dp(int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
