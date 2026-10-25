package sa.yourtv.v2.screensaver;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The update's download on the screen, in Arabic: «جارٍ تنزيل التحديث (٢ من ٢) · ٤٥٪» over a bar that fills from
 * the right, at the bottom of whatever is on the TV. Gone once the download ends (Android's own «تثبيت» follows).
 */
final class UpdateProgress {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View box;
    private static TextView line;
    private static View fill;
    private static FrameLayout track;

    private UpdateProgress() {}

    /** pct 0..100; part and parts: which file of how many */
    static void show(Context ctx, int part, int parts, int pct) {
        final Context c = ctx.getApplicationContext();
        main.post(() -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
            if (box == null) build(c);
            if (box == null) return;
            line.setText("⬇️ جارٍ تنزيل التحديث" + (parts > 1 ? " (" + arabic(part) + " من " + arabic(parts) + ")" : "") + " · " + arabic(pct) + "٪");
            track.post(() -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                lp.width = track.getWidth() * Math.max(0, Math.min(100, pct)) / 100;
                fill.setLayoutParams(lp);
            });
        });
    }

    static void hide() {
        main.post(() -> {
            if (box == null) return;
            try { ((WindowManager) box.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(box); } catch (Exception ignored) { }
            box = null;
        });
    }

    private static void build(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        b.setPadding((int) (22 * d), (int) (14 * d), (int) (22 * d), (int) (16 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * d);
        bg.setColor(0xF0081410);
        bg.setStroke((int) (2 * d), 0xFFE8C46F);
        b.setBackground(bg);
        line = new TextView(c);
        line.setTextColor(0xFFFFF3D6);
        line.setTypeface(Typeface.DEFAULT_BOLD);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        line.setGravity(Gravity.START);
        b.addView(line);
        track = new FrameLayout(c);
        GradientDrawable tr = new GradientDrawable();
        tr.setCornerRadius(6 * d);
        tr.setColor(0x33F6EFE0);
        track.setBackground(tr);
        fill = new View(c);
        GradientDrawable fl = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xFFF3D48A, 0xFFDCB86F});
        fl.setCornerRadius(6 * d);
        fill.setBackground(fl);
        track.addView(fill, new FrameLayout.LayoutParams(0, -1, Gravity.RIGHT));   // fills from the right, as Arabic reads
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams((int) (460 * d), (int) (12 * d));
        tp.topMargin = (int) (10 * d);
        b.addView(track, tp);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (40 * d);
        try { ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(b, lp); box = b; } catch (Exception ignored) { }
    }

    static String arabic(int n) {
        StringBuilder out = new StringBuilder();
        for (char ch : String.valueOf(n).toCharArray()) out.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return out.toString();
    }
}
