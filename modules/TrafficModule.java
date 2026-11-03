package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Traffic — цифры месячного мобильного трафика (ГБ) отдельным модулем (раньше были частью AnTTS).
 *
 * Одно окно-наложение: не перехватывает касания, обведено тёмным контуром (видно и на светлом фоне).
 * Начертание — тонкое по умолчанию; толщина цифр и контура настраивается.
 * Старые значения (угол, размер, день начала периода) берутся из настроек AnTTS, пока не заданы здесь.
 */
public class TrafficModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_Traffic";
    private static final String OLD_PREFS = "module_AnTTS";
    private static final long REFRESH_MS = 2000L;
    private static final long RAISE_DELAY_MS = 3000L;      // после AnTTS (2 с) и других модулей — цифры сверху

    private SharedPreferences cfg;
    private SharedPreferences oldCfg;
    private int corner;
    private int sizeSp = 27;
    private int startDay = 1;
    private int weight;            // 0 тонкие, 1 лёгкие, 2 обычные
    private int outlinePercent = 8;

    private AccessibilityService svc;
    private Handler main;
    private Digits digits;
    private String text = "";
    private volatile boolean active;

    @Override public String getName() { return "Traffic"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() { return "Цифры мобильного трафика за месяц"; }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        if (!(context instanceof AccessibilityService)) return;
        svc = (AccessibilityService) context;
        main = new Handler(Looper.getMainLooper());
        cfg = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        oldCfg = svc.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);
        loadConfig();
        active = true;
        try {
            digits = new Digits();
            digits.show();
            text = monthlyText();
            digits.setText(text);
            main.postDelayed(refreshTask, REFRESH_MS);
            main.postDelayed(raiseTask, RAISE_DELAY_MS);
        } catch (Throwable t) {
            stop();
        }
    }

    @Override
    public void stop() {
        active = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        if (digits != null) { digits.hide(); digits = null; }
        svc = null;
    }

    @Override
    public Object execute(Map<String, ?> data) {
        if (active && digits != null) {
            text = monthlyText();
            digits.setText(text);
        }
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (!active || digits == null) return;
            String t = monthlyText();
            if (!t.equals(text)) {
                text = t;
                digits.setText(t);
            }
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private final Runnable raiseTask = new Runnable() {
        @Override public void run() { if (active && digits != null) digits.raise(); }
    };

    // ---------------------------------------------------------------- настройки

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int intPref(String key, int def) {
        if (cfg.contains(key)) return cfg.getInt(key, def);
        return oldCfg.getInt(key, def);
    }

    private void loadConfig() {
        corner = clamp(intPref("digits_corner", 0), 0, 5);
        sizeSp = clamp(intPref("digits_size", 27), 12, 48);
        startDay = clamp(intPref("traffic_start_day", 1), 1, 31);
        weight = clamp(cfg.getInt("digits_weight", 0), 0, 2);
        outlinePercent = clamp(cfg.getInt("digits_outline", 8), 2, 20);
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Цифры мобильного трафика"));
        l.add(SettingItem.choice("digits_corner", "Где показывать", "",
                Arrays.asList("Сверху по центру", "Сверху слева", "Сверху справа",
                        "Снизу по центру", "Снизу слева", "Снизу справа"), 0));
        l.add(SettingItem.slider("digits_size", "Размер цифр", "sp", 12, 48, 2, 27));
        l.add(SettingItem.choice("digits_weight", "Толщина цифр", "",
                Arrays.asList("Тонкие", "Лёгкие", "Обычные"), 0));
        l.add(SettingItem.slider("digits_outline", "Толщина контура", "%", 2, 20, 1, 8));
        l.add(SettingItem.slider("traffic_start_day", "День начала учётного периода", "", 1, 31, 1, 1));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (svc == null || !active) return;
        Context c = svc;
        stop();
        init(c);
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = size / 96f;
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f * s);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path up = new Path();                                   // стрелка вверх
        up.moveTo(34 * s, 70 * s); up.lineTo(34 * s, 26 * s);
        up.moveTo(22 * s, 38 * s); up.lineTo(34 * s, 26 * s); up.lineTo(46 * s, 38 * s);
        c.drawPath(up, p);
        Path down = new Path();                                 // стрелка вниз
        down.moveTo(62 * s, 26 * s); down.lineTo(62 * s, 70 * s);
        down.moveTo(50 * s, 58 * s); down.lineTo(62 * s, 70 * s); down.lineTo(74 * s, 58 * s);
        c.drawPath(down, p);
        return b;
    }

    // ---------------------------------------------------------------- трафик

    private int dp(int v) { return (int) (v * svc.getResources().getDisplayMetrics().density); }

    private long periodStartMillis() {
        long now = System.currentTimeMillis();
        Calendar cycle = Calendar.getInstance();
        cycle.setTimeInMillis(now);
        cycle.set(Calendar.DAY_OF_MONTH, Math.min(startDay, cycle.getActualMaximum(Calendar.DAY_OF_MONTH)));
        cycle.set(Calendar.HOUR_OF_DAY, 0);
        cycle.set(Calendar.MINUTE, 0);
        cycle.set(Calendar.SECOND, 0);
        cycle.set(Calendar.MILLISECOND, 0);
        if (now < cycle.getTimeInMillis()) cycle.add(Calendar.MONTH, -1);
        return cycle.getTimeInMillis();
    }

    /** Мобильный трафик (приём + передача) за учётный период, ГБ; «—», если нет доступа к статистике. */
    private String monthlyText() {
        try {
            NetworkStatsManager m = (NetworkStatsManager) svc.getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStats.Bucket b = m.querySummaryForDevice(
                    ConnectivityManager.TYPE_MOBILE, null, periodStartMillis(), System.currentTimeMillis());
            long bytes = Math.max(0L, b.getRxBytes()) + Math.max(0L, b.getTxBytes());
            return String.format(Locale.getDefault(), "%.2f", bytes / 1e9);
        } catch (Throwable t) {
            return "\u2014";
        }
    }

    // ---------------------------------------------------------------- окно

    private final class Digits {
        private final WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        private final OutlinedText view = new OutlinedText(svc, sizeSp, weight, outlinePercent);
        private WindowManager.LayoutParams lp;
        private boolean shown;

        void show() {
            if (shown) return;
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            int vertical = corner >= 3 ? Gravity.BOTTOM : Gravity.TOP;
            int horizontal;
            switch (corner % 3) {
                case 1: horizontal = Gravity.START; break;
                case 2: horizontal = Gravity.END; break;
                default: horizontal = Gravity.CENTER_HORIZONTAL; break;
            }
            lp.gravity = vertical | horizontal;
            lp.x = (corner % 3 == 0) ? 0 : dp(8);
            lp.y = dp(2);
            wm.addView(view, lp);
            shown = true;
        }

        void setText(String t) { view.setText(t); }

        /** Окно убирается и добавляется заново: в своём слое последнее добавленное лежит выше остальных. */
        void raise() {
            if (!shown || lp == null) return;
            try {
                wm.removeView(view);
                wm.addView(view, lp);
            } catch (Throwable ignored) { }
        }

        void hide() {
            if (!shown) return;
            shown = false;
            try { wm.removeView(view); } catch (Throwable ignored) { }
        }
    }

    /** Белые цифры с тонким тёмным контуром. */
    private static final class OutlinedText extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float pad;
        private String text = "";

        OutlinedText(Context c, float sizeSp, int weight, int outlinePercent) {
            super(c);
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, c.getResources().getDisplayMetrics());
            Typeface tf = typeface(weight);
            fill.setTypeface(tf);
            fill.setTextSize(px);
            fill.setColor(Color.WHITE);
            stroke.setTypeface(tf);
            stroke.setTextSize(px);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1.5f, px * outlinePercent / 100f));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            pad = stroke.getStrokeWidth();
        }

        private static Typeface typeface(int weight) {
            if (Build.VERSION.SDK_INT >= 28) {
                int w = weight == 0 ? 100 : (weight == 1 ? 300 : 500);
                return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), w, false);
            }
            String family = weight == 0 ? "sans-serif-thin" : (weight == 1 ? "sans-serif-light" : "sans-serif-medium");
            return Typeface.create(family, Typeface.NORMAL);
        }

        void setText(String t) {
            if (t == null) t = "";
            if (t.equals(text)) return;
            text = t;
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            Paint.FontMetrics fm = fill.getFontMetrics();
            int w = (int) Math.ceil(fill.measureText(text) + 2 * pad);
            int h = (int) Math.ceil(fm.descent - fm.ascent + 2 * pad);
            setMeasuredDimension(Math.max(w, 1), Math.max(h, 1));
        }

        @Override protected void onDraw(Canvas canvas) {
            Paint.FontMetrics fm = fill.getFontMetrics();
            float y = pad - fm.ascent;
            canvas.drawText(text, pad, y, stroke);
            canvas.drawText(text, pad, y, fill);
        }
    }
}
