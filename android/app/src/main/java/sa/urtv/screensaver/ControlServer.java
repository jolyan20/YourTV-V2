package sa.yourtv.v2.screensaver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * A tiny web server for the phone page (port 8766), reachable from the home network and from Tailscale only:
 *   GET  /               the page (assets/control.html)
 *   GET  /api/public     the screensaver page's view of the settings, from this TV itself (127.0.0.1)
 *   POST /api/login      {"pin": "123456"} -> {"key": session key}
 *   POST /api/logout     ends this phone's session
 *   GET  /api/state      everything                         } with the session key
 *   POST /api/save       {"section": "...", "value": ...}   } in the X-Key header
 *   POST /api/quran      {"on": true|false}                 }
 *   POST /api/test       {"what": "adhan"|"kickoff"|...|"stop"} } on every TV (passed on: GET /api/peer, sync key)
 * Wrong PINs: 5 from one address locks that address for 15 minutes; 20 in an hour from anywhere locks
 * logins for an hour. No cross-site access (CORS only for /api/public on this TV), strict security headers.
 */
final class ControlServer implements Runnable {
    private static final String CRLF = "\r\n";
    private static final int PER_ADDRESS = 5, GLOBAL = 20;
    private static final long ADDRESS_LOCK_MS = 15 * 60_000, GLOBAL_WINDOW_MS = 3600_000;

    private final Context app;
    private final Semaphore slots = new Semaphore(8);   // at most 8 requests at once
    private final Map<String, int[]> fails = new HashMap<>();          // address -> {count}
    private final Map<String, Long> lockedUntil = new HashMap<>();     // address -> time
    private final java.util.ArrayDeque<Long> recentFails = new java.util.ArrayDeque<>();
    private long globalLockUntil;
    private ServerSocket server;
    private volatile boolean running;

