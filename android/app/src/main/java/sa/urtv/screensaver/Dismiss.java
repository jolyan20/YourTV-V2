package sa.yourtv.v2.screensaver;

import android.view.KeyEvent;
import android.view.View;

/**
 * A notice over other apps that the remote can hide: while it shows it takes the remote's keys, and Back, OK or any
 * other key hides it at once (the volume keys still reach the TV). Its window is focusable for that, so it is added
 * without FLAG_NOT_FOCUSABLE and asks for the focus once added.
 */
final class Dismiss {
    private Dismiss() {}

    static void byRemote(View box, Runnable hide) {
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setOnKeyListener((v, key, e) -> {
            if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) hide.run();
            return true;
        });
    }
}
