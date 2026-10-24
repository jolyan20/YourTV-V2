package sa.yourtv.v2.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The favourites row: one set of rules for the home screen, its «ترتيب المفضلة» screen and ADB (DockReceiver). */
final class Dock {
    static final int MIN = 5;    // the row never shows fewer apps than this
    static final int MAX = 14;   // tiles share the row width, so 10 still fit at 1080p
    static final String CHANGED = "sa.yourtv.v2.home.DOCK_CHANGED";   // tells a running home screen to redraw

    private Dock() {}

    /** The launcher's settings file (named after MainActivity, which wrote it first). */
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    /** Saved order, or the defaults from config.xml; may name apps that are no longer installed. */
    static List<String> saved(Context c) {
        String s = prefs(c).getString("dock", null);
        return s != null ? Arrays.asList(s.isEmpty() ? new String[0] : s.split(","))
                         : Arrays.asList(c.getResources().getStringArray(R.array.dock_apps));
    }

    /** Apps the home screen can show, package -> name: TV apps minus the hidden ones and the launcher. */
    static Map<String, String> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> hidden = new ArrayList<>(Arrays.asList(c.getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(c.getPackageName());
        Map<String, String> out = new LinkedHashMap<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (!hidden.contains(p) && !out.containsKey(p)) out.put(p, String.valueOf(ri.loadLabel(pm)));
        }
        return out;
    }

    /** The row as the home screen shows it: saved apps still installed, topped up to MIN alphabetically. */
    static List<String> shown(Context c, Map<String, String> apps) {
        List<String> row = new ArrayList<>();
        for (String p : saved(c)) if (apps.containsKey(p) && !row.contains(p) && row.size() < MAX) row.add(p);
        List<String> rest = new ArrayList<>(apps.keySet());
        rest.removeAll(row);
        Collections.sort(rest, (x, y) -> apps.get(x).compareToIgnoreCase(apps.get(y)));
        while (row.size() < MIN && !rest.isEmpty()) row.add(rest.remove(0));
        return row;
    }

    static void save(Context c, List<String> row) {
        prefs(c).edit().putString("dock", String.join(",", row)).apply();
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }

    static void reset(Context c) {
        prefs(c).edit().remove("dock").apply();
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }
}
