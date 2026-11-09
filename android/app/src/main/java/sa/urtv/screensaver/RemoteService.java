package sa.yourtv.v2.screensaver;

import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * «تلفزيوني: التحكم من الجوال»: the phone page's remote, in any app on the TV. Android lets an app press keys in
 * other apps only through an accessibility service the person turns on once (TV settings → Accessibility). The arrows
 * and OK are Android's own d-pad actions from Android 13; before that the focus is moved and the focused item clicked.
 * Back and Home work everywhere. Typing goes into the field in front; YouTube on the TV has none Android can reach, so
 * its own search opens with the words. The volume and play/pause need no service. Every action comes from a phone
 * signed in to this TV (ControlServer); the service reads nothing on its own and keeps nothing.
 */
public class RemoteService extends AccessibilityService {
    static volatile RemoteService running;
    static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    @Override protected void onServiceConnected() { running = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }   // nothing is read
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent i) { running = null; return super.onUnbind(i); }
    @Override public void onDestroy() { running = null; super.onDestroy(); }

    static boolean on() { return running != null; }

    /** a key from the phone: "" when done, else why not (in Arabic, for the page) */
    static String key(Context c, String k) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        switch (k) {   // the sound and the player: no service needed
            case "volup": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return "";
            case "voldown": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return "";
            case "mute": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return "";
            case "play": media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return "";
            case "rew": media(am, KeyEvent.KEYCODE_MEDIA_REWIND); return "";
            case "ffwd": media(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); return "";
            default: break;
        }
        RemoteService s = running;
        if (s == null) return "off";
        switch (k) {
            case "back": return s.performGlobalAction(GLOBAL_ACTION_BACK) ? "" : "fail";
            case "home": return s.performGlobalAction(GLOBAL_ACTION_HOME) ? "" : "fail";
            case "up": return s.dpad(16, View.FOCUS_UP);       // GLOBAL_ACTION_DPAD_UP (Android 13)
            case "down": return s.dpad(17, View.FOCUS_DOWN);
            case "left": return s.dpad(18, View.FOCUS_LEFT);
            case "right": return s.dpad(19, View.FOCUS_RIGHT);
            case "ok": return s.dpad(20, 0);                   // GLOBAL_ACTION_DPAD_CENTER
            default: return "key";
        }
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** Android 13+: its own d-pad action; before: the focus moves to the next item that way, or the focused one is clicked */
    private String dpad(int action, int direction) {
        if (Build.VERSION.SDK_INT >= 33 && performGlobalAction(action)) return "";
        AccessibilityNodeInfo f = focused();
        if (f == null) return "focus";
        if (direction == 0) {
            for (AccessibilityNodeInfo n = f; n != null; n = n.getParent())
                if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "";
            return "fail";
        }
        AccessibilityNodeInfo next = f.focusSearch(direction);
        return next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ? "" : "edge";
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null ? f : root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    }

    /** the words into the field in front (replacing what is in it); YouTube: its search with them */
    static String type(Context c, String text) {
        RemoteService s = running;
        if (s == null) return "off";
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        String pkg = root != null && root.getPackageName() != null ? root.getPackageName().toString() : "";
        AccessibilityNodeInfo field = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) field = root == null ? null : editable(root, 0);
        if (field != null) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return "";
        }
        if (YOUTUBE_TV.equals(pkg) || pkg.contains("youtube")) return youtube(c, text);
        return "field";
    }

    private static AccessibilityNodeInfo editable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo e = editable(n.getChild(i), depth + 1);
            if (e != null) return e;
        }
        return null;
    }

    /** YouTube on the TV with its search open on the words (no service needed) */
    static String youtube(Context c, String text) {
        Intent[] tries = {
            new Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE_TV).putExtra(SearchManager.QUERY, text).putExtra("query", text),
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(text))).setPackage(YOUTUBE_TV),
        };
        for (Intent i : tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); return ""; }
            catch (Exception ignored) { }
        }
        return "youtube";
    }

    /** the TV's apps, as its own apps row lists them */
    static JSONArray apps(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        Collections.sort(found, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo r : found) {
            String p = r.activityInfo.packageName;
            if (seen.contains(p) || p.equals(c.getPackageName())) continue;
            seen.add(p);
            out.put(new JSONObject().put("pkg", p).put("name", String.valueOf(r.loadLabel(pm))));
        }
        return out;
    }

    static String open(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return "app";
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return ""; }
        catch (Exception e) { return "app"; }
    }

    /** the TV's accessibility settings, to turn the service on with the remote */
    static void openSettings(Context c) {
        try { c.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            try { c.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }
}
