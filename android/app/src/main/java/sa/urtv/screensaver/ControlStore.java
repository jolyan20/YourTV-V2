package sa.yourtv.v2.screensaver;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Iterator;

/**
 * What the household sets from the phone page, kept on this TV only (never uploaded): a message, tasks,
 * reminders, family occasions, the sleep hours and whether the Quran window is on. One JSON value per
 * section, in the app's own preferences.
 *
 * Access: a 6-digit PIN made on this TV and shown in its settings. Entering it gives the phone a random
 * session key (32 bytes, valid 30 days); the PIN itself is never stored on the phone. Changing the PIN
 * from the TV's settings makes a new one and cancels every session key.
 */
final class ControlStore {
    static final int PORT = 8766;
    static final String[] SECTIONS = {"message", "tasks", "reminders", "family", "sleep", "display", "series", "bedtime", "game", "wird", "screens", "guest", "alarm", "tmdb"};
    static final String KEY_QURAN = "quran";
    private static final String FILE = "control";
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ControlStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized String pin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("pin", null);
        if (pin == null || pin.length() != 6) pin = resetPin(c);   // older builds had 4 digits
        return pin;
    }

    /** a new PIN; every phone has to enter it again */
    static synchronized String resetPin(Context c) {
        String pin = String.format(java.util.Locale.US, "%06d", RANDOM.nextInt(1_000_000));
        prefs(c).edit().putString("pin", pin).putString("sessions", "{}").apply();
        return pin;
    }

    /** constant-time comparison, so the time taken says nothing about how many digits were right */
    static boolean pinMatches(Context c, String given) {
        byte[] a = pin(c).getBytes(), b = (given == null ? "" : given).getBytes();
        return MessageDigest.isEqual(a, b);
    }

    static synchronized String newSession(Context c) {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        StringBuilder sb = new StringBuilder();
        for (byte x : k) sb.append(String.format("%02x", x));
        String key = sb.toString();
        try {
            JSONObject s = sessions(c);
            s.put(hash(key), System.currentTimeMillis() + SESSION_MS);
            prefs(c).edit().putString("sessions", s.toString()).apply();
        } catch (Exception ignored) { }
        return key;
    }

    static synchronized boolean sessionValid(Context c, String key) {
        if (key == null || key.length() != 64) return false;
        try {
            JSONObject s = sessions(c);
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {   // drop the expired ones
                String k = it.next();
                if (s.optLong(k) < now) { it.remove(); changed = true; }
            }
            if (changed) prefs(c).edit().putString("sessions", s.toString()).apply();
            return s.optLong(hash(key)) > now;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void endSession(Context c, String key) {
        try {
            JSONObject s = sessions(c);
            s.remove(hash(key));
            prefs(c).edit().putString("sessions", s.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONObject sessions(Context c) throws Exception {
        return new JSONObject(prefs(c).getString("sessions", "{}"));
    }

    /** only a hash of each session key is kept on the TV */
    private static String hash(String key) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static JSONObject state(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            for (String s : SECTIONS) {
                String v = p.getString(s, null);
                if (v != null) o.put(s, new JSONObject("{\"v\":" + v + "}").get("v"));
            }
            o.put(KEY_QURAN, p.getBoolean(KEY_QURAN, false));
            o.put("gameAsk", p.getLong("gameAsk", 0));   // the PlayStation was locked and someone asked
            o.put("gameUsed", p.getString("gameUsed", ""));
            try { o.put("selftest", new JSONObject(p.getString("selftest", "{}"))); } catch (Exception ignored) { }   // today's minutes on the PlayStation: "date:seconds"
            o.put("files", Uploads.list(c));   // the household's own files: the adhan, birthday photos
            o.put("me", me(c));                // which TV this is: items and settings can be for one TV only
            o.put("tvs", tvs(c));
        } catch (Exception ignored) { }
        return o;
    }

    /** this TV's id and name (set from the computer: SaverReceiver SYNC --es id --es name) */
    static JSONObject me(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String id = p.getString("tvId", "");
        return new JSONObject().put("id", id).put("name", p.getString("tvName", id));
    }

    /** the household's TVs: this one, then the others as they last answered (ControlSync) */
    static org.json.JSONArray tvs(Context c) throws Exception {
        org.json.JSONArray out = new org.json.JSONArray();
        JSONObject mine = me(c);
        if (!mine.optString("id").isEmpty()) out.put(mine);
        JSONObject known = new JSONObject(prefs(c).getString("tvsKnown", "{}"));
        for (Iterator<String> it = known.keys(); it.hasNext(); ) {
            String id = it.next();
            if (!id.equals(mine.optString("id"))) out.put(new JSONObject().put("id", id).put("name", known.getJSONObject(id).optString("name", id)));
        }
        return out;
    }

    /** remember another TV of the household by what it says it is */
    static synchronized void knowTv(Context c, JSONObject tv) {
        try {
            String id = tv.optString("id");
            if (!id.matches("[a-z0-9-]{1,20}")) return;
            JSONObject known = new JSONObject(prefs(c).getString("tvsKnown", "{}"));
            known.put(id, new JSONObject().put("name", tv.optString("name", id)).put("at", System.currentTimeMillis()));
            prefs(c).edit().putString("tvsKnown", known.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** value is the section's JSON text, already parsed once by the caller */
    static void save(Context c, String section, String json) {
        prefs(c).edit().putString(section, json).putLong("at:" + section, System.currentTimeMillis()).apply();
    }

    /** a newer copy from another TV of the household (ControlSync), with the time it was changed there */
    static void saveFrom(Context c, String section, String json, long at) {
        prefs(c).edit().putString(section, json).putLong("at:" + section, at).apply();
    }

    static void setQuran(Context c, boolean on) {
        setQuran(c, on, ClockSync.now());
    }

    /** on or off from a household moment (ClockSync): every TV's window follows at that same moment */
    static void setQuran(Context c, boolean on, long at) {
        prefs(c).edit().putLong("quranAt", at).putBoolean(KEY_QURAN, on).apply();
    }

    static boolean quran(Context c) {
        return prefs(c).getBoolean(KEY_QURAN, false);
    }

    /** the home network and Tailscale's private range (100.64.0.0/10) only */
    static boolean allowed(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    /** this TV's addresses: the home network's, and Tailscale's if it is installed and on */
    static String address() {
        return addressWhere(false);
    }

    static String tailscaleAddress() {
        return addressWhere(true);
    }

    private static String addressWhere(boolean tailscale) {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    boolean ts = (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
                    if (tailscale ? ts : a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String url() {
        String ip = address();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    static String tailscaleUrl() {
        String ip = tailscaleAddress();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    /** the one link for the phone, at home and away: Tailscale's (kept while it reconnects), else the home one */
    static String phoneUrl(Context c) {
        String ts = tailscaleUrl();
        if (ts != null) prefs(c).edit().putString("tailscale_url", ts).apply();
        else ts = prefs(c).getString("tailscale_url", null);
        return ts != null ? ts : url();
    }
}
