package sa.yourtv.v2.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The screensaver's settings from ADB, for agents (people use SettingsActivity). Only the shell can send
 * these: the manifest requires android.permission.DUMP. optimize/saver.sh wraps them.
 *
 *   am broadcast -n sa.yourtv.v2.screensaver/.SaverReceiver -a sa.yourtv.v2.screensaver.GET
 *   am broadcast -n sa.yourtv.v2.screensaver/.SaverReceiver -a sa.yourtv.v2.screensaver.CITIES
 *   am broadcast -n sa.yourtv.v2.screensaver/.SaverReceiver -a sa.yourtv.v2.screensaver.SET
 *       [--es city 'الرس'] [--es lat 25.86 --es lon 43.49] [--es teams '136013:Al-Hilal,150643:Al-Zulfi']
 *       [--es panel prayers] [--es home_panel none] [--es minrating 7] [--es genres Drama,Comedy] [--es langs English]
 *   am broadcast -n sa.yourtv.v2.screensaver/.SaverReceiver -a sa.yourtv.v2.screensaver.UPDATE
 *
 * The answer is in the "data" of am's output. Unknown values are refused with a message, nothing half-saved.
 */
public class SaverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        String action = String.valueOf(i.getAction());
        switch (action) {
            case "sa.yourtv.v2.screensaver.HUB": {
                try {
                    org.json.JSONArray devices = new org.json.JSONArray(i.getStringExtra("devices"));
                    for(int n=0;n<devices.length();n++) {
                        org.json.JSONObject d=devices.getJSONObject(n);
                        if(!d.getString("id").matches("[a-z0-9_-]{1,20}") || !d.getString("host").matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}") || !d.getString("pin").matches("[0-9]{6}")) throw new Exception();
                    }
                    ControlStore.prefs(c).edit().putString("controlDevices",devices.toString()).apply();
                    setResultData("hub configured");
                } catch(Exception e) { setResultData("invalid devices"); }
                return;
            }
            case "sa.yourtv.v2.screensaver.GET":
                setResultData(describe(p));
                return;
            case "sa.yourtv.v2.screensaver.CITIES": {
                StringBuilder b = new StringBuilder();
                for (Cities.Region r : Cities.REGIONS) {
                    b.append(r.name).append(": ");
                    for (String[] city : r.cities) b.append(city[0]).append("، ");
                    b.setLength(b.length() - 2);
                    b.append('\n');
                }
                setResultData(b.toString().trim());
                return;
            }
            case "sa.yourtv.v2.screensaver.UPDATE": {
                final PendingResult pending = goAsync();   // a download: off the main thread
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    pending.setResultData("update: " + Updater.check(app, true));
                    pending.finish();
                }).start();
                return;
            }
            case "sa.yourtv.v2.screensaver.SYNC": {   // --es key <32+ characters> --es peers <the other TVs' addresses, comma separated>
                String k = i.getStringExtra("key"), peers = i.getStringExtra("peers");
                if (k == null || !k.matches("[A-Za-z0-9]{32,128}") || peers == null || !peers.matches("[0-9.,]{7,200}")) {
                    setResultData("error: --es key (32+ letters and digits) and --es peers (addresses, comma separated)");
                    return;
                }
                ControlStore.prefs(c).edit().putString("syncKey", k).putString("syncPeers", peers).apply();
                setResultData("sync: " + peers);
                return;
            }
            case "sa.yourtv.v2.screensaver.TEST": {   // --es what whistle|chime|soft|kickoff|hour|adhan|weather|friday|series|overlay
                String what = String.valueOf(i.getStringExtra("what"));
                if (!what.matches("[a-z]{3,12}")) { setResultData("error: --es what"); return; }
                if ("overlay".equals(what)) new WeatherWatch(c).test();   // the notice over other apps
                else try {
                    ControlStore.prefs(c).edit().putString("selftest", new org.json.JSONObject().put("what", what)
                        .put("at", System.currentTimeMillis()).toString()).apply();
                } catch (Exception ignored) { }
                setResultData("test: " + what);
                return;
            }
            case "sa.yourtv.v2.screensaver.SET":
                setResultData(set(c, p, i));
                return;
            default:
                setResultData("error: unknown action " + action);
        }
    }

    private static String set(Context c, Prefs p, Intent i) {
        // check everything first, then save: a typo never leaves half the settings changed
        String[] city = null;
        String name = i.getStringExtra("city"), lat = i.getStringExtra("lat"), lon = i.getStringExtra("lon");
        if (name != null) {
            if (lat != null && lon != null) {
                if (!lat.matches("-?\\d{1,2}(\\.\\d+)?") || !lon.matches("-?\\d{1,3}(\\.\\d+)?"))
                    return "error: lat/lon must be numbers like 25.86 / 43.49";
                city = new String[] {name, lat, lon};
            } else {
                for (Cities.Region r : Cities.REGIONS) for (String[] x : r.cities) if (x[0].equals(name)) city = x;
                if (city == null) return "error: city not in the built-in list; give --es lat and --es lon too (CITIES lists them)";
            }
        }
        List<String> teams = null;
        String t = i.getStringExtra("teams");
        if (t != null) {
            teams = new ArrayList<>();
            for (String part : t.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String[] kv = part.split(":", 2);
                if (!kv[0].matches("\\d+")) return "error: teams are id:EnglishName, e.g. 136013:Al-Hilal";
                String en = kv.length > 1 ? kv[1].trim() : "";
                String ar = en.isEmpty() ? "#" + kv[0] : Arabic.team(c, en);
                teams.add(kv[0] + "|" + ar.replace("|", " ").replace(";", " "));
            }
            if (teams.size() > Prefs.MAX_TEAMS) return "error: at most " + Prefs.MAX_TEAMS + " teams";
        }
        String panel = i.getStringExtra("panel"), home = i.getStringExtra("home_panel");
        if (panel != null && !isPanel(panel)) return "error: panel is one of " + panels();
        if (home != null && !isPanel(home)) return "error: home_panel is one of " + panels();
        String rating = i.getStringExtra("minrating");
        if (rating != null && !Arrays.asList(Prefs.MIN_RATINGS).contains(rating))
            return "error: minrating is one of " + Prefs.join(Arrays.asList(Prefs.MIN_RATINGS), " ");
        List<String> genres = codes(i.getStringExtra("genres"), Prefs.GENRES);
        List<String> langs = codes(i.getStringExtra("langs"), Prefs.LANGS);
        if (genres != null && genres.contains(null)) return "error: genres are " + names(Prefs.GENRES);
        if (langs != null && langs.contains(null)) return "error: langs are " + names(Prefs.LANGS);

        if (city != null) p.setCity(city[0], city[1], city[2]);
        if (teams != null) p.setList("teams", teams);
        if (panel != null) p.setPanel(panel);
        if (home != null) p.setHomePanel(home);
        if (rating != null) p.setMinRating(rating);
        if (genres != null) p.setList("genres", genres);
        if (langs != null) p.setList("langs", langs);
        return "saved\n" + describe(p);
    }

    private static String describe(Prefs p) {
        String[] city = p.city();
        return "city: " + city[0] + " (" + city[1] + ", " + city[2] + ")"
            + "\nteams: " + Prefs.join(p.teams(), ", ")
            + "\npanel: " + p.panel() + "\nhome_panel: " + p.homePanel()
            + "\nminrating: " + p.minRating()
            + "\ngenres: " + Prefs.join(p.list("genres"), ",") + "\nlangs: " + Prefs.join(p.list("langs"), ",")
            + "\nautoupdate: " + p.autoUpdate()
            + "\ncontrol: " + ControlStore.url() + " pin " + ControlStore.pin(p.context())
            + "\ntailscale: " + ControlStore.tailscaleUrl();
    }

    private static boolean isPanel(String v) {
        for (String[] x : Prefs.PANELS) if (x[0].equals(v)) return true;
        return false;
    }

    private static String panels() {
        return names(Prefs.PANELS);
    }

    private static String names(String[][] table) {
        List<String> out = new ArrayList<>();
        for (String[] x : table) out.add(x[0]);
        return Prefs.join(out, " ");
    }

    /** "Drama,Comedy" -> the same codes, checked against the table; null entry = unknown; "" = all. */
    private static List<String> codes(String raw, String[][] table) {
        if (raw == null) return null;
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            String hit = null;
            for (String[] x : table) if (x[0].equalsIgnoreCase(s)) hit = x[0];
            out.add(hit);
        }
        return out;
    }
}
