package sa.yourtv.v2.screensaver;

import android.content.SharedPreferences;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * The system screensaver: starts on the TV's own idle timer. OK on the remote, or the phone page, turns the
 * Quran channel's live stream on and off; the volume keys change the volume; any other key wakes the TV.
 */
public class ScreenDream extends DreamService {
    private WebView web;
    private QuranWindow quran;
    private FrameLayout root;

    // the phone page switched the Quran window: follow it
    private final SharedPreferences.OnSharedPreferenceChangeListener follow = (p, key) -> {
        if (ControlStore.KEY_QURAN.equals(key) && quran != null) quran.setAt(ControlStore.quran(this), p.getLong("quranAt", 0));   // with the other TVs
        if ("display".equals(key) && quran != null && quran.isOn()) { quran.toggle(); quran.toggle(); }
        if ("adhanPlay".equals(key) && quran != null) quran.adhan(p.getString(key, "{}"));   // new size or place
    };
    // city, teams or cards changed from the phone: load the page again with them
    private final Runnable reloadNow = () -> { if (web != null) web.loadUrl(new Prefs(this).dashboardUrl(this)); };
    private final SharedPreferences.OnSharedPreferenceChangeListener reload = (p, key) -> {
        if (web != null && root != null) {
            root.removeCallbacks(reloadNow);
            root.postDelayed(reloadNow, 1500);   // several settings arrive together
        }
    };

    @Override
    public void onAttachedToWindow() {
        MainActivity.up = true;
        super.onAttachedToWindow();
        setInteractive(true);   // keys reach dispatchKeyEvent below instead of waking the TV at once
        setFullscreen(true);
        setScreenBright(true);
        ControlService.start(this);
        root = new FrameLayout(this);
        web = ScreenView.create(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        quran = new QuranWindow(root);
        setContentView(root);
        ControlStore.prefs(this).registerOnSharedPreferenceChangeListener(follow);
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(reload);
        // turned on from the phone while the TV was elsewhere: open it once the screen has its size
        if (ControlStore.quran(this)) root.postDelayed(() -> { if (quran != null && !quran.isOn()) quran.toggle(); }, 2500);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    QuranWindow.everywhere(this, !quran.isOn());   // on every TV at the same moment; the phone page shows it
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
                if (e.getAction() == KeyEvent.ACTION_UP) wakeUp();
                return true;
        }
    }

    @Override
    public void onDetachedFromWindow() {
        MainActivity.up = false;
        ControlStore.prefs(this).unregisterOnSharedPreferenceChangeListener(follow);
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(reload);
        if (root != null) root.removeCallbacks(reloadNow);
        if (quran != null) { quran.hide(); quran = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDetachedFromWindow();
    }
}
