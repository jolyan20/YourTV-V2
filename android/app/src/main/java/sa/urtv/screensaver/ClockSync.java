package sa.yourtv.v2.screensaver;

import android.content.Context;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * One clock for the household's TVs, so the adhan, the alerts, the cards, the message and the Quran stream change
 * on all of them at the same moment. The leader is the TV whose id (or address) sorts first; every other TV asks it
 * for its time a few times every two minutes over the home network and keeps the offset of the quickest answer
 * (half its round trip), NTP style: a few milliseconds apart on Wi-Fi. A TV alone, or one that cannot reach the
 * leader, is its own clock.
 */
final class ClockSync {
    static volatile long offset = 0;        // household time = System.currentTimeMillis() + offset
    static volatile boolean leader = true;

    private ClockSync() {}

    static long now() { return System.currentTimeMillis() + offset; }

    /** what this TV goes by when the TVs compare: its id, else its address */
    static String name(Context c) {
        String id = ControlStore.prefs(c).getString("tvId", "");
        String a = ControlStore.address();
        return !id.isEmpty() ? id : a == null ? "" : a;
    }

    /** blocking: call off the main thread */
    static void align(Context c) {
        String key = ControlStore.prefs(c).getString("syncKey", ""), peers = ControlStore.prefs(c).getString("syncPeers", "");
        if (key.length() < 32 || peers.isEmpty()) { leader = true; offset = 0; return; }
        String me = name(c);
        for (String peer : peers.split(",")) {
            peer = peer.trim();
            if (!peer.matches("[0-9.]{7,15}")) continue;
            try {
                long best = Long.MAX_VALUE, bestOff = 0;
                String other = null;
                for (int i = 0; i < 5; i++) {
                    long t0 = System.currentTimeMillis();
                    JSONObject o = new JSONObject(new String(ControlSync.fetch(peer, key, "/api/peer?clock=1"), StandardCharsets.UTF_8));
                    long t1 = System.currentTimeMillis(), rtt = t1 - t0;
                    other = o.optString("id");
                    // the leader's own clock (its offset is 0); a follower answers with its household time too
                    long theirs = o.optLong("ms");
                    if (rtt < best) { best = rtt; bestOff = theirs + rtt / 2 - t1; }
                }
                if (other == null || other.isEmpty() || me.isEmpty() || me.compareTo(other) < 0) { leader = true; offset = 0; }
                else { leader = false; offset = bestOff; }
                return;   // one TV that answered is enough
            } catch (Exception ignored) { }   // that TV is off: try the next address
        }
        leader = true;
        offset = 0;
    }
}
