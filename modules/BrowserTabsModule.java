package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;
import im.manus.universalhost.ShizukuResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Панель вкладок при открытии браузера.
 *
 * Когда на экран выходит один из выбранных браузеров, модуль нажимает кнопку вкладок.
 * Браузеры выбираются в настройках из списка установленных приложений.
 * Положение кнопки задаётся в настройках: либо нажатием пальцем («Указать кнопку вкладок»),
 * либо ползунками. Хранится долями экрана (в тысячных), у всех выбранных браузеров оно одинаковое.
 * Нажатие делает служба специальных возможностей (dispatchGesture), запасной путь: input tap через Shizuku.
 *
 * Режим «Только при холодном старте» (нужен Shizuku): возраст процесса браузера берётся из ps.
 * Если процесс моложе заданного порога, браузера не было в памяти и его только что запустили.
 * Без Shizuku режим не может определить состояние процесса, поэтому модуль срабатывает при каждом выходе браузера на экран.
 */
public class BrowserTabsModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_BrowserTabs";
    private static final String DEF_BROWSERS = "";          // пока пользователь ничего не выбрал, модуль не срабатывает
    private static final long REFIRE_GUARD_MS = 2500;
    private static final long CALIBRATE_START_DELAY_MS = 5000;
    private static final long CALIBRATE_TIMEOUT_MS = 30000;

    private Context ctx;
    private Handler main;
    private volatile AccessibilityService service;
    private final Set<String> ignored = new HashSet<String>();
    private String lastPkg;
    private long lastFired;

    private View overlay;
    private WindowManager overlayWm;
    private final Runnable overlayTimeout = new Runnable() {
        @Override public void run() { hideOverlay(); }
    };

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "BrowserTabs"; }
    @Override public int getVersion() { return 3; }
    @Override public String getDescription() {
        return "Открывает панель вкладок при запуске выбранных браузеров";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        ctx = context;
        main = new Handler(Looper.getMainLooper());
        service = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;

        ignored.clear();
        ignored.add("android");
        ignored.add("com.android.systemui");
        ignored.add(context.getPackageName());
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo i : imm.getInputMethodList()) ignored.add(i.getPackageName());
            }
        } catch (Throwable ignoredErr) { }
    }

    @Override
    public void stop() {
        hideOverlay();
        service = null;
        ctx = null;
    }

    @Override
    public Object execute(Map<String, ?> data) {
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService svc) {
        service = svc;
        if (ctx == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (ignored.contains(pkg)) return;               // шторка, клавиатура, сам хост

        String prev = lastPkg;
        lastPkg = pkg;
        if (pkg.equals(prev)) return;                    // окна внутри того же приложения
        if (!browsers().contains(pkg)) return;

        long now = SystemClock.uptimeMillis();
        if (now - lastFired < REFIRE_GUARD_MS) return;
        lastFired = now;
        schedule(svc, pkg);
    }

    // ---------------------------------------------------------------- логика

    private void schedule(final AccessibilityService svc, final String pkg) {
        SharedPreferences p = prefs();
        if (p == null) return;
        final boolean coldOnly = p.getBoolean("cold_only", true);
        final int maxAge = p.getInt("cold_max_age", 4);
        final int delay = p.getInt("delay_ms", 600);
        final long t0 = SystemClock.uptimeMillis();

        new Thread(new Runnable() {
            @Override public void run() {
                if (coldOnly && !isCold(pkg, maxAge)) return;
                long wait = delay - (SystemClock.uptimeMillis() - t0);
                final Handler h = main;
                if (h == null) return;
                h.postDelayed(new Runnable() {
                    @Override public void run() { tap(svc, pkg); }
                }, Math.max(0, wait));
            }
        }).start();
    }

    /** true, если процесс браузера запущен недавно (или состояние определить нельзя). Вызывать не из главного потока. */
    private boolean isCold(String pkg, int maxAgeSec) {
        if (!ShizukuBridge.hasPermission()) return true;
        ShizukuResult r = ShizukuBridge.exec("ps -A -o ETIME,ARGS");
        if (!r.getOk()) return true;
        long oldest = -1;
        for (String line : r.getOut().split("\n")) {
            String[] a = line.trim().split("\\s+", 2);
            if (a.length < 2 || !pkg.equals(a[1].trim())) continue;
            try {
                oldest = Math.max(oldest, parseEtime(a[0]));
            } catch (NumberFormatException ignoredErr) { }
        }
        return oldest < 0 || oldest <= maxAgeSec;
    }

    /** ETIME: [[дни-]часы:]минуты:секунды. */
    private static long parseEtime(String s) {
        long days = 0;
        String t = s;
        int d = t.indexOf('-');
        if (d > 0) {
            days = Long.parseLong(t.substring(0, d));
            t = t.substring(d + 1);
        }
        long v = 0;
        for (String x : t.split(":")) v = v * 60 + Long.parseLong(x);
        return days * 86400 + v;
    }

    /** pkg == null: нажать без проверки, что браузер на экране (кнопка «Проверить нажатие»). */
    private void tap(AccessibilityService svc, String pkg) {
        if (svc == null) return;
        SharedPreferences p = prefs();
        if (p == null) return;

        if (pkg != null) {
            AccessibilityNodeInfo root = null;
            try {
                root = svc.getRootInActiveWindow();
                CharSequence rp = root == null ? null : root.getPackageName();
                if (rp == null || !pkg.equals(rp.toString())) return;   // уже ушли из браузера
            } catch (Throwable t) {
                return;
            } finally {
                recycle(root);
            }
        }

        int[] size = realSize(svc);
        final float x = size[0] * p.getInt("tab_x", 900) / 1000f;
        final float y = size[1] * p.getInt("tab_y", 70) / 1000f;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        boolean sent = false;
        try {
            sent = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override public void onCancelled(GestureDescription gd) { shizukuTap(x, y); }
            }, null);
        } catch (Throwable ignoredErr) { }
        if (!sent) shizukuTap(x, y);
    }

    private void shizukuTap(final float x, final float y) {
        new Thread(new Runnable() {
            @Override public void run() {
                ShizukuBridge.exec("input tap " + Math.round(x) + " " + Math.round(y));
            }
        }).start();
    }

    /** Полный размер экрана в пикселях (вместе со служебными панелями): в этих координатах работают и жесты, и getRawX. */
    @SuppressWarnings("deprecation")
    private static int[] realSize(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[] { dm.widthPixels, dm.heightPixels };
    }

    private Set<String> browsers() {
        Set<String> s = new HashSet<String>();
        SharedPreferences p = prefs();
        String raw = p == null ? DEF_BROWSERS : p.getString("browsers", DEF_BROWSERS);
        for (String x : raw.split("[,;\\s]+")) {
            if (x.length() > 0) s.add(x);
        }
        return s;
    }

    private SharedPreferences prefs() {
        Context c = ctx;
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- калибровка нажатием

    private void startCalibration() {
        final AccessibilityService svc = service;
        final Handler h = main;
        if (svc == null || h == null) {
            toast("Служба специальных возможностей не работает");
            return;
        }
        toast("Откройте браузер: через 5 секунд экран затемнится, тогда нажмите на кнопку вкладок");
        h.postDelayed(new Runnable() {
            @Override public void run() { showOverlay(svc); }
        }, CALIBRATE_START_DELAY_MS);
    }

    private void showOverlay(final AccessibilityService svc) {
        hideOverlay();
        final Handler h = main;
        if (h == null) return;
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);

            FrameLayout root = new FrameLayout(svc);
            root.setBackgroundColor(0x66000000);
            TextView label = new TextView(svc);
            label.setText("Нажмите пальцем на кнопку вкладок");
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(20);
            label.setGravity(Gravity.CENTER);
            label.setPadding(48, 48, 48, 48);
            root.addView(label, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        saveTap(svc, e.getRawX(), e.getRawY());
                        hideOverlay();
                    }
                    return true;
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wm.addView(root, lp);
            overlay = root;
            overlayWm = wm;
            h.postDelayed(overlayTimeout, CALIBRATE_TIMEOUT_MS);
        } catch (Throwable t) {
            toast("Не удалось показать затемнение: " + t);
        }
    }

    private void hideOverlay() {
        Handler h = main;
        if (h != null) h.removeCallbacks(overlayTimeout);
        View v = overlay;
        WindowManager wm = overlayWm;
        overlay = null;
        overlayWm = null;
        if (v != null && wm != null) {
            try { wm.removeView(v); } catch (Throwable ignoredErr) { }
        }
    }

    private void saveTap(Context c, float rawX, float rawY) {
        SharedPreferences p = prefs();
        if (p == null) return;
        int[] s = realSize(c);
        int px = Math.max(0, Math.min(1000, Math.round(1000f * rawX / s[0])));
        int py = Math.max(0, Math.min(1000, Math.round(1000f * rawY / s[1])));
        p.edit().putInt("tab_x", px).putInt("tab_y", py).apply();
        toast("Кнопка сохранена: x=" + px + "‰, y=" + py + "‰");
    }

    // ---------------------------------------------------------------- настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Браузеры"));
        l.add(SettingItem.appPicker("browsers", "Браузеры",
                "Не выбрано: нажмите и отметьте приложения из списка", DEF_BROWSERS));
        l.add(SettingItem.toggle("cold_only", "Только при холодном старте",
                "Нужен Shizuku: срабатывает, если браузера не было в памяти. Без Shizuku срабатывает при каждом выходе браузера на экран",
                true));
        l.add(SettingItem.slider("cold_max_age", "Возраст процесса при холодном старте, с",
                "Процесс моложе этого значения считается только что запущенным", 1, 15, 1, 4));
        l.add(SettingItem.section("Кнопка вкладок"));
        l.add(SettingItem.action("calibrate", "Указать кнопку вкладок",
                "Откройте браузер, через 5 секунд экран затемнится: нажмите пальцем на кнопку вкладок"));
        l.add(SettingItem.slider("tab_x", "Горизонталь, ‰ ширины",
                "Подстройка вручную: 0 — левый край, 1000 — правый", 0, 1000, 5, 900));
        l.add(SettingItem.slider("tab_y", "Вертикаль, ‰ высоты",
                "Подстройка вручную: 0 — верх экрана, 1000 — низ", 0, 1000, 5, 70));
        l.add(SettingItem.slider("delay_ms", "Задержка после запуска, мс",
                "Сколько ждать, пока браузер нарисует интерфейс", 0, 3000, 100, 600));
        l.add(SettingItem.action("test_tap", "Проверить нажатие",
                "Через 4 секунды нажмёт в сохранённую точку: за это время откройте браузер"));
        l.add(SettingItem.action("status", "Состояние Shizuku", "Нужно только для режима холодного старта"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        final Context c = ctx;
        if (c == null) return;
        if ("calibrate".equals(key)) {
            startCalibration();
        } else if ("test_tap".equals(key)) {
            toast("Нажатие через 4 секунды");
            final Handler h = main;
            if (h == null) return;
            h.postDelayed(new Runnable() {
                @Override public void run() { tap(service, null); }
            }, 4000);
        } else if ("status".equals(key)) {
            toast(ShizukuBridge.status(c));
        }
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        return null;        // хост нарисует монограмму
    }

    private void toast(final String text) {
        final Context c = ctx;
        final Handler h = main;
        if (c == null || h == null) return;
        h.post(new Runnable() {
            @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
        });
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo n) {
        if (n == null) return;
        try { n.recycle(); } catch (Throwable ignoredErr) { }
    }
}
