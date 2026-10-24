package sa.yourtv.v2.screensaver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * Full-screen preview of the screensaver. Like the screensaver: OK or the phone page turns the Quran window
 * on and off, the volume keys change the volume, any other key closes it.
 */
public class MainActivity extends Activity {
    private WebView web;
    private QuranWindow quran;
    private FrameLayout root;

    private final SharedPreferences.OnSharedPreferenceChangeListener follow = (p, key) -> {
        if (ControlStore.KEY_QURAN.equals(key) && quran != null && quran.isOn() != ControlStore.quran(this)) quran.toggle();
        if ("display".equals(key) && quran != null && quran.isOn()) { quran.toggle(); quran.toggle(); }
        if ("adhanPlay".equals(key) && quran != null) quran.adhan(p.getString(key, "{}"));
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ControlService.start(this);
        root = new FrameLayout(this);
        web = ScreenView.create(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        quran = new QuranWindow(root);
        setContentView(root);
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        ControlStore.prefs(this).registerOnSharedPreferenceChangeListener(follow);
        if (ControlStore.quran(this)) root.postDelayed(() -> { if (quran != null && !quran.isOn()) quran.toggle(); }, 2500);
        morning(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        morning(i);
    }

    /** opened by the morning alarm: the screen on, and the adhkar in the window (up to 30 minutes) */
    @SuppressWarnings("deprecation")
    private void morning(android.content.Intent i) {
        String video = i == null ? null : i.getStringExtra("alarmVideo");
        if (video == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        root.postDelayed(() -> { if (quran != null) quran.playVideo(video, 0, 1800, null); }, 3000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    quran.toggle();
                    ControlStore.setQuran(this, quran.isOn());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:     // the screens (family, each person): next and back
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getAction() == KeyEvent.ACTION_UP && web != null)
                    web.evaluateJavascript("window.navigateCard && navigateCard(" + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : -1) + ")", null);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (e.getAction() == KeyEvent.ACTION_UP && web != null)
                    web.evaluateJavascript("window.switchScreen && switchScreen(" + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) + ")", null);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(e);
            default:
                if (e.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
        }
    }

    @Override
    protected void onDestroy() {
        ControlStore.prefs(this).unregisterOnSharedPreferenceChangeListener(follow);
        if (quran != null) { quran.hide(); quran = null; }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
