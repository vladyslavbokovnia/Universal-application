package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Панель недавних приложений (порт AppRecent 2.2 в DEX-модуль).
 *
 * - полоса иконок сверху, вертикальный «pull» раскрывает сетку 3 колонки;
 * - раскрытая сетка открывается сразу прокрученной к последним приложениям (без видимой прокрутки);
 * - фон панели полностью прозрачный, прозрачность задаётся для иконок;
 * - долгое нажатие на иконку открывает круговое меню (предыдущая позиция / скрыть / сортировка /
 *   вернуть скрытые / о приложении);
 * - индикатор заряда снизу и боковая ручка справа: тап — показать/скрыть панель, долгое нажатие —
 *   раскрыть сетку (если узкая полоса была скрыта, после сворачивания она снова скрывается),
 *   свайп — прокрутка.
 */
public class RecentAppsPanelModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_RecentAppsPanel";   // настройки, которые пишет хост
    private static final long EVENT_DEBOUNCE_MS = 80L;

    // значения по умолчанию совпадают со схемой настроек ниже
    private SharedPreferences cfg;
    private int iconDp = 128;
    private int columns = 3;
    private int expandedRows = 3;
    private String expandedAnchorPackage;
    private int fadeAlpha = 128;     // прозрачность нижнего края иконки (0 — нет, 255 — полная)
    private int iconAlpha = 0;       // прозрачность всех иконок (0 — непрозрачные, 255 — невидимые)
    private long menuTimeoutMs = 6000L;
    private boolean pullEnabled = true;
    private boolean edgeEnabled = true;
    private boolean batteryEnabled = true;
    private boolean stretchCustomImages = true;
    private int customCropTop = 5;
    private int customCropBottom = 5;
    private LruCache<String, Bitmap> customCache;

    private Context ctx;
    private WindowManager wm;
    private Handler handler;
    private ExecutorService bg;
    private Store store;
    private Repo repo;
    private LruCache<String, Drawable.ConstantState> iconCache;
    private volatile boolean active;
    private int generation;

    private PullLayout panel;
    private HorizontalScrollView hScroll;
    private ScrollView vScroll;
    private LinearLayout hItems;
    private LinearLayout vItems;
    private WindowManager.LayoutParams panelLp;
    private int stripH;
    private boolean expanded;
    private boolean panelShown = true;
    private boolean restoreHidden;      // сетку раскрыли из скрытого состояния: после сворачивания полоса снова скрывается
    private boolean pendingSnap;
    private int snapTarget;
    private List<Entry> entries = new ArrayList<Entry>();
    private String renderedKey = "";

    private BatteryBar battery;
    private View edgeHandle;
    private View overlay;
    private boolean receiversRegistered;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "RecentAppsPanel"; }
    @Override public int getVersion() { return 5; }
    @Override public String getDescription() {
        return "Панель недавних приложений: круговое меню, скрытие, раскрытие сеткой";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        ctx = context;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        bg = Executors.newSingleThreadExecutor();
        cfg = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadConfig();
        store = new Store(ctx, cfg);
        repo = new Repo(ctx, store);
        iconCache = new LruCache<String, Drawable.ConstantState>(300);
        customCache = new LruCache<String, Bitmap>(64);
        expanded = false;
        panelShown = true;
        restoreHidden = false;
        renderedKey = "";
        active = true;
        try {
            buildPanel();
            if (batteryEnabled) buildBattery();
            if (edgeEnabled) buildEdgeHandle();
            registerReceivers();
            refresh(true);
        } catch (Throwable t) {
            Toast.makeText(ctx, "RecentAppsPanel: не удалось запустить панель", Toast.LENGTH_LONG).show();
            stop();
        }
    }

    @Override
    public void stop() {
        active = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (bg != null) { bg.shutdownNow(); bg = null; }
        if (ctx != null && receiversRegistered) {
            try { ctx.unregisterReceiver(batteryReceiver); } catch (Throwable ignored) { }
            try { ctx.unregisterReceiver(packageReceiver); } catch (Throwable ignored) { }
            receiversRegistered = false;
        }
        dismissOverlay();
        removeWindow(panel);
        removeWindow(battery);
        removeWindow(edgeHandle);
        panel = null; hScroll = null; vScroll = null; hItems = null; vItems = null;
        battery = null; edgeHandle = null;
        entries = new ArrayList<Entry>();
        pendingSnap = false;
        restoreHidden = false;
    }

    @Override
    public Object execute(Map<String, ?> data) {
        refresh(true);
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        if (!active || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (pkg.equals(ctx.getPackageName())) return;      // собственные окна хоста
        dismissOverlay();                                  // смена окна закрывает меню
        if (!repo.isLaunchable(pkg) || pkg.equals(imePackage())) return;  // systemui, клавиатура и т.п.
        store.setActive(pkg);
        handler.removeCallbacks(refreshFromEvent);
        handler.postDelayed(refreshFromEvent, EVENT_DEBOUNCE_MS);
    }

    private final Runnable refreshFromEvent = new Runnable() {
        @Override public void run() { refresh(true); }
    };

    private String imePackage() {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (v == null) return null;
            int i = v.indexOf('/');
            return i > 0 ? v.substring(0, i) : v;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- настройки (экран в хосте)

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void loadConfig() {
        iconDp = clamp(cfg.getInt("icon_dp", 128), 64, 192);
        columns = clamp(cfg.getInt("columns", 3), 2, 5);
        expandedRows = clamp(cfg.getInt("expanded_rows", 3), 1, 5);
        fadeAlpha = clamp(cfg.getInt("fade_alpha", 128), 0, 255);
        iconAlpha = clamp(cfg.getInt("icon_alpha", 0), 0, 255);
        menuTimeoutMs = clamp(cfg.getInt("menu_timeout", 6), 2, 15) * 1000L;
        pullEnabled = cfg.getBoolean("pull_enabled", true);
        edgeEnabled = cfg.getBoolean("edge_handle", true);
        batteryEnabled = cfg.getBoolean("battery_bar", true);
        stretchCustomImages = cfg.getBoolean("stretch_custom_images", true);
        customCropTop = clamp(cfg.getInt("custom_crop_top", 5), 0, 20);
        customCropBottom = clamp(cfg.getInt("custom_crop_bottom", 5), 0, 20 - customCropTop);
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Вид панели"));
        l.add(SettingItem.slider("icon_dp", "Ширина иконки", "dp", 64, 192, 8, 128));
        l.add(SettingItem.slider("columns", "Колонок в раскрытой панели", "", 2, 5, 1, 3));
        l.add(SettingItem.slider("expanded_rows", "Строк в раскрытой панели", "по умолчанию 3", 1, 5, 1, 3));
        l.add(SettingItem.slider("fade_alpha", "Прозрачность нижнего края иконок", "", 0, 255, 5, 128));
        l.add(SettingItem.slider("icon_alpha", "Прозрачность всех иконок", "", 0, 255, 5, 0));
        l.add(SettingItem.section("Поведение"));
        l.add(SettingItem.toggle("pull_enabled", "Раскрытие свайпом вниз", "Список приложений сеткой", true));
        l.add(SettingItem.toggle("edge_handle", "Боковая ручка", "Тап показывает или скрывает панель, свайп прокручивает, долгое нажатие раскрывает", true));
        l.add(SettingItem.toggle("battery_bar", "Полоса заряда", "Тонкая полоса внизу экрана", true));
        l.add(SettingItem.slider("menu_timeout", "Автозакрытие меню, сек", "", 2, 15, 1, 6));
        l.add(SettingItem.choice("sort_mode", "Сортировка", "", Arrays.asList("По использованию", "По времени установки"), 0));
        l.add(SettingItem.section("Картинки"));
        l.add(SettingItem.appImages("app_icons", "Замена иконок приложений", "Выберите приложение и картинку"));
        l.add(SettingItem.toggle("stretch_custom_images", "Растягивать картинки на ширину ячейки", "Только заменённые значки в узкой панели; обычные значки сохраняют прежнюю маску", true));
        l.add(SettingItem.slider("custom_crop_top", "Обрезка заменённой картинки сверху", "%", 0, 20, 1, 5));
        l.add(SettingItem.slider("custom_crop_bottom", "Обрезка заменённой картинки снизу", "%", 0, 20, 1, 5));
        l.add(SettingItem.section("Скрытые приложения"));
        l.add(SettingItem.action("unhide_all", "Вернуть все скрытые приложения", ""));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (ctx == null || !active || handler == null) return;
        if ("unhide_all".equals(key)) { store.clearHidden(); refresh(false); return; }
        if ("app_icons".equals(key)) { customCache.evictAll(); renderedKey = ""; refresh(false); return; }
        if ("sort_mode".equals(key)) { refresh(true); return; }
        // остальные параметры меняют размеры окон и набор окон — проще перезапустить панель
        Context c = ctx;
        stop();
        init(c);
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = size / 96f;
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.WHITE);
        p.setStrokeWidth(4f * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawRoundRect(new RectF(10 * s, 20 * s, 86 * s, 52 * s), 8 * s, 8 * s, p);     // полоса панели
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {                                                       // иконки в полосе
            c.drawRoundRect(new RectF((18 + i * 22) * s, 27 * s, (34 + i * 22) * s, 45 * s), 3 * s, 3 * s, p);
        }
        p.setStyle(Paint.Style.STROKE);                                                     // стрелка «раскрыть»
        c.drawLine(48 * s, 62 * s, 48 * s, 80 * s, p);
        c.drawLine(38 * s, 72 * s, 48 * s, 82 * s, p);
        c.drawLine(58 * s, 72 * s, 48 * s, 82 * s, p);
        return b;
    }

    /** Картинка, которой пользователь заменил иконку приложения (или null). */
    private Bitmap customBitmap(String pkg) {
        if (cfg == null || customCache == null) return null;
        String path = cfg.getString("app_icons:" + pkg, null);
        if (path == null) return null;
        File f = new File(path);
        if (!f.isFile()) return null;
        String key = path + ":" + f.lastModified();
        Bitmap b = customCache.get(key);
        if (b != null) return b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            b = BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            b = null;
        }
        if (b != null) customCache.put(key, b);
        return b;
    }

    // ---------------------------------------------------------------- окна

    private int overlayType() {
        return (ctx instanceof AccessibilityService)
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private void removeWindow(View v) {
        if (v == null || wm == null) return;
        try { wm.removeView(v); } catch (Throwable ignored) { }
    }

    private int dp(int v) { return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f); }

    private int statusBarHeightPx() {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id != 0 ? ctx.getResources().getDimensionPixelSize(id) * 2 : dp(48);
    }

    private void buildPanel() {
        stripH = statusBarHeightPx();
        panel = new PullLayout(ctx, new PullHost() {
            @Override public boolean isExpanded() { return expanded; }
            @Override public boolean listAtBottom() { return vScroll == null || !vScroll.canScrollVertically(1); }
            @Override public boolean pullEnabled() { return pullEnabled; }
            @Override public void onPull(boolean expand) {
                if (expand) restoreHidden = false;      // тянули за видимую полосу — вернёмся к ней
                toggleExpand(expand);
            }
        });
        panel.setBackgroundColor(Color.TRANSPARENT);

        hScroll = new HorizontalScrollView(ctx);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hScroll.setBackgroundColor(Color.TRANSPARENT);
        hItems = new LinearLayout(ctx);
        hItems.setOrientation(LinearLayout.HORIZONTAL);
        hItems.setGravity(Gravity.CENTER_VERTICAL);
        hScroll.addView(hItems, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        vScroll = new ScrollView(ctx);
        vScroll.setVerticalScrollBarEnabled(false);
        vScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        vScroll.setBackgroundColor(Color.TRANSPARENT);
        vItems = new LinearLayout(ctx);
        vItems.setOrientation(LinearLayout.VERTICAL);
        vItems.setBackgroundColor(Color.TRANSPARENT);   // фон раскрытой панели всегда полностью прозрачный
        vScroll.addView(vItems, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        vScroll.setVisibility(View.GONE);
        // Раскрытая сетка становится видимой только после того, как встала в нужную позицию.
        vScroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (pendingSnap && expanded && (b - t) == snapTarget) finishSnap();
            }
        });

        panel.addView(hScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(vScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, stripH, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP;
        wm.addView(panel, panelLp);
    }

    /** Окно панели всегда ровно по размеру содержимого: под прозрачной зоной касания не перехватываются. */
    private void setPanelWindow(int height, boolean watchOutside) {
        if (panel == null || panelLp == null) return;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (watchOutside) flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        if (!panelShown) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (panelLp.height == height && panelLp.flags == flags) return;
        panelLp.height = height;
        panelLp.flags = flags;
        try { wm.updateViewLayout(panel, panelLp); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- список

    private void refresh(final boolean snapStart) {
        if (!active || bg == null) return;
        final int gen = ++generation;
        try {
            bg.execute(new Runnable() {
                @Override public void run() {
                    List<Entry> loaded;
                    try { loaded = repo.load(); } catch (Throwable t) { loaded = new ArrayList<Entry>(); }
                    for (Entry e : loaded) {
                        if (iconCache.get(e.pkg) == null) {
                            try {
                                Drawable d = ctx.getPackageManager().getApplicationIcon(e.pkg);
                                if (d.getConstantState() != null) iconCache.put(e.pkg, d.getConstantState());
                            } catch (Throwable ignored) { }
                        }
                        customBitmap(e.pkg);      // картинки пользователя декодируем не на главном потоке
                    }
                    final List<Entry> result = loaded;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (active && gen == generation) applyList(result, snapStart);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private void applyList(List<Entry> loaded, boolean snapStart) {
        if (panel == null) return;
        entries = loaded;
        StringBuilder sb = new StringBuilder(expanded ? "E:" : "C:");
        for (Entry e : loaded) sb.append(e.pkg).append(',');
        String key = sb.toString();
        if (key.equals(renderedKey)) {
            if (snapStart && !expanded && hScroll != null) hScroll.scrollTo(0, 0);
            return;
        }
        renderedKey = key;
        if (expanded) buildExpanded(); else buildCollapsed(snapStart);
    }

    private void buildCollapsed(boolean snapStart) {
        vScroll.setVisibility(View.GONE);
        hScroll.setVisibility(View.VISIBLE);
        pendingSnap = false;
        hItems.removeAllViews();
        int w = dp(iconDp);
        for (Entry e : entries) hItems.addView(createAppView(e, fadeAlpha, true), new LinearLayout.LayoutParams(w, stripH));
        setPanelWindow(stripH, false);
        if (snapStart) hScroll.scrollTo(0, 0);   // самое последнее приложение слева, мгновенно
    }

    private void buildExpanded() {
        hScroll.setVisibility(View.GONE);
        vScroll.setVisibility(View.INVISIBLE);   // покажем после позиционирования
        vItems.removeAllViews();
        int rowH = dp(iconDp);
        List<Entry> ordered = new ArrayList<Entry>(entries);
        Collections.reverse(ordered);            // последние приложения — внизу
        int n = ordered.size();
        int rows = Math.max(1, (n + columns - 1) / columns);
        int maxRows = Math.max(1, ctx.getResources().getDisplayMetrics().heightPixels / Math.max(1, rowH));
        int shownRows = Math.min(Math.min(rows, expandedRows), maxRows);
        // Помещаем приложение, на котором остановилась узкая лента, в нижний ряд сетки.
        if (expandedAnchorPackage != null && n > 0) {
            int anchor = -1;
            for (int i = 0; i < n; i++) if (expandedAnchorPackage.equals(ordered.get(i).pkg)) { anchor = i; break; }
            if (anchor >= 0) {
                int target = Math.min(n - 1, Math.max(0, (shownRows - 1) * columns));
                Entry tmp = ordered.get(target);
                ordered.set(target, ordered.get(anchor));
                ordered.set(anchor, tmp);
            }
        }
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setWeightSum(columns);
            int fade = (r == rows - 1) ? fadeAlpha : 0;   // градиент только у нижнего ряда
            for (int c = 0; c < columns; c++) {
                int idx = r * columns + c;
                if (idx < n) row.addView(createAppView(ordered.get(idx), fade, false), new LinearLayout.LayoutParams(0, rowH, 1f));
                else row.addView(new View(ctx), new LinearLayout.LayoutParams(0, rowH, 1f));
            }
            vItems.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        }
        snapTarget = shownRows * rowH;
        pendingSnap = true;
        setPanelWindow(snapTarget, true);
        handler.removeCallbacks(forceSnap);
        handler.postDelayed(forceSnap, 160L);    // страховка, если размер окна не совпал
    }

    private final Runnable forceSnap = new Runnable() {
        @Override public void run() { if (pendingSnap) finishSnap(); }
    };

    /** Мгновенно ставит сетку на последние приложения и только потом показывает её. */
    private void finishSnap() {
        pendingSnap = false;
        handler.removeCallbacks(forceSnap);
        if (vScroll == null || vItems == null) return;
        int max = vItems.getHeight() - vScroll.getHeight();
        vScroll.scrollTo(0, Math.max(0, max));
        vScroll.setVisibility(View.VISIBLE);
    }

    private void toggleExpand(boolean expand) {
        if (!active || expanded == expand) return;
        dismissOverlay();
        if (expand) {
            expandedAnchorPackage = null;
            if (hScroll != null && !entries.isEmpty()) {
                int cellW = Math.max(1, dp(iconDp));
                int index = Math.max(0, Math.min(entries.size() - 1, hScroll.getScrollX() / cellW));
                expandedAnchorPackage = entries.get(index).pkg;
            }
        }
        expanded = expand;
        renderedKey = "";
        applyList(entries, false);
        if (!expand) expandedAnchorPackage = null;
        if (!expand && restoreHidden) {
            // узкой полосы до раскрытия не было — возвращаем скрытый режим
            restoreHidden = false;
            panelShown = false;
            if (panel != null) panel.setVisibility(View.GONE);
            setPanelWindow(stripH, false);      // добавит FLAG_NOT_TOUCHABLE
        }
    }

    /** Долгое нажатие на боковую ручку: раскрыть сетку из любого состояния или свернуть её. */
    private void longPressExpand() {
        if (!active || panel == null) return;
        if (expanded) { toggleExpand(false); return; }
        if (!panelShown) {
            panelShown = true;
            restoreHidden = true;               // после сворачивания полоса снова скроется
            panel.setVisibility(View.VISIBLE);
            setPanelWindow(stripH, false);      // снимет FLAG_NOT_TOUCHABLE
            refresh(true);
        } else {
            restoreHidden = false;              // полоса была видна — вернёмся к ней
        }
        toggleExpand(true);
    }

    private View createAppView(final Entry entry, int fade, boolean narrowStrip) {
        Bitmap cb = customBitmap(entry.pkg);
        Drawable.ConstantState cs = cb == null ? iconCache.get(entry.pkg) : null;
        Drawable icon = null;
        if (cb != null) {
            icon = new BitmapDrawable(ctx.getResources(), cb);
        } else if (cs != null) {
            icon = cs.newDrawable(ctx.getResources());
        } else {
            try {
                Drawable d = ctx.getPackageManager().getApplicationIcon(entry.pkg);
                if (d.getConstantState() != null) iconCache.put(entry.pkg, d.getConstantState());
                icon = d;
            } catch (Throwable ignored) { }
        }
        final AlphaIconView v = new AlphaIconView(ctx, icon, fade, iconAlpha, cb != null,
                narrowStrip && cb != null && stretchCustomImages,
                narrowStrip && cb != null ? customCropTop : 0,
                narrowStrip && cb != null ? customCropBottom : 0);
        v.setContentDescription(entry.label);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { launch(entry); }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { showMenu(v, entry); return true; }
        });
        return v;
    }

    private void launch(Entry entry) {
        dismissOverlay();
        if (expanded) toggleExpand(false);
        store.launched(entry.pkg);
        store.setActive(entry.pkg);
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(entry.pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (Throwable ignored) { }
        }
        handler.removeCallbacks(refreshFromEvent);
        handler.postDelayed(refreshFromEvent, 250L);
    }

    // ---------------------------------------------------------------- круговое меню

    private static final int K_PREV = 0, K_HIDE = 1, K_SORT = 2, K_SHOW = 3, K_INFO = 4;

    private void showMenu(View anchor, final Entry entry) {
        if (!active) return;
        dismissOverlay();

        List<Integer> kinds = new ArrayList<Integer>();
        kinds.add(K_PREV);
        kinds.add(K_HIDE);
        kinds.add(K_SORT);
        if (!store.hidden().isEmpty()) kinds.add(K_SHOW);
        kinds.add(K_INFO);

        int n = kinds.size();
        final int[] kindArr = new int[n];
        final Runnable[] actions = new Runnable[n];
        for (int i = 0; i < n; i++) {
            final int k = kinds.get(i);
            kindArr[i] = k;
            actions[i] = new Runnable() {
                @Override public void run() { runMenuAction(k, entry); }
            };
        }

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        float ax = loc[0] + anchor.getWidth() / 2f;
        float ab = loc[1] + anchor.getHeight();

        Drawable.ConstantState cs = iconCache.get(entry.pkg);
        Drawable centerIcon = cs != null ? cs.newDrawable(ctx.getResources()) : null;

        final RingMenu menu = new RingMenu(ctx, kindArr, actions, centerIcon, ax, ab, new Runnable() {
            @Override public void run() { dismissOverlay(); }
        }, new Runnable() {
            @Override public void run() { armMenuTimeout(); }
        });
        WindowManager.LayoutParams lp = fullScreenParams();
        try {
            wm.addView(menu, lp);
            overlay = menu;
            armMenuTimeout();
        } catch (Throwable t) {
            overlay = null;
        }
    }

    private WindowManager.LayoutParams fullScreenParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private final Runnable dismissTask = new Runnable() {
        @Override public void run() { dismissOverlay(); }
    };

    private void armMenuTimeout() {
        handler.removeCallbacks(dismissTask);
        handler.postDelayed(dismissTask, menuTimeoutMs);
    }

    /** Единственная точка закрытия меню и списка скрытых: таймер, тап мимо, смена окна, stop(). */
    private void dismissOverlay() {
        if (handler != null) handler.removeCallbacks(dismissTask);
        View v = overlay;
        overlay = null;
        if (v != null) removeWindow(v);
    }

    private void runMenuAction(int kind, Entry entry) {
        if (!active) return;
        switch (kind) {
            case K_PREV: {
                store.movePrevious(entry.pkg, entries);
                List<Entry> reordered = store.applyManualOrder(entries);
                applyList(reordered, false);
                refresh(false);
                break;
            }
            case K_HIDE: {
                store.setHidden(entry.pkg, true);
                List<Entry> left = new ArrayList<Entry>();
                for (Entry e : entries) if (!e.pkg.equals(entry.pkg)) left.add(e);
                applyList(left, false);          // сразу убираем иконку, не дожидаясь фонового обновления
                refresh(false);
                break;
            }
            case K_SORT: {
                store.toggleSort();
                refresh(true);
                break;
            }
            case K_SHOW: {
                showHiddenList();
                break;
            }
            case K_INFO: {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + entry.pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(i); } catch (Throwable ignored) { }
                break;
            }
            default:
                break;
        }
    }

    private void showHiddenList() {
        final List<Entry> hidden = repo.hiddenEntries();
        if (hidden.isEmpty()) {
            Toast.makeText(ctx, "Скрытых приложений нет", Toast.LENGTH_SHORT).show();
            return;
        }
        dismissOverlay();
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(Color.argb(96, 0, 0, 0));
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.BLACK);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setClickable(true);                 // тап по карточке не закрывает список

        TextView title = new TextView(ctx);
        title.setText("Скрытые приложения — нажмите, чтобы вернуть");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(4), 0, dp(4), dp(8));
        card.addView(title);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final Entry e : hidden) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try { iv.setImageDrawable(ctx.getPackageManager().getApplicationIcon(e.pkg)); } catch (Throwable ignored) { }
            row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView tv = new TextView(ctx);
            tv.setText(e.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(18f);
            tv.setPadding(dp(12), 0, 0, 0);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.setHidden(e.pkg, false);
                    dismissOverlay();
                    refresh(false);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        }
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, clp);
        // ограничиваем высоту списка ~60% экрана
        ViewGroup.LayoutParams svlp = sv.getLayoutParams();
        int need = hidden.size() * dp(56);
        svlp.height = Math.min(need, (int) (screenH * 0.6f));
        sv.setLayoutParams(svlp);

        try {
            wm.addView(scrim, fullScreenParams());
            overlay = scrim;
            armMenuTimeout();
        } catch (Throwable t) {
            overlay = null;
        }
    }

    // ---------------------------------------------------------------- боковая ручка и батарея

    private void buildEdgeHandle() {
        final View handle = new View(ctx);
        handle.setBackgroundColor(Color.TRANSPARENT);
        final GestureDetector detector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) { longPressExpand(); }
            @Override public boolean onSingleTapUp(MotionEvent e) { toggleVisibility(); return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent cur, float dx, float dy) {
                if (!panelShown) return true;
                if (expanded) { if (vScroll != null) vScroll.scrollBy(0, (int) dy * 2); }
                else if (hScroll != null) hScroll.scrollBy(-(int) dy * 2, 0);
                return true;
            }
        });
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) { return detector.onTouchEvent(ev); }
        });
        edgeHandle = handle;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(18), WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END;
        wm.addView(handle, lp);
    }

    private void toggleVisibility() {
        if (panel == null) return;
        if (expanded) { toggleExpand(false); return; }     // тап по ручке при раскрытой сетке сворачивает её
        panelShown = !panelShown;
        restoreHidden = false;
        dismissOverlay();
        panel.setVisibility(panelShown ? View.VISIBLE : View.GONE);
        if (panelShown) {
            expanded = false;
            renderedKey = "";
            setPanelWindow(stripH, false);
            refresh(true);
        } else {
            setPanelWindow(stripH, false);       // добавит FLAG_NOT_TOUCHABLE
        }
    }

    private void buildBattery() {
        battery = new BatteryBar(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(4), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        wm.addView(battery, lp);
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent == null || battery == null) return;
            int level = intent.getIntExtra("level", -1);
            int scale = intent.getIntExtra("scale", -1);
            int status = intent.getIntExtra("status", BatteryManager.BATTERY_STATUS_UNKNOWN);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            battery.setPulsing(charging);
            if (level >= 0 && scale > 0) battery.setLevel(level / (float) scale);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!active) return;
            repo.invalidate();
            iconCache.evictAll();
            refresh(false);
        }
    };

    private void registerReceivers() {
        IntentFilter bf = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = ctx.registerReceiver(batteryReceiver, bf, Context.RECEIVER_NOT_EXPORTED);
            ctx.registerReceiver(packageReceiver, pf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sticky = ctx.registerReceiver(batteryReceiver, bf);
            ctx.registerReceiver(packageReceiver, pf);
        }
        receiversRegistered = true;
        if (sticky != null) batteryReceiver.onReceive(ctx, sticky);
    }

    // ---------------------------------------------------------------- модель

    private static final class Entry {
        final String pkg;
        final String label;
        final long lastUsed;
        final long installTime;
        Entry(String pkg, String label, long lastUsed, long installTime) {
            this.pkg = pkg; this.label = label; this.lastUsed = lastUsed; this.installTime = installTime;
        }
    }

    /** Настройки модуля хранятся отдельно от настроек хоста. */
    private static final class Store {
        private final SharedPreferences p;    // служебное состояние панели
        private final SharedPreferences s;    // настройки из хоста
        Store(Context c, SharedPreferences settings) {
            p = c.getSharedPreferences("recent_panel", Context.MODE_PRIVATE);
            s = settings;
        }
        void clearHidden() { p.edit().remove("hidden").commit(); }

        /** Возвращает копию: набор из SharedPreferences менять нельзя. */
        Set<String> hidden() {
            Set<String> s = p.getStringSet("hidden", null);
            return s == null ? new HashSet<String>() : new HashSet<String>(s);
        }
        void setHidden(String pkg, boolean value) {
            Set<String> s = hidden();
            if (value) s.add(pkg); else s.remove(pkg);
            p.edit().putStringSet("hidden", s).commit();   // синхронно: список читается сразу после
        }
        boolean sortInstall() { return s.getInt("sort_mode", 0) == 1; }
        void toggleSort() { s.edit().putInt("sort_mode", sortInstall() ? 0 : 1).apply(); }
        String active() { return p.getString("active", null); }
        void setActive(String pkg) { p.edit().putString("active", pkg).apply(); }

        List<String> overrides() {
            List<String> out = new ArrayList<String>();
            String raw = p.getString("order", "");
            if (raw == null || raw.isEmpty()) return out;
            for (String s : raw.split("\\|")) if (!s.isEmpty() && !out.contains(s)) out.add(s);
            return out;
        }
        void setOverrides(List<String> order) {
            StringBuilder sb = new StringBuilder();
            for (String s : order) { if (sb.length() > 0) sb.append('|'); sb.append(s); }
            p.edit().putString("order", sb.toString()).apply();
        }

        /** Приложения без ручной позиции идут первыми (в порядке сортировки), затем зафиксированные. */
        List<Entry> applyManualOrder(List<Entry> in) {
            List<String> ov = overrides();
            if (ov.isEmpty()) return in;
            Map<String, Entry> by = new HashMap<String, Entry>();
            for (Entry e : in) by.put(e.pkg, e);
            Set<String> pinned = new HashSet<String>();
            List<Entry> manual = new ArrayList<Entry>();
            for (String pkg : ov) {
                Entry e = by.get(pkg);
                if (e != null && pinned.add(pkg)) manual.add(e);
            }
            List<Entry> out = new ArrayList<Entry>();
            for (Entry e : in) if (!pinned.contains(e.pkg)) out.add(e);
            out.addAll(manual);
            return out;
        }
        void movePrevious(String pkg, List<Entry> current) {
            List<String> order = new ArrayList<String>();
            for (Entry e : applyManualOrder(current)) order.add(e.pkg);
            int i = order.indexOf(pkg);
            if (i > 0) { order.remove(i); order.add(i - 1, pkg); setOverrides(order); }
        }
        void launched(String pkg) {
            List<String> ov = overrides();
            if (ov.remove(pkg)) setOverrides(ov);
        }
    }

    /** Список запускаемых приложений кэшируется и сбрасывается только при установке/удалении. */
    private static final class Repo {
        private final Context c;
        private final Store store;
        private volatile Map<String, Entry> base;

        Repo(Context c, Store store) { this.c = c; this.store = store; }

        void invalidate() { base = null; }

        boolean isLaunchable(String pkg) {
            Map<String, Entry> b = base;
            return b == null || b.containsKey(pkg);
        }

        private synchronized Map<String, Entry> scan() {
            if (base != null) return base;
            PackageManager pm = c.getPackageManager();
            Set<String> pkgs = new LinkedHashSet<String>();
            Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(li, PackageManager.MATCH_ALL)) {
                pkgs.add(ri.activityInfo.packageName);
            }
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
                if (ai.enabled && pm.getLaunchIntentForPackage(ai.packageName) != null) pkgs.add(ai.packageName);
            }
            pkgs.add(c.getPackageName());
            Map<String, Entry> map = new LinkedHashMap<String, Entry>();
            for (String pkg : pkgs) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    map.put(pkg, new Entry(pkg, String.valueOf(pm.getApplicationLabel(ai)), 0L, pi.firstInstallTime));
                } catch (Exception ignored) { }
            }
            base = map;
            return map;
        }

        List<Entry> hiddenEntries() {
            Map<String, Entry> b = base != null ? base : scan();
            List<Entry> out = new ArrayList<Entry>();
            for (String pkg : store.hidden()) {
                Entry e = b.get(pkg);
                if (e != null) out.add(e);
            }
            Collections.sort(out, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b2) { return String.CASE_INSENSITIVE_ORDER.compare(a.label, b2.label); }
            });
            return out;
        }

        List<Entry> load() {
            Map<String, Entry> b = base != null ? base : scan();
            Set<String> hidden = store.hidden();
            long now = System.currentTimeMillis();
            Map<String, Long> usage = new HashMap<String, Long>();
            try {
                UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY,
                            now - 365L * 24L * 60L * 60L * 1000L, now);
                    if (stats != null) {
                        for (UsageStats s : stats) {
                            Long old = usage.get(s.getPackageName());
                            long t = s.getLastTimeUsed();
                            if (old == null || t > old) usage.put(s.getPackageName(), t);
                        }
                    }
                }
            } catch (Throwable ignored) { }
            String act = store.active();
            if (act != null) usage.put(act, now);
            List<Entry> out = new ArrayList<Entry>();
            for (Entry e : b.values()) {
                if (hidden.contains(e.pkg)) continue;
                Long u = usage.get(e.pkg);
                out.add(new Entry(e.pkg, e.label, u == null ? 0L : u, e.installTime));
            }
            final boolean byInstall = store.sortInstall();
            Collections.sort(out, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b2) {
                    long x = byInstall ? a.installTime : a.lastUsed;
                    long y = byInstall ? b2.installTime : b2.lastUsed;
                    if (x != y) return x > y ? -1 : 1;
                    return String.CASE_INSENSITIVE_ORDER.compare(a.label, b2.label);
                }
            });
            return store.applyManualOrder(out);
        }
    }

    // ---------------------------------------------------------------- вью

    /** Геометрия кругового меню вынесена отдельно, чтобы её можно было проверить без Android. */
    static final class RingGeometry {
        final float[] x;
        final float[] y;
        final float ring;
        final float btn;
        final float cx;
        final float cy;

        RingGeometry(int n, float anchorX, float anchorBottom, float w, float h, float ring, float btn, float gap) {
            this.ring = ring;
            this.btn = btn;
            float extent = ring + btn + gap;
            float lo = extent;
            this.cx = clamp(anchorX, lo, Math.max(lo, w - extent));
            this.cy = clamp(anchorBottom + extent + gap, lo, Math.max(lo, h - extent));
            x = new float[n];
            y = new float[n];
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(-90.0 + i * 360.0 / n);   // первая кнопка сверху, далее по часовой
                x[i] = cx + (float) Math.cos(a) * ring;
                y[i] = cy + (float) Math.sin(a) * ring;
            }
        }

        /** Индекс ближайшей кнопки в пределах её зоны касания или -1. */
        int hit(float px, float py, float slop) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            float r = btn + slop;
            for (int i = 0; i < x.length; i++) {
                float dx = px - x[i], dy = py - y[i];
                float d = dx * dx + dy * dy;
                if (d <= r * r && d < bestD) { bestD = d; best = i; }
            }
            return best;
        }

        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    }

    private static final class RingMenu extends View {
        private final int[] kinds;
        private final Runnable[] actions;
        private final Drawable centerIcon;
        private final float anchorX, anchorBottom;
        private final Runnable onDismiss;
        private final Runnable onTouch;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private RingGeometry geo;
        private int pressed = -1;

        RingMenu(Context c, int[] kinds, Runnable[] actions, Drawable centerIcon,
                 float anchorX, float anchorBottom, Runnable onDismiss, Runnable onTouch) {
            super(c);
            this.kinds = kinds;
            this.actions = actions;
            this.centerIcon = centerIcon;
            this.anchorX = anchorX;
            this.anchorBottom = anchorBottom;
            this.onDismiss = onDismiss;
            this.onTouch = onTouch;
            this.density = c.getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.argb(72, 0, 0, 0));    // лёгкое затемнение, без анимаций
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            geo = new RingGeometry(kinds.length, anchorX, anchorBottom, w, h, 76f * density, 28f * density, 6f * density);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (geo == null) return;
            // центр: иконка выбранного приложения
            float cr = 30f * density;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.WHITE);
            canvas.drawCircle(geo.cx, geo.cy, cr, fill);
            if (centerIcon != null) {
                float in = cr * 0.72f;
                centerIcon.setBounds((int) (geo.cx - in), (int) (geo.cy - in), (int) (geo.cx + in), (int) (geo.cy + in));
                centerIcon.draw(canvas);
            }
            for (int i = 0; i < kinds.length; i++) {
                boolean p = i == pressed;
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(p ? Color.WHITE : Color.BLACK);
                canvas.drawCircle(geo.x[i], geo.y[i], geo.btn, fill);
                line.setStrokeWidth(2f * density);
                line.setColor(p ? Color.BLACK : Color.WHITE);
                canvas.drawCircle(geo.x[i], geo.y[i], geo.btn - density, line);
                drawGlyph(canvas, kinds[i], geo.x[i], geo.y[i], geo.btn / 18f, p ? Color.BLACK : Color.WHITE);
            }
        }

        private void drawGlyph(Canvas c, int kind, float x, float y, float s, int color) {
            c.save();
            c.translate(x, y);
            c.scale(s, s);
            line.setColor(color);
            line.setStrokeWidth(2.2f * density / s);
            RectF r = new RectF();
            switch (kind) {
                case K_PREV:
                    c.drawLine(10, 0, -7, 0, line);
                    c.drawLine(-7, 0, 1, -8, line);
                    c.drawLine(-7, 0, 1, 8, line);
                    break;
                case K_HIDE:
                    r.set(-12, -7, 12, 7); c.drawOval(r, line);
                    c.drawLine(-10, -10, 10, 10, line);
                    break;
                case K_SHOW:
                    r.set(-12, -7, 12, 7); c.drawOval(r, line);
                    c.drawCircle(0, 0, 3.5f, line);
                    break;
                case K_SORT:
                    c.drawLine(-9, -8, 9, -8, line);
                    c.drawLine(-9, 0, 5, 0, line);
                    c.drawLine(-9, 8, 0, 8, line);
                    break;
                case K_INFO:
                    c.drawCircle(0, 0, 8f, line);
                    c.drawCircle(0, 0, 3f, line);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        c.drawLine((float) Math.cos(a) * 9.5f, (float) Math.sin(a) * 9.5f,
                                (float) Math.cos(a) * 12f, (float) Math.sin(a) * 12f, line);
                    }
                    break;
                default:
                    break;
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (geo == null) return true;
            float slop = 10f * density;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onTouch.run();
                    pressed = geo.hit(e.getX(), e.getY(), slop);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int h = geo.hit(e.getX(), e.getY(), slop);
                    if (h != pressed) { pressed = h; invalidate(); }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int h = geo.hit(e.getX(), e.getY(), slop);
                    pressed = -1;
                    onDismiss.run();                         // меню закрываем всегда
                    if (h >= 0) actions[h].run();            // действие — только если попали в кнопку
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    pressed = -1;
                    onDismiss.run();
                    return true;
                default:
                    return true;
            }
        }
    }

    private static final class AlphaIconView extends View {
        private final Drawable icon;
        private final int fade;          // прозрачность нижнего края
        private final int opacity;       // непрозрачность иконки целиком: 255 - «прозрачность всех иконок»
        private final boolean custom;
        private final boolean stretchToCell;
        private final int cropTopPercent;
        private final int cropBottomPercent;
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float radius;

        AlphaIconView(Context c, Drawable icon, int fade, int iconAlpha, boolean custom, boolean stretchToCell,
                      int cropTopPercent, int cropBottomPercent) {
            super(c);
            this.icon = icon;
            this.fade = fade;
            this.opacity = 255 - Math.max(0, Math.min(255, iconAlpha));
            this.custom = custom;
            this.stretchToCell = stretchToCell;
            this.cropTopPercent = Math.max(0, cropTopPercent);
            this.cropBottomPercent = Math.max(0, cropBottomPercent);
            this.radius = 12f * c.getResources().getDisplayMetrics().density;
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rect.set(0, 0, w, h);
            path.reset();
            path.addRoundRect(rect, new float[]{0, 0, 0, 0, radius, radius, radius, radius}, Path.Direction.CW);
            if (fade > 0) {
                mask.setShader(new LinearGradient(0, 0, 0, h, Color.WHITE,
                        Color.argb(255 - fade, 255, 255, 255), Shader.TileMode.CLAMP));
            }
            if (icon != null) {
                float boxW = custom ? w : w / .80f;
                float boxH = h;
                float iw = icon.getIntrinsicWidth() > 0 ? icon.getIntrinsicWidth() : boxW;
                float ih = icon.getIntrinsicHeight() > 0 ? icon.getIntrinsicHeight() : boxH;
                float scale = custom ? Math.min(boxW / iw, boxH / ih)      // своя картинка целиком, без обрезки
                        : Math.max(boxW / iw, boxH / ih);   // системную иконку слегка обрезаем по краям
                float dw = stretchToCell ? boxW : iw * scale;
                float dh = stretchToCell ? boxH : ih * scale;
                float left = (w - dw) / 2f, top = (h - dh) / 2f;
                if (custom && stretchToCell) {
                    int totalCrop = Math.min(80, cropTopPercent + cropBottomPercent);
                    if (totalCrop > 0) {
                        dh = boxH * 100f / (100f - totalCrop);
                        top = -boxH * cropTopPercent / (100f - totalCrop);
                    }
                }
                icon.setBounds((int) left, (int) top, (int) (left + dw), (int) (top + dh));
                icon.setAlpha(255);      // общая прозрачность применяется слоем в onDraw
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (icon == null || opacity <= 0) return;
            if (fade == 0 && opacity == 255) {
                int sc = canvas.save();
                canvas.clipPath(path);
                icon.draw(canvas);
                canvas.restoreToCount(sc);
            } else {
                // слой рисуется целиком и только потом блендится с нужной прозрачностью,
                // поэтому слои адаптивной иконки не просвечивают друг через друга
                int sc = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), opacity);
                canvas.clipPath(path);
                icon.draw(canvas);
                if (fade > 0) canvas.drawRect(rect, mask);
                canvas.restoreToCount(sc);
            }
        }
    }

    private interface PullHost {
        boolean isExpanded();
        boolean listAtBottom();
        boolean pullEnabled();
        void onPull(boolean expand);
    }

    /** Корневой контейнер: ловит вертикальный «pull» раньше горизонтальной прокрутки. */
    private static final class PullLayout extends LinearLayout {
        private final PullHost host;
        private final int slop;
        private float downX, downY;
        private boolean captured;

        PullLayout(Context c, PullHost host) {
            super(c);
            this.host = host;
            this.slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2;
        }

        /** 0 — игнорировать, 1 — раскрыть, 2 — свернуть. */
        private int direction(float dy) {
            if (!host.isExpanded()) return (dy > 0f && host.pullEnabled()) ? 1 : 0;
            if (dy >= 0f) return 0;
            return host.listAtBottom() ? 2 : 0;    // свайп вверх сворачивает, только когда листать вниз некуда
        }

        private boolean probe(MotionEvent ev) {
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            if (Math.abs(dy) < slop || Math.abs(dy) <= Math.abs(dx)) return false;
            final int dir = direction(dy);
            if (dir == 0) return false;
            captured = true;
            post(new Runnable() { @Override public void run() { host.onPull(dir == 1); } });
            return true;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY(); captured = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    return captured || probe(ev);
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY(); captured = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!captured) probe(ev);
                    break;
                case MotionEvent.ACTION_OUTSIDE:
                    if (host.isExpanded()) post(new Runnable() { @Override public void run() { host.onPull(false); } });
                    break;
                default:
                    captured = false;
                    break;
            }
            return true;
        }
    }

    private static final class BatteryBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level;
        private boolean pulsing;
        private final Runnable tick = new Runnable() {
            @Override public void run() { if (pulsing) { invalidate(); postDelayed(this, 250L); } }
        };

        BatteryBar(Context c) { super(c); }

        void setLevel(float v) { level = Math.max(0f, Math.min(1f, v)); invalidate(); }

        void setPulsing(boolean v) {
            if (v == pulsing) return;
            pulsing = v;
            removeCallbacks(tick);
            if (v) postDelayed(tick, 250L);          // редкое обновление вместо перерисовки на каждом кадре
            invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            float whiteAlpha = 1f;
            if (pulsing) {
                float t = (SystemClock.uptimeMillis() % 2400L) / 2400f;
                float p = t < 0.5f ? t * 2f : (1f - t) * 2f;
                whiteAlpha = 1f - 0.88f * (p * p * (3f - 2f * p));
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (whiteAlpha * 255f));
            canvas.drawRect(0f, 0f, getWidth() * level, getHeight(), paint);
            paint.setAlpha(255);
        }
    }
}
