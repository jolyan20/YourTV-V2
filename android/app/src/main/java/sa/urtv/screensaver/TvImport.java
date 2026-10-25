package sa.yourtv.v2.screensaver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * «استيراد من تلفزيون ثاني»: a TV just installed (its data gone with the old app) takes everything from another TV
 * of the house, old version or new, with that TV's PIN: the tasks, reminders, occasions, screens and stars, the
 * series, sleep and bedtime, the wird, the screen options, the city and the teams. The phone's message, game time
 * and uploaded files are not taken (the files: upload them again). When the other TV runs this version too, the
 * two are paired for ControlSync from then on, each with the other's address and one shared key.
 */
final class TvImport {
    private static final String[] TAKE = {"tasks", "reminders", "family", "sleep", "display", "series", "bedtime", "wird", "screens", "alarm", "tmdb"};

    private TvImport() {}

    /** the other TVs on this home network: whoever answers on the control port the way this app does */
    static JSONArray scan(Context c) throws Exception {
        String me = ControlStore.address();
        JSONArray out = new JSONArray();
        if (me == null) return out;
        String base = me.substring(0, me.lastIndexOf('.') + 1);
        final List<String> found = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String host = base + i;
            if (host.equals(me)) continue;
            pool.execute(() -> {
                try {
                    String[] r = http(host, "GET", "/api/public", null, null, 600, 2500);
                    if ("403".equals(r[0]) && r[1].contains("local only")) synchronized (found) { found.add(host); }
                } catch (Exception ignored) { }
            });
        }
        pool.shutdown();
        pool.awaitTermination(25, TimeUnit.SECONDS);
        for (String h : found) out.put(new JSONObject().put("host", h));
        return out;
    }

    /** take the other TV's data; pair the two when it can. Returns what was taken, in Arabic, for the phone page. */
    static JSONObject from(Context c, String host, String pin, String myName, String otherName) throws Exception {
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new Exception("العنوان غير صحيح");
        String[] login = http(host, "POST", "/api/login", null, new JSONObject().put("pin", pin).toString(), 4000, 10000);
        if ("401".equals(login[0])) throw new Exception("الرمز السري غير صحيح");
        if ("429".equals(login[0])) throw new Exception("محاولات كثيرة، انتظر قليلاً ثم أعد المحاولة");
        if (!"200".equals(login[0])) throw new Exception("التلفزيون لم يرد");
        String key = new JSONObject(login[1]).getString("key");

        String[] st = http(host, "GET", "/api/state", key, null, 4000, 15000);
        if (!"200".equals(st[0])) throw new Exception("تعذر قراءة البيانات");
        JSONObject state = new JSONObject(st[1]);
        long now = System.currentTimeMillis();
        JSONArray took = new JSONArray();
        for (String s : TAKE) {
            Object v = state.opt(s);
            if (v instanceof JSONObject || v instanceof JSONArray) { ControlStore.saveFrom(c, s, v.toString(), now); took.put(s); }
        }
        try {   // the city and the followed teams
            String[] pr = http(host, "GET", "/api/prefs", key, null, 4000, 10000);
            if ("200".equals(pr[0])) {
                JSONObject p = new JSONObject(pr[1]), v = new JSONObject();
                if (p.optJSONArray("city") != null) v.put("city", p.getJSONArray("city").getString(0));
                if (p.optJSONArray("teams") != null) v.put("teams", p.getJSONArray("teams"));
                if (v.length() > 0 && ControlPrefs.apply(c, v, false) == null) took.put("prefs");
            }
        } catch (Exception ignored) { }   // an older TV without its city or teams: the rest is taken

        boolean paired = false;
        try {   // both on this version: one shared key, each the other's peer, each with its name
            byte[] r = new byte[24];
            new SecureRandom().nextBytes(r);
            StringBuilder k = new StringBuilder();
            for (byte b : r) k.append(String.format("%02x", b));
            String meId = idOf(myName, "tv-a"), otherId = idOf(otherName, "tv-b");
            if (meId.equals(otherId)) otherId = otherId + "-2";
            JSONObject pair = new JSONObject().put("key", k.toString()).put("peer", ControlStore.address())
                .put("id", otherId).put("name", otherName.isEmpty() ? otherId : otherName);
            String[] pr = http(host, "POST", "/api/pair", key, pair.toString(), 4000, 10000);
            if ("200".equals(pr[0])) {
                ControlStore.prefs(c).edit().putString("syncKey", k.toString()).putString("syncPeers", host)
                    .putString("tvId", meId).putString("tvName", myName.isEmpty() ? meId : myName).apply();
                paired = true;
            }
        } catch (Exception ignored) { }   // an older TV: the data is taken, the pairing waits for its update
        try { http(host, "POST", "/api/logout", key, "{}", 4000, 5000); } catch (Exception ignored) { }
        return new JSONObject().put("took", took).put("paired", paired);
    }

    /** the other side of from(): this TV keeps the shared key and the importing TV's address, and its own name */
    static void pair(Context c, JSONObject o) throws Exception {
        String k = o.optString("key"), peer = o.optString("peer"), id = o.optString("id"), name = o.optString("name");
        if (!k.matches("[A-Za-z0-9]{32,128}") || !peer.matches("[0-9.]{7,15}") || !id.matches("[a-z0-9-]{1,20}") || name.length() > 30)
            throw new Exception("pair");
        ControlStore.prefs(c).edit().putString("syncKey", k).putString("syncPeers", peer).putString("tvId", id).putString("tvName", name.isEmpty() ? id : name).apply();
    }

    /** a TV's id from its name (TCL -> tcl); Arabic names get the fallback */
    private static String idOf(String name, String fallback) {
        String id = name.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return id.isEmpty() || id.length() > 20 ? fallback : id;
    }

    /** a plain HTTP/1.0 exchange over a socket (the app allows no other clear-text connections): {status, body} */
    static String[] http(String host, String method, String path, String key, String body, int connectMs, int readMs) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, ControlStore.PORT), connectMs);
            s.setSoTimeout(readMs);
            byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write((method + " " + path + " HTTP/1.0\r\nHost: " + host + "\r\n" + (key != null ? "X-Key: " + key + "\r\n" : "")
                + "Content-Type: application/json\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 4_000_000) b.write(buf, 0, n);
            String all = new String(b.toByteArray(), StandardCharsets.UTF_8);
            int head = all.indexOf("\r\n\r\n");
            String status = all.length() > 12 ? all.substring(9, 12) : "000";
            return new String[]{status, head < 0 ? "" : all.substring(head + 4)};
        }
    }
}
