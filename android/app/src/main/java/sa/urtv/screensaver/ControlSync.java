package sa.yourtv.v2.screensaver;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The household's TVs share one set of data: tasks, reminders, family occasions, the screens, the wird, my series,
 * bedtime, sleep hours, the screen options, and the followed teams. Every minute each TV asks the others
 * (home network or Tailscale, set once from the computer: SaverReceiver SYNC) for what they hold, and keeps
 * whichever copy of each part was changed last. The message on the screen, game time and the city stay with each
 * TV. An item or the screen options can still be for one TV only: it carries that TV's id (ControlStore.me).
 * The TVs prove themselves to each other with a shared random key; the phone's PIN is not involved.
 */
final class ControlSync {
    static final String[] SECTIONS = {"tasks", "reminders", "family", "sleep", "display", "series", "bedtime", "wird", "screens", "tmdb"};

    private ControlSync() {}

    static boolean keyMatches(Context c, String given) {
        String k = ControlStore.prefs(c).getString("syncKey", "");
        return k.length() >= 32 && given != null && MessageDigest.isEqual(k.getBytes(), given.getBytes());
    }

    /** what this TV holds, with when each part last changed */
    static JSONObject state(Context c) throws Exception {
        JSONObject out = new JSONObject(), secs = new JSONObject();
        for (String s : SECTIONS) {
            String v = ControlStore.prefs(c).getString(s, null);
            if (v != null) secs.put(s, new JSONObject().put("v", new JSONObject("{\"v\":" + v + "}").get("v"))
                .put("at", ControlStore.prefs(c).getLong("at:" + s, 0)));
        }
        out.put("sections", secs);
        out.put("me", ControlStore.me(c));
        out.put("files", Uploads.list(c));
        JSONObject pr = ControlPrefs.read(c);
        out.put("prefs", new JSONObject().put("at", ControlStore.prefs(c).getLong("at:prefs", 0))
            .put("v", new JSONObject().put("teams", pr.getJSONArray("teams"))));   // the city stays each TV's own
        return out;
    }

    /** ask the other TVs; keep each part that one of them changed later */
    static void pull(Context c) {
        String key = ControlStore.prefs(c).getString("syncKey", ""), peers = ControlStore.prefs(c).getString("syncPeers", "");
        if (key.length() < 32 || peers.isEmpty()) return;
        for (String peer : peers.split(",")) {
            peer = peer.trim();
            if (!peer.matches("[0-9.]{7,15}")) continue;
            try {
                JSONObject o = new JSONObject(new String(fetch(peer, key, "/api/sync"), StandardCharsets.UTF_8));
                if (o.optJSONObject("me") != null) ControlStore.knowTv(c, o.getJSONObject("me"));
                JSONObject secs = o.optJSONObject("sections");
                for (String s : SECTIONS) {
                    JSONObject x = secs == null ? null : secs.optJSONObject(s);
                    if (x == null || !x.has("v")) continue;
                    long at = x.optLong("at");
                    if (at > ControlStore.prefs(c).getLong("at:" + s, 0)) ControlStore.saveFrom(c, s, x.get("v").toString(), at);
                }
                JSONObject files = o.optJSONObject("files"), mine = Uploads.list(c);
                if (files != null) for (java.util.Iterator<String> it = files.keys(); it.hasNext(); ) {
                    String name = it.next();
                    if (name.startsWith("adhan-video-")) continue;
                    JSONObject f = files.optJSONObject(name), m = mine.optJSONObject(name);
                    if (f == null || !Uploads.okName(name) || f.optLong("at") <= (m == null ? 0 : m.optLong("at"))) continue;
                    if (f.optLong("size") < 0) Uploads.delete(c, name, f.optLong("at"));
                    else Uploads.save(c, name, fetch(peer, key, "/api/syncfile?name=" + name), f.optLong("at"), f.optString("type"));
                }
                JSONObject pr = o.optJSONObject("prefs");
                if (pr != null && pr.optLong("at") > ControlStore.prefs(c).getLong("at:prefs", 0)
                        && ControlPrefs.apply(c, pr.getJSONObject("v"), false) == null)
                    ControlStore.prefs(c).edit().putLong("at:prefs", pr.optLong("at")).apply();
                return;   // one TV that answered is enough
            } catch (Exception ignored) { }   // that TV is off or away: try the next
        }
    }

    /** a plain GET over a socket (the app allows no other clear-text connections), home network or Tailscale only */
    private static byte[] fetch(String host, String key, String path) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, ControlStore.PORT), 4000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nX-Sync: " + key + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < Uploads.MAX + 65536) b.write(buf, 0, n);
            byte[] all = b.toByteArray();
            String head = new String(all, 0, Math.min(all.length, 16), StandardCharsets.ISO_8859_1);
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) throw new Exception("status");
            for (int i = 0; i + 3 < all.length; i++)
                if (all[i] == 13 && all[i + 1] == 10 && all[i + 2] == 13 && all[i + 3] == 10) return java.util.Arrays.copyOfRange(all, i + 4, all.length);
            throw new Exception("head");
        }
    }
}