    ControlServer(Context c) { app = c.getApplicationContext(); }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this, "control-server");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    @Override
    public void run() {
        while (running) {
            try {
                server = new ServerSocket(ControlStore.PORT);
                while (running) {
                    final Socket s = server.accept();
                    if (!ControlStore.allowed(s.getInetAddress()) || !slots.tryAcquire()) { s.close(); continue; }
                    new Thread(() -> { try { handle(s); } finally { slots.release(); } }, "control-request").start();
                }
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }   // port busy: try again
            } finally {
                try { if (server != null) server.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** Stream media in bounded chunks; WebView requests byte ranges while probing MP4 files. */
    private static void replyFile(Socket socket, String type, java.io.File file, String range) throws Exception {
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            long total = input.length(), start = 0, end = total - 1;
            boolean partial = !range.isEmpty();
            if (partial) {
                try {
                    if (!range.matches("bytes=[0-9]*-[0-9]*")) throw new Exception();
                    String[] pair = range.substring(6).split("-", -1);
                    if (pair[0].isEmpty()) { long suffix=Long.parseLong(pair[1]); if(suffix<=0)throw new Exception(); start=Math.max(0,total-suffix); }
                    else { start=Long.parseLong(pair[0]); if(!pair[1].isEmpty())end=Math.min(end,Long.parseLong(pair[1])); }
                    if(start>=total || end<start)throw new Exception();
                } catch(Exception invalid) {
                    socket.getOutputStream().write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+total+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); return;
                }
            }
            long count=end-start+1;
            String header="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+type+"\r\nAccept-Ranges: bytes\r\nContent-Length: "+count+"\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n"+(partial?"Content-Range: bytes "+start+"-"+end+"/"+total+"\r\n":"")+"\r\n";
            OutputStream out=socket.getOutputStream();out.write(header.getBytes(StandardCharsets.US_ASCII));input.seek(start);
            byte[] buffer=new byte[65536];
            while(count>0){int n=input.read(buffer,0,(int)Math.min(count,buffer.length));if(n<0)break;out.write(buffer,0,n);count-=n;}
            out.flush();
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InetAddress from = s.getInetAddress();
            String who = from.getHostAddress();
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            String method = first[0], path = first.length > 1 ? first[1] : "/";
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) h.put(lines[i].substring(0, k).trim().toLowerCase(), lines[i].substring(k + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(get(h, "content-length", "0")); } catch (Exception ignored) { }
            boolean upload = path.startsWith("/api/upload");
            if (len < 0 || len > (upload ? Uploads.VIDEO_MAX : 200_000)) { reply(s, 413, "text/plain", "too large", false); return; }
            if (upload) s.setSoTimeout(90_000);   // a few megabytes from the phone, maybe over Tailscale
            String device=get(h,"x-device","");
            if(!device.isEmpty() && !device.equals("local")) {
                if(!ControlStore.sessionValid(app,get(h,"x-key",""))) {reply(s,401,"application/json","{}",false);return;}
                if(!path.matches("/api/(state|prefs|teams|shows|save|upload|quran|appupdate|test)(\\?.*)?")) {reply(s,403,"application/json","{}",false);return;}
                relayDevice(s,in,method,path,h,len,device); return;
            }
            byte[] body = new byte[upload ? 0 : len];
            for (int got = 0; got < body.length; ) { int n = in.read(body, got, body.length - got); if (n < 0) throw new java.io.EOFException(); got += n; }
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                reply(s, 200, "text/html; charset=utf-8", asset("control.html"), false); return;
            }
            if ("GET".equals(method) && "/manifest.json".equals(path)) {   // "add to home screen" on the phone
                reply(s, 200, "application/manifest+json", "{\"name\":\"تلفزيوني\",\"short_name\":\"تلفزيوني\",\"start_url\":\"/\","
                    + "\"display\":\"standalone\",\"background_color\":\"#0b1410\",\"theme_color\":\"#0b1410\",\"dir\":\"rtl\",\"lang\":\"ar\","
                    + "\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"512x512\",\"type\":\"image/png\"}]}", false); return;
            }
            if ("GET".equals(method) && "/icon.png".equals(path)) { replyBytes(s, "image/png", assetBytes("control-icon.png")); return; }
            if ("/api/public".equals(path)) {   // the screensaver page on this TV: file:// origin, so CORS here only
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString(), true); return;
            }
            if ("/api/autoquran".equals(path)) {   // the screensaver page: the Makkah stream for Jumu'ah and Taraweeh
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                ControlStore.setQuran(app, new JSONObject(new String(body, StandardCharsets.UTF_8)).optBoolean("on"));
                reply(s, 200, "application/json", "{\"ok\":true}", true); return;
            }
            if ("/api/adhanvolume".equals(path)) {   // the screensaver page: the adhan at its own level, then back
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                adhanVolume(new JSONObject(new String(body, StandardCharsets.UTF_8)).optBoolean("on"));
                reply(s, 200, "application/json", "{\"ok\":true}", true); return;
            }
            if ("/api/adhan".equals(path)) {   // the screensaver page: play the adhan video now
                if (!from.isLoopbackAddress()) { reply(s, 403, "text/plain", "local only", false); return; }
                if ("OPTIONS".equals(method)) { reply(s, 204, "text/plain", "", true); return; }
                JSONObject a = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String id = a.optString("id");
                if (!id.matches("[A-Za-z0-9_-]{11}")) { reply(s, 400, "text/plain", "id", true); return; }
                ControlStore.prefs(app).edit().putString("adhanPlay", new JSONObject().put("id", id)
                    .put("start", Math.max(0, a.optInt("start"))).put("end", Math.min(600, a.optInt("end", 240)))
                    .put("at", System.currentTimeMillis()).toString()).apply();
                reply(s, 200, "application/json", "{\"ok\":true}", true); return;
            }
            if (!"POST".equals(method) && !"GET".equals(method)) { reply(s, 405, "text/plain", "method", false); return; }

            if ("/api/login".equals(path) && "POST".equals(method)) {
                long wait = lockWait(who);
                if (wait > 0) { reply(s, 429, "application/json", "{\"error\":\"locked\",\"minutes\":" + (wait / 60_000 + 1) + "}", false); return; }
                String pin = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("pin");
                if (!ControlStore.pinMatches(app, pin)) {
                    failed(who);
                    Thread.sleep(1000);   // slows guessing further
                    reply(s, 401, "application/json", "{\"error\":\"pin\"}", false); return;
                }
                synchronized (this) { fails.remove(who); }
                reply(s, 200, "application/json", new JSONObject().put("key", ControlStore.newSession(app)).toString(), false); return;
            }

            if ("GET".equals(method) && path.startsWith("/file/")) {   // the screensaver page on this TV: the adhan, a photo
                String name = path.substring(6);
                java.io.File f = from.isLoopbackAddress() && Uploads.okName(name) ? Uploads.file(app, name) : null;
                if (f == null || !f.isFile()) { reply(s, 404, "text/plain", "none", false); return; }
                replyFile(s, Uploads.type(app, name), f, get(h, "range", "")); return;
            }
            if ("GET".equals(method) && "/api/syncfile".equals(path)) {   // the sister TV takes a file (ControlSync)
                if (!ControlSync.keyMatches(app, get(h, "x-sync", ""))) { failed(who); reply(s, 401, "text/plain", "sync", false); return; }
                String name = query.startsWith("name=") ? query.substring(5) : "";
                byte[] f = Uploads.okName(name) ? Uploads.read(app, name) : null;
                if (f == null) { reply(s, 404, "text/plain", "none", false); return; }
                replyBytes(s, Uploads.type(app, name), f); return;
            }
            if ("GET".equals(method) && "/api/peer".equals(path)) {   // another TV passing on a test, the Quran or "take it now"
                if (!ControlSync.keyMatches(app, get(h, "x-sync", ""))) { failed(who); reply(s, 401, "text/plain", "sync", false); return; }
                if (query.startsWith("test=")) runTest(query.substring(5));
                else if (query.startsWith("quran=")) ControlStore.setQuran(app, query.endsWith("1"));
                else if (query.startsWith("pull=")) new Thread(() -> ControlSync.pull(app)).start();
                reply(s, 200, "application/json", "{\"ok\":true}", false); return;
            }
            if ("GET".equals(method) && "/api/sync".equals(path)) {   // the household's other TV (ControlSync), its own key
                if (!ControlSync.keyMatches(app, get(h, "x-sync", ""))) { failed(who); reply(s, 401, "text/plain", "sync", false); return; }
                reply(s, 200, "application/json; charset=utf-8", ControlSync.state(app).toString(), false); return;
            }
            String key = get(h, "x-key", "");
            if (!ControlStore.sessionValid(app, key)) { reply(s, 401, "application/json", "{\"error\":\"session\"}", false); return; }
            if("GET".equals(method) && "/api/devices".equals(path)) {
                JSONArray list=new JSONArray(), devices=new JSONArray(ControlStore.prefs(app).getString("controlDevices","[]"));
                list.put(new JSONObject().put("id","local").put("name","TCL"));
                for(int n=0;n<devices.length();n++){JSONObject d=devices.getJSONObject(n);list.put(new JSONObject().put("id",d.getString("id")).put("name",d.optString("name",d.getString("id"))));}
                reply(s,200,"application/json",list.toString(),false);return;
            }
            if ("GET".equals(method) && "/api/prefs".equals(path)) {
                reply(s, 200, "application/json; charset=utf-8", ControlPrefs.read(app).toString(), false); return;
            }
            if ("GET".equals(method) && "/api/teams".equals(path)) {
                String q = query.startsWith("q=") ? java.net.URLDecoder.decode(query.substring(2), "UTF-8") : "";
                reply(s, 200, "application/json; charset=utf-8", ControlPrefs.searchTeams(app, q).toString(), false); return;
            }
            if ("POST".equals(method) && upload) {   // ?name=adhan-main|adhan-fajr|fam-<id> [&del=1], the file as the body
                String name = "", del = "";
                for (String kv : query.split("&")) {
                    if (kv.startsWith("name=")) name = kv.substring(5);
                    if (kv.startsWith("del=")) del = kv.substring(4);
                }
                String type = get(h, "content-type", "").split(";")[0].trim().toLowerCase();
                boolean video = name.startsWith("adhan-video-");
                boolean audio = name.startsWith("adhan-") && !video;
                if (!Uploads.okName(name)) { reply(s, 400, "application/json", "{\"error\":\"name\"}", false); return; }
                if ("1".equals(del)) { Uploads.delete(app, name, System.currentTimeMillis()); reply(s, 200, "application/json", "{\"ok\":true}", false); return; }
                if (len < 1000 || len > (video ? Uploads.VIDEO_MAX : Uploads.MAX) || !(video ? type.equals("video/mp4") : audio ? type.startsWith("audio/") : type.matches("image/(jpeg|png|webp)"))) {
                    reply(s, 400, "application/json", "{\"error\":\"type\"}", false); return;
                }
                Uploads.saveStream(app, name, in, len, System.currentTimeMillis(), type);
                reply(s, 200, "application/json", "{\"ok\":true}", false); return;
            }
            if ("GET".equals(method) && "/api/shows".equals(path)) {
                String q = query.startsWith("q=") ? java.net.URLDecoder.decode(query.substring(2), "UTF-8") : "";
                reply(s, 200, "application/json; charset=utf-8", ControlPrefs.searchShows(app, q).toString(), false); return;
            }
            if ("POST".equals(method) && "/api/prefs".equals(path)) {
                String err = ControlPrefs.apply(app, new JSONObject(new String(body, StandardCharsets.UTF_8)));
                reply(s, err == null ? 200 : 400, "application/json", err == null ? "{\"ok\":true}" : "{\"error\":\"" + err + "\"}", false); return;
            }
            if ("/api/logout".equals(path)) { ControlStore.endSession(app, key); reply(s, 200, "application/json", "{\"ok\":true}", false); return; }
            if ("/api/appupdate".equals(path)) {   // «تحديث التطبيق» on the phone: download here, «تثبيت» on the TV
                if ("POST".equals(method)) new Thread(() -> AppUpdater.run(app, true)).start();
                String st = "POST".equals(method) ? "جارٍ التنزيل… اضغط «تثبيت» على الشاشة" : AppUpdater.run(app, false);
                reply(s, 200, "application/json; charset=utf-8", new JSONObject().put("status", st).toString(), false); return;
            }
            if ("GET".equals(method) && "/api/state".equals(path)) {
                reply(s, 200, "application/json; charset=utf-8", ControlStore.state(app).toString(), false); return;
            }
            if ("POST".equals(method)) {
                JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if ("/api/quran".equals(path)) {
                    ControlStore.setQuran(app, o.optBoolean("on"));
                    ControlSync.tell(app, "quran=" + (o.optBoolean("on") ? 1 : 0));   // the household's other TVs too
                    reply(s, 200, "application/json", "{\"ok\":true}", false); return;
                }
                if ("/api/test".equals(path)) {   // «تجربة الميزات» on the phone: on every TV of the household
                    String what = o.optString("what");
                    if (!runTest(what)) { reply(s, 400, "application/json", "{\"error\":\"what\"}", false); return; }
                    ControlSync.tell(app, "test=" + what);
                    reply(s, 200, "application/json", "{\"ok\":true}", false); return;
                }
                if ("/api/save".equals(path)) {
                    String section = o.optString("section");
                    boolean known = false;
                    for (String x : ControlStore.SECTIONS) if (x.equals(section)) known = true;
                    Object v = o.opt("value");
                    if (!known || !(v instanceof JSONObject || v instanceof JSONArray)) {
                        reply(s, 400, "application/json", "{\"error\":\"section\"}", false); return;
                    }
                    ControlStore.save(app, section, v.toString());
                    ControlSync.tell(app, "pull=1");   // the other TVs take it now, not at their next minute
                    reply(s, 200, "application/json", "{\"ok\":true}", false); return;
                }
            }
            reply(s, 404, "text/plain", "not found", false);
        } catch (Exception e) {
            try { reply(s, 400, "text/plain", "bad request", false); } catch (Exception ignored) { }
        }
    }

    /** a test from the phone (or passed on by another TV): what waits for its moment, now; "stop" ends them */
    private boolean runTest(String what) {
        if (!what.matches("whistle|chime|soft|kickoff|hour|adhan|weather|friday|series|overlay|stop")) return false;
        if ("overlay".equals(what)) { new WeatherWatch(app).test(); return true; }   // the notice over other apps
        try {
            ControlStore.prefs(app).edit().putString("selftest", new JSONObject().put("what", what)
                .put("at", System.currentTimeMillis()).toString()).apply();
        } catch (Exception ignored) { }
        return true;
    }

    /** on: remember the TV's volume and set the adhan's (display.adhanVolume, 0..100, default 20); off: put it back */
    private void adhanVolume(boolean on) {
        android.media.AudioManager au = (android.media.AudioManager) app.getSystemService(android.content.Context.AUDIO_SERVICE);
        if (au == null) return;
        int st = android.media.AudioManager.STREAM_MUSIC, max = au.getStreamMaxVolume(st);
        android.content.SharedPreferences p = ControlStore.prefs(app);
        try {
            if (on) {
                if (!p.contains("volBeforeAdhan")) p.edit().putInt("volBeforeAdhan", au.getStreamVolume(st)).apply();
                JSONObject d = ControlStore.state(app).optJSONObject("display");
                int level = Math.max(0, Math.min(100, d == null ? 20 : d.optInt("adhanVolume", 20)));
                au.setStreamVolume(st, Math.round(max * level / 100f), 0);
            } else if (p.contains("volBeforeAdhan")) {
                au.setStreamVolume(st, p.getInt("volBeforeAdhan", au.getStreamVolume(st)), 0);
                p.edit().remove("volBeforeAdhan").apply();
            }
        } catch (Exception ignored) { }   // a TV with fixed volume (an amplifier's): nothing to set
    }

    private final Map<String,String> deviceSessions=new java.util.concurrent.ConcurrentHashMap<>();
    private void relayDevice(Socket client,InputStream body,String method,String path,Map<String,String> headers,int length,String id) throws Exception {
        JSONObject device=null;JSONArray devices=new JSONArray(ControlStore.prefs(app).getString("controlDevices","[]"));
        for(int i=0;i<devices.length();i++)if(id.equals(devices.getJSONObject(i).getString("id")))device=devices.getJSONObject(i);
        if(device==null){reply(client,404,"application/json","{}",false);return;}
        String host=device.getString("host");
        try {
            String token=deviceSessions.get(id);
            if(token==null){
                try(Socket remote=new Socket()){
                    remote.connect(new java.net.InetSocketAddress(host,ControlStore.PORT),4000);remote.setSoTimeout(10000);
                    byte[] data=new JSONObject().put("pin",device.getString("pin")).toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream out=remote.getOutputStream();out.write(("POST /api/login HTTP/1.1\r\nHost: "+host+"\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(data);out.flush();
                    InputStream input=remote.getInputStream();String head=readHead(input);if(head==null||!head.startsWith("HTTP/1.1 200"))throw new java.io.IOException("login");
                    ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=input.read(buf))!=-1){result.write(buf,0,n);if(result.size()>16384)throw new java.io.IOException("response");}
                    token=new JSONObject(result.toString("UTF-8")).getString("key");deviceSessions.put(id,token);
                }
            }
            try(Socket remote=new Socket()){
                remote.connect(new java.net.InetSocketAddress(host,ControlStore.PORT),4000);remote.setSoTimeout(90000);client.setSoTimeout(90000);
                OutputStream out=remote.getOutputStream();String type=get(headers,"content-type","application/json");if(!type.matches("[A-Za-z0-9/;= ._-]+"))type="application/octet-stream";
                out.write((method+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\nX-Key: "+token+"\r\nContent-Type: "+type+"\r\nContent-Length: "+length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                byte[] buf=new byte[65536];int left=length;while(left>0){int n=body.read(buf,0,Math.min(left,buf.length));if(n<0)throw new java.io.EOFException();out.write(buf,0,n);left-=n;}out.flush();
                InputStream input=remote.getInputStream();String head=readHead(input);if(head==null)throw new java.io.IOException("response");
                if(head.startsWith("HTTP/1.1 401")){deviceSessions.remove(id);reply(client,502,"application/json","{\"error\":\"device_session_retry\"}",false);return;}
                OutputStream target=client.getOutputStream();target.write(head.getBytes(StandardCharsets.UTF_8));int n;while((n=input.read(buf))!=-1)target.write(buf,0,n);target.flush();
            }
        }catch(Exception e){reply(client,503,"application/json","{\"error\":\"device_unavailable\"}",false);}
    }

    /** ms until this address may try again (0 = now) */
    private synchronized long lockWait(String who) {
        long now = System.currentTimeMillis();
        if (globalLockUntil > now) return globalLockUntil - now;
        Long until = lockedUntil.get(who);
        return until != null && until > now ? until - now : 0;
    }

    private synchronized void failed(String who) {
        long now = System.currentTimeMillis();
        int[] n = fails.get(who);
        if (n == null) fails.put(who, n = new int[1]);
        if (++n[0] >= PER_ADDRESS) { lockedUntil.put(who, now + ADDRESS_LOCK_MS); n[0] = 0; }
        recentFails.addLast(now);
        while (!recentFails.isEmpty() && recentFails.peekFirst() < now - GLOBAL_WINDOW_MS) recentFails.removeFirst();
        if (recentFails.size() >= GLOBAL) { globalLockUntil = now + GLOBAL_WINDOW_MS; recentFails.clear(); }
    }

    private static String get(Map<String, String> m, String k, String dflt) {
        String v = m.get(k);
        return v == null ? dflt : v;
    }

    private static String readHead(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > 16384) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("UTF-8");
        }
        return null;
    }

    private String asset(String name) throws java.io.IOException {
        return new String(assetBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] assetBytes(String name) throws java.io.IOException {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void replyBytes(Socket s, String type, byte[] body) throws java.io.IOException {
        String head = "HTTP/1.1 200 X" + CRLF + "Content-Type: " + type + CRLF + "Content-Length: " + body.length
            + CRLF + "X-Content-Type-Options: nosniff" + CRLF + "Cache-Control: max-age=86400" + CRLF + "Connection: close" + CRLF + CRLF;
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        s.close();
    }

    private static void reply(Socket s, int code, String type, String text, boolean cors) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + (cors ? "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET" : "")
            + "\r\nX-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
            + "\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; frame-ancestors 'none'"
            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        s.close();
    }
}
