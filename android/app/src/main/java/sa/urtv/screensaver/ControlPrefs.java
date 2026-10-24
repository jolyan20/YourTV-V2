package sa.yourtv.v2.screensaver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The screensaver's own settings (city, followed teams, the card beside the clock and on the home screen) for
 * the phone page: read them, change them with the same checks as optimize/saver.sh, and search TheSportsDB for
 * a team (from the TV, so the phone needs nothing but the page).
 */
final class ControlPrefs {
    private static long lastSearch;

    private ControlPrefs() {}

    static JSONObject read(Context c) throws Exception {
        Prefs p = new Prefs(c);
        JSONObject o = new JSONObject();
        String[] city = p.city();
        o.put("city", new JSONArray().put(city[0]).put(city[1]).put(city[2]));
        JSONArray teams = new JSONArray();
        for (String t : p.teams()) {
            String[] kv = t.split("\\|", 2);
            teams.put(new JSONObject().put("id", kv[0]).put("name", kv.length > 1 ? kv[1] : kv[0]));
        }
        o.put("teams", teams);
        o.put("panel", p.panel());
        o.put("home_panel", p.homePanel());
        JSONArray regions = new JSONArray();
        for (Cities.Region r : Cities.REGIONS) {
            JSONArray names = new JSONArray();
            for (String[] x : r.cities) names.put(x[0]);
            regions.put(new JSONObject().put("name", r.name).put("cities", names));
        }
        o.put("regions", regions);
        JSONArray panels = new JSONArray();
        for (String[] x : Prefs.PANELS) panels.put(new JSONArray().put(x[0]).put(x[1]));
        o.put("panels", panels);
        return o;
    }

    /** null when saved, else what was wrong (nothing is saved then) */
    static String apply(Context c, JSONObject o) throws Exception {
        return apply(c, o, true);
    }

