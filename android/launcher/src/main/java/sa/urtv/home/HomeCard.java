package sa.yourtv.v2.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Collections;

/**
 * The optional card on the home screen: the screensaver's page in card-only mode (#widget=1), showing
 * the panel picked in the screensaver's settings («بطاقة على الشاشة الرئيسية»). Off by default; while
 * off no WebView is created. Settings come from the screensaver app's ConfigProvider (same signing key).
 */
final class HomeCard {
    private static final Uri CONFIG = Uri.parse("content://sa.yourtv.v2.screensaver.config");
    private static final String FONTS = "https://raw.githubusercontent.com/jolyan20/YourTV-V2/master/assets/fonts/";

    private final Activity activity;
    private final ViewGroup parent;
    private final FrameLayout.LayoutParams params;
    private WebView web;
    private long pageAt = -1;
    private String url;

    HomeCard(Activity activity, ViewGroup parent, FrameLayout.LayoutParams params) {
        this.activity = activity;
        this.parent = parent;
        this.params = params;
    }

    /** onResume: follow the current settings; a changed choice gets a fresh page. */
    void refresh() {
        String want = readUrl();
        long at = new java.io.File(activity.getFilesDir(), "web/tv.html").lastModified();
        if (!want.equals(url) || at != pageAt) { destroy(); create(want); pageAt = at; }   // new settings or a new page
        web.onResume();
        web.resumeTimers();
    }

    /** onPause: no timers or network while an app is in front. */
    void navigate(int direction) { if (web != null) web.evaluateJavascript("window.navigateCard && navigateCard(" + direction + ")", null); }
    void focusCards(boolean on) { if (web != null) web.evaluateJavascript("document.querySelector('.week').classList.toggle('remote-focus'," + on + ")", null); }
    void pause() {
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    void destroy() {
        if (web == null) return;
        parent.removeView(web);
        web.destroy();
        web = null;
        url = null;
    }

    /** the screensaver's page with the screensaver's own settings: the home screen and the screensaver are one */
    private String readUrl() {
        String page = page();
        try (Cursor c = activity.getContentResolver().query(CONFIG, null, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.getString(0) == null) return page + "#home=1";
            return page + "#" + c.getString(0) + "&home=1";
        } catch (RuntimeException e) {
            return page + "#home=1";
        }
    }

    /** the page as the screensaver last updated it from GitHub (copied over), else the one built into this app */
    private String page() {
        java.io.File out = new java.io.File(activity.getFilesDir(), "web/tv.html");
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(android.net.Uri.withAppendedPath(CONFIG, "page"))) {
            if (in != null) {
                java.io.File tmp = new java.io.File(out.getParentFile(), "page.tmp");
                if (!out.getParentFile().isDirectory()) out.getParentFile().mkdirs();
                try (java.io.OutputStream o = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                if (tmp.length() > 30_000 && tmp.renameTo(out)) return "file://" + out.getAbsolutePath();
            }
        } catch (Exception ignored) { }   // the screensaver has not updated it yet: the built-in page
        return out.isFile() ? "file://" + out.getAbsolutePath() : "file:///android_asset/web/tv.html";
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void create(String u) {
        web = new WebView(activity);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            // the page asks GitHub for its fonts; answer from the APK
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String s = r.getUrl().toString();
                if (s.startsWith("https://raw.githubusercontent.com/jolyan20/YourTV-V2/master/assets/backgrounds/")) {
                    String name = s.substring(s.lastIndexOf('/') + 1);
                    if (name.matches("[A-Za-z0-9_-]+\\.jpg")) {
                        try {
                            return new WebResourceResponse("image/jpeg", null, 200, "OK",
                                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                                activity.getAssets().open("wallpapers/" + name));
                        } catch (java.io.IOException ignored) { }
                    }
                }
                if (s.startsWith(FONTS)) {
                    String name = s.substring(FONTS.length());
                    if (name.matches("[A-Za-z0-9_-]+\\.woff2")) {
                        try {
                            return new WebResourceResponse("font/woff2", null, 200, "OK",
                                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                                activity.getAssets().open("fonts/" + name));
                        } catch (java.io.IOException ignored) { }
                    }
                }
                return super.shouldInterceptRequest(v, r);
            }
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // same as the screensaver: the page calls its data APIs directly from file://
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        parent.addView(web, 0, params);
        url = u;
        web.loadUrl(u);
    }
}
