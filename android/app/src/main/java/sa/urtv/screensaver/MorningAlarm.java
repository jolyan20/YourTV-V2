package sa.yourtv.v2.screensaver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * The morning alarm, set on the phone page ("alarm": on, time, days 0=Sunday.., video, volume 10..100): at that
 * time the TV wakes, the screensaver's preview opens and the morning adhkar play in its window at the chosen
 * volume. Rescheduled after each ring and whenever the phone page changes it.
 */
public class MorningAlarm extends BroadcastReceiver {
    static final String DEFAULT_VIDEO = "OIWahnI8rSI";   // أذكار الصباح, Mishary Alafasy's own channel

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, MorningAlarm.class),
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        am.cancel(pi);
        long at = next(c);
        if (at <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {   // exact alarms not allowed on this TV: a few minutes' leeway
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static JSONObject settings(Context c) {
        try { return new JSONObject(ControlStore.prefs(c).getString("alarm", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** the next ring, or 0 when off */
    private static long next(Context c) {
        JSONObject a = settings(c);
        if (!a.optBoolean("on")) return 0;
        String[] hm = a.optString("time", "06:30").split(":");
        JSONArray days = a.optJSONArray("days");
        try {
            int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
            Calendar cal = Calendar.getInstance();
            for (int i = 0; i < 8; i++) {
                Calendar d = (Calendar) cal.clone();
                d.add(Calendar.DAY_OF_YEAR, i);
                d.set(Calendar.HOUR_OF_DAY, h); d.set(Calendar.MINUTE, m); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
                if (d.getTimeInMillis() <= cal.getTimeInMillis()) continue;
                int wd = d.get(Calendar.DAY_OF_WEEK) - 1;   // 0 = Sunday, like the phone page
                boolean ok = days == null || days.length() == 0;
                for (int k = 0; days != null && k < days.length(); k++) if (days.optInt(k) == wd) ok = true;
                if (ok) return d.getTimeInMillis();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        JSONObject a = settings(c);
        if (a.optBoolean("on")) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "yourtv:morning");
            wl.acquire(15_000);
            AudioManager au = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int vol = Math.max(5, Math.min(100, a.optInt("volume", 30)));
            try { au.setStreamVolume(AudioManager.STREAM_MUSIC, au.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * vol / 100, 0); } catch (Exception ignored) { }
            String video = a.optString("video", DEFAULT_VIDEO);
            if (!video.matches("[A-Za-z0-9_-]{11}")) video = DEFAULT_VIDEO;
            c.startActivity(new Intent(c, MainActivity.class).putExtra("alarmVideo", video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        schedule(c);
    }
}
