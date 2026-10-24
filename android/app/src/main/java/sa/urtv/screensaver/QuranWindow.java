package sa.yourtv.v2.screensaver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Quran channel's live stream from YouTube in a small window at the top centre of the screensaver.
 * Off until the remote's OK turns it on; OK again turns it off. YouTube's player stays visible, as its
 * terms require. It runs in its own WebView with an https address, because YouTube will not play an
 * embed whose page has no web origin (the screensaver page itself is a file).
 */
final class QuranWindow {
    /** «القرآن الكريم» (youtube.com/@AlQuran-Official), Makkah live, picked by the household: its current
     *  live video, and the one they chose if that cannot be looked up */
    private static final String CHANNEL = "UCqDUJp6Z4wyFkDJGqd0EP4Q";
    private static final String FALLBACK_VIDEO = "m68ZBweM1gg";
    private static final String ORIGIN = "https://jolyan20.github.io/YourTV/";

    private final FrameLayout root;
    private WebView player;

    QuranWindow(FrameLayout root) { this.root = root; }

    boolean isOn() { return player != null; }

    void toggle() {
        if (player == null) show(null, 0, 0); else hide();
    }

    /** one video for a while (the adhan from the Haram's own channel), then the window closes and `done` runs */
    void playVideo(String id, int start, int end, final Runnable done) {
        hide();
        show(id, start, end);
        final WebView mine = player;
        root.postDelayed(() -> { if (player == mine) hide(); if (done != null) done.run(); }, (end - start + 5) * 1000L);
    }

    /** the page asked for the adhan (ControlServer /api/adhan): play it, then bring the stream back if it was on */
    void adhan(String json) {
        try {
            org.json.JSONObject a = new org.json.JSONObject(json);
            if (System.currentTimeMillis() - a.optLong("at") > 60_000) return;
            final boolean wasOn = isOn();
            playVideo(a.getString("id"), a.optInt("start"), a.optInt("end", 240), () -> { if (wasOn && !isOn()) toggle(); });
        } catch (Exception ignored) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void show(final String fixed, final int start, final int end) {
        Context c = root.getContext();
        final WebView w = new WebView(c);
        w.setBackgroundColor(0xFF000000);
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setWebChromeClient(new WebChromeClient());
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // OK on the remote is the gesture
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xFF000000);
        frame.setCornerRadius(dp(c, 10));
        frame.setStroke((int) dp(c, 1.5f), 0x99DCB86F);
        w.setBackground(frame);
        w.setClipToOutline(true);
        // size and place from the phone page (display.quranSize s|m|l, display.quranPos tc|tr|tl|bc|br|bl)
        org.json.JSONObject d = ControlStore.state(c).optJSONObject("display");
        String size = d == null ? "s" : d.optString("quranSize", "s"), pos = d == null ? "tc" : d.optString("quranPos", "tc");
        float share = "l".equals(size) ? 0.42f : "m".equals(size) ? 0.33f : 0.25f;
        int width = (int) (root.getWidth() * share);
        int g = (pos.startsWith("b") ? Gravity.BOTTOM : Gravity.TOP)
              | (pos.endsWith("r") ? Gravity.RIGHT : pos.endsWith("l") ? Gravity.LEFT : Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, width * 9 / 16, g);
        int mx = (int) (root.getWidth() * 0.03f), my = (int) (root.getHeight() * 0.035f);
        lp.setMargins(mx, my, mx, my);
        root.addView(w, lp);
        final String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>"
            + "</head><body><iframe allow='autoplay; encrypted-media' src='__SRC__'></iframe></body></html>";
        player = w;
        // YouTube no longer plays the channel's generic live link in an embed: find today's live video first
        new Thread(() -> {
            String id = fixed != null ? fixed : liveVideoId();
            String src = "https://www.youtube.com/embed/" + (id != null ? id : FALLBACK_VIDEO)
                + "?autoplay=1&controls=0&modestbranding=1&rel=0&playsinline=1"
                + (fixed != null ? "&start=" + start + "&end=" + end : "");
            final String page = html.replace("__SRC__", src);
            w.post(() -> { if (player == w) w.loadDataWithBaseURL(ORIGIN, page, "text/html", "utf-8", null); });
        }).start();
    }

    /** The channel's live video id, from its /live page; null when offline or not live. */
    private static String liveVideoId() {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("https://www.youtube.com/channel/" + CHANNEL + "/live").openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setRequestProperty("Accept-Language", "ar");
            InputStream in = con.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
            in.close();
            Matcher m = Pattern.compile("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})")
                .matcher(b.toString("UTF-8"));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    void hide() {
        if (player == null) return;
        root.removeView(player);
        player.destroy();
        player = null;
    }

    private static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
}
