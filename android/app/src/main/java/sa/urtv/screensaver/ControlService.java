package sa.yourtv.v2.screensaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Keeps the phone page's server running: started at boot, by the screensaver and by the settings screen.
 * A foreground service, so Android does not stop it while the TV sits on another app.
 */
public class ControlService extends Service {
    private static final String CHANNEL = "control";
    private ControlServer server;
    private GameOverlay game;
    private WeatherWatch weather;
    private NoticeOverlay notices;
    // the TV coming out of standby: the screen opens at once (the phone page can turn this off)
    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { handler.postDelayed(() -> openScreen(c), 1500); }
    };

    /** the household's screen, now, unless the phone page turned «open on power» off */
    static void openScreen(Context c) {
        try {
            JSONObject d = ControlStore.state(c).optJSONObject("display");
            if (d != null && d.has("startOnPower") && !d.optBoolean("startOnPower")) return;
            c.startActivity(new Intent().setClassName("sa.yourtv.v2.home", "sa.yourtv.v2.home.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) { }
    }
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener gameChanged = (p, key) -> {
        if ("game".equals(key) && game != null) game.update();
        if ("alarm".equals(key)) MorningAlarm.schedule(this);
    };
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Android stops Tailscale now and then and the link from outside home goes dead: ask it to connect again
    private final Runnable tailscale = new Runnable() {
        public void run() {
            if (ControlStore.tailscaleAddress() == null && installed("com.tailscale.ipn")) {
                Intent i = new Intent("com.tailscale.ipn.CONNECT_VPN").setClassName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver");
                try { sendBroadcast(i); } catch (Exception ignored) { }
            }
            handler.postDelayed(this, 60_000);
        }
    };

    // the household's other TVs: take what changed there (ControlSync), every minute
    private final Runnable sync = new Runnable() {
        public void run() {
            new Thread(() -> ControlSync.pull(ControlService.this)).start();
            handler.postDelayed(this, 60_000);
        }
    };

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    static void start(Context c) {
        Intent i = new Intent(c, ControlService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }   // a TV that refuses: the page is simply unavailable until the next try
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "التحكم من الجوال", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("التحكم من الجوال")
            .setContentText(ControlStore.phoneUrl(this) == null ? "" : ControlStore.phoneUrl(this)).build();
        startForeground(1, n);
        ControlStore.pin(this);   // made once, shown in the settings
        server = new ControlServer(this);
        server.start();
        handler.postDelayed(tailscale, 20_000);
        handler.postDelayed(sync, 15_000);
        game = new GameOverlay(this);
        ControlStore.prefs(this).registerOnSharedPreferenceChangeListener(gameChanged);
        game.update();
        MorningAlarm.schedule(this);
        weather = new WeatherWatch(this);
        weather.start();
        notices = new NoticeOverlay(this);
        notices.start();
        registerReceiver(screenOn, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tailscale);
        handler.removeCallbacks(sync);
        ControlStore.prefs(this).unregisterOnSharedPreferenceChangeListener(gameChanged);
        if (game != null) game.stop();
        if (weather != null) weather.stop();
        if (notices != null) notices.stop();
        try { unregisterReceiver(screenOn); } catch (Exception ignored) { }
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