    /** stamp: a change made here (the phone page), which the other TVs then take; false for one taken from them */
    static String apply(Context c, JSONObject o, boolean stamp) throws Exception {
        Prefs p = new Prefs(c);
        String[] city = null;
        if (o.has("city")) {
            String name = o.optString("city");
            for (Cities.Region r : Cities.REGIONS) for (String[] x : r.cities) if (x[0].equals(name)) city = x;
            if (city == null) return "city";
        }
        List<String> teams = null;
        if (o.has("teams")) {
            JSONArray a = o.getJSONArray("teams");
            if (a.length() > Prefs.MAX_TEAMS) return "teams";
            teams = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                String id = t.optString("id");
                if (!id.matches("\\d{1,9}")) return "teams";
                String name = t.optString("name").trim();
                if (name.isEmpty() || name.length() > 60) return "teams";
                teams.add(id + "|" + name.replace("|", " ").replace(";", " "));
            }
        }
        String panel = o.has("panel") ? o.optString("panel") : null, home = o.has("home_panel") ? o.optString("home_panel") : null;
        if (panel != null && !isPanel(panel)) return "panel";
        if (home != null && !isPanel(home)) return "home_panel";
        if (city != null) p.setCity(city[0], city[1], city[2]);
        if (teams != null) p.setList("teams", teams);
        if (panel != null) p.setPanel(panel);
        if (home != null) p.setHomePanel(home);
        if (stamp && (city != null || teams != null)) ControlStore.prefs(c).edit().putLong("at:prefs", System.currentTimeMillis()).apply();
        return null;
    }

    private static boolean isPanel(String v) {
        for (String[] x : Prefs.PANELS) if (x[0].equals(v)) return true;
        return false;
    }

    /** football teams for a name, with their Arabic name; one search every 3 s at most (free API) */
    static JSONArray searchTeams(Context c, String q) throws Exception {
        q = q == null ? "" : q.trim();
        if (q.length() < 2 || q.length() > 40) return new JSONArray();
        synchronized (ControlPrefs.class) {
            long wait = lastSearch + 3000 - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastSearch = System.currentTimeMillis();
        }
        HttpURLConnection con = (HttpURLConnection) new URL("https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t="
            + URLEncoder.encode(q, "UTF-8")).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        JSONArray out = new JSONArray();
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
            JSONArray teams = new JSONObject(b.toString("UTF-8")).optJSONArray("teams");
            if (teams == null) return out;
            for (int i = 0; i < teams.length() && out.length() < 12; i++) {
                JSONObject t = teams.getJSONObject(i);
                if (!"Soccer".equals(t.optString("strSport"))) continue;
                String en = t.optString("strTeam");
                out.put(new JSONObject().put("id", t.optString("idTeam")).put("en", en).put("name", Arabic.team(c, en))
                    .put("league", t.optString("strLeague")).put("country", t.optString("strCountry"))
                    .put("badge", t.optString("strBadge", t.optString("strTeamBadge"))));
            }
        } finally {
            con.disconnect();
        }
        return out;
    }

    /** series for «مسلسلاتي»: TVmaze's search, from the TV */
    /** the household's TMDB key (the phone page's «مسلسلاتي»): a v4 read token, or a v3 key */
    static String tmdbToken(Context c) {
        try { return new JSONObject(ControlStore.prefs(c).getString("tmdb", "{}")).optString("token").trim(); } catch (Exception e) { return ""; }
    }

    /** a TMDB request with the household's key; the answer's JSON text */
    static String tmdb(Context c, String path) throws Exception {
        String t = tmdbToken(c);
        boolean v3 = t.matches("[0-9a-f]{32}");
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.themoviedb.org/3" + path
            + (v3 ? (path.contains("?") ? "&" : "?") + "api_key=" + t : "")).openConnection();
        if (!v3) con.setRequestProperty("Authorization", "Bearer " + t);
        con.setRequestProperty("Accept", "application/json");
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    /** series and films in any language, searched in Arabic or English (TMDB); TVmaze while there is no key */
    static JSONArray searchShows(Context c, String q) throws Exception {
        q = q == null ? "" : q.trim();
        if (q.length() < 2 || q.length() > 60) return new JSONArray();
        if (tmdbToken(c).length() >= 32) {
            JSONArray hits = new JSONObject(tmdb(c, "/search/multi?language=ar-SA&include_adult=false&query=" + URLEncoder.encode(q, "UTF-8")))
                .optJSONArray("results");
            JSONArray out = new JSONArray();
            for (int i = 0; hits != null && i < hits.length() && out.length() < 12; i++) {
                JSONObject h = hits.getJSONObject(i);
                String type = h.optString("media_type");
                if (!"tv".equals(type) && !"movie".equals(type)) continue;
                boolean tv = "tv".equals(type);
                String date = h.optString(tv ? "first_air_date" : "release_date", "");
                String poster = h.optString("poster_path", "");
                out.put(new JSONObject().put("src", "tmdb").put("type", type).put("id", h.optInt("id"))
                    .put("name", h.optString(tv ? "name" : "title")).put("orig", h.optString(tv ? "original_name" : "original_title"))
                    .put("year", date.length() >= 4 ? date.substring(0, 4) : "").put("lang", h.optString("original_language"))
                    .put("img", poster.startsWith("/") ? "https://image.tmdb.org/t/p/w185" + poster : ""));
            }
            return out;
        }
        return searchTvmaze(q);
    }

    static JSONArray searchTvmaze(String q) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL("https://api.tvmaze.com/search/shows?q="
            + URLEncoder.encode(q, "UTF-8")).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        JSONArray out = new JSONArray();
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
            JSONArray hits = new JSONArray(b.toString("UTF-8"));
            for (int i = 0; i < hits.length() && out.length() < 10; i++) {
                JSONObject sh = hits.getJSONObject(i).optJSONObject("show");
                if (sh == null) continue;
                JSONObject net = sh.optJSONObject("network"), web = sh.optJSONObject("webChannel"), img = sh.optJSONObject("image");
                out.put(new JSONObject().put("id", sh.optInt("id")).put("name", sh.optString("name"))
                    .put("year", sh.optString("premiered", "").length() >= 4 ? sh.optString("premiered").substring(0, 4) : "")
                    .put("net", net != null ? net.optString("name") : web != null ? web.optString("name") : "")
                    .put("status", sh.optString("status")).put("lang", sh.optString("language"))
                    .put("img", img != null ? img.optString("medium") : ""));
            }
        } finally {
            con.disconnect();
        }
        return out;
    }
}
