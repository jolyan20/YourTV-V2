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
    private boolean live;
    private Runnable pending;
    private static volatile QuranWindow shown;   // the window up on this TV, for the leading TV's place in the stream

    QuranWindow(FrameLayout root) { this.root = root; }

    boolean isOn() { return player != null; }

    void toggle() {
        if (player == null) show(null, 0, 0, 0); else hide();
    }

    /** on or off at a household moment (ClockSync), the same on every TV */
    void setAt(final boolean on, long at) {
        if (pending != null) root.removeCallbacks(pending);
        pending = () -> { pending = null; if (on != isOn()) toggle(); };
        root.postDelayed(pending, Math.max(0, Math.min(10_000, at - ClockSync.now())));
    }

    /** the remote's OK: the stream on or off on this TV and the others, 1.5 s from now on all of them */
    static void everywhere(Context c, boolean on) {
        long at = ClockSync.now() + 1500;
        ControlStore.setQuran(c, on, at);
        ControlSync.tell(c, "quran=" + (on ? 1 : 0) + "a" + at);
    }

    /** the leading TV's place in the live stream: this TV's window seeks to stay with it */
    static void follow(final long pos, final long at) {
        final QuranWindow q = shown;
        if (q == null || ClockSync.leader) return;
        q.root.post(() -> { if (q.player != null && q.live) q.player.evaluateJavascript("OFF=" + ClockSync.offset + ";follow(" + pos + "," + at + ")", null); });
    }

    /** one video for a while (the adhan from the Haram's own channel), then the window closes and `done` runs */
    void playVideo(String id, int start, int end, final Runnable done) {
        playVideo(id, start, end, done, ClockSync.now());
    }

    /** …in step on every TV: at household moment `at` + 2 s it is at `start`, and every TV keeps to that */
    void playVideo(String id, int start, int end, final Runnable done, long at) {
        hide();
        show(id, start, end, at + 2000);
        final WebView mine = player;
        root.postDelayed(() -> { if (player == mine) hide(); if (done != null) done.run(); }, (end - start + 5) * 1000L);
    }

    /** the page asked for the adhan (ControlServer /api/adhan): play it, then bring the stream back if it was on */
    void adhan(String json) {
        try {
            org.json.JSONObject a = new org.json.JSONObject(json);
            if (ClockSync.now() - a.optLong("at") > 60_000) return;
            final boolean wasOn = isOn();
            playVideo(a.getString("id"), a.optInt("start"), a.optInt("end", 240), () -> { if (wasOn && !isOn()) toggle(); }, a.optLong("at"));
        } catch (Exception ignored) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void show(final String fixed, final int start, final int end, final long t0) {
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
        // YouTube's own player (IFrame API), kept in step with the other TVs: a set video sits at the place the
        // household clock gives it; the live stream follows the leading TV, which tells its place every 2 s
        final String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p,iframe{border:0;width:100%;height:100%}</style>"
            + "</head><body><div id='p'></div><script>var P,OFF=__OFF__,FIXED=__FIXED__,START=__START__,END=__END__,T0=__T0__,LEAD=__LEAD__;"
            + "function hnow(){return Date.now()+OFF}"
            + "function onYouTubeIframeAPIReady(){var v={autoplay:1,controls:0,modestbranding:1,rel:0,playsinline:1};if(FIXED){v.start=START;v.end=END}"
            + "P=new YT.Player('p',{width:'100%',height:'100%',videoId:'__ID__',playerVars:v,events:{onReady:function(e){e.target.playVideo();setInterval(step,2000)}}})}"
            + "function step(){if(!P||!P.getCurrentTime)return;var c=P.getCurrentTime();"
            + "if(FIXED){var w=START+(hnow()-T0)/1000;if(w>START+.5&&Math.abs(c-w)>.4)P.seekTo(w,true)}"
            + "else if(LEAD&&window.Bridge&&P.getPlayerState()==1)Bridge.at(Math.round(c*1000),hnow())}"
            + "function follow(pos,at){if(!P||!P.getCurrentTime||P.getPlayerState()!=1)return;var w=pos/1000+(hnow()-at)/1000,c=P.getCurrentTime();if(Math.abs(c-w)>.35)P.seekTo(w,true)}"
            + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
        player = w;
        live = fixed == null;
        shown = this;
        final Context ctx = c.getApplicationContext();
        w.addJavascriptInterface(new Object() {   // the leading TV tells the others where the live stream is
            @android.webkit.JavascriptInterface public void at(long pos, long household) { ControlSync.tell(ctx, "qt=" + pos + "a" + household); }
        }, "Bridge");
        // YouTube no longer plays the channel's generic live link in an embed: find today's live video first
        new Thread(() -> {
            String id = fixed != null ? fixed : liveVideoId();
            final String page = html.replace("__ID__", id != null ? id : FALLBACK_VIDEO).replace("__OFF__", String.valueOf(ClockSync.offset))
                .replace("__FIXED__", fixed != null ? "true" : "false").replace("__START__", String.valueOf(start)).replace("__END__", String.valueOf(end))
                .replace("__T0__", String.valueOf(t0)).replace("__LEAD__", ClockSync.leader ? "true" : "false");
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
        if (shown == this) shown = null;
        if (player == null) return;
        root.removeView(player);
        player.destroy();
        player = null;
    }

    private static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
}
