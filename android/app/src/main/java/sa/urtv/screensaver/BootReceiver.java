package sa.yourtv.v2.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Some TVs (TCL) leave the app cold after a power cycle and the first dream fails.
 * Any boot broadcast that does arrive brings the process up and re-arms the update job;
 * the persisted job covers TVs that never send one.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        UpdateJob.schedule(c);
        ControlService.start(c);   // the phone page
        String a = String.valueOf(i.getAction());
        if (!a.equals(Intent.ACTION_MY_PACKAGE_REPLACED)) ControlService.openScreen(c);   // the TV just started: the screen first
    }
}
