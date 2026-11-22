package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import im.manus.universalhost.IKeyHandler;
import im.manus.universalhost.IMotionHandler;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth-пульт (например, для TikTok): записывает его кнопки и назначает на них медиафункции.
 *
 * Как назначить: в настройках модуля нажать нужную функцию, затем нажать кнопку на пульте. Кнопка запоминается.
 * Два источника сигналов:
 *  - клавиши (IKeyHandler, любой Android): пульт, который представляется клавиатурой/медиа-кнопками;
 *  - указка (IMotionHandler, только Android 14+, по включению): пульт, который представляется мышью —
 *    это те самые «курсор появился и дёргается». Свайпы и клики указки превращаются в кнопки.
 * Назначенные кнопки по умолчанию поглощаются, то есть TikTok и другие приложения их не получают.
 */
public class BtRemoteModule implements IPlugin, ISettingsProvider, IKeyHandler, IMotionHandler {

    private static final String NAME = "BtRemote";
    private static final String PREFS = "module_" + NAME;

    /** id функции, подпись. */
    private static final String[][] FNS = {
            {"play_pause", "⏯ Пауза / воспроизведение"},
            {"next", "⏭ Следующий трек"},
            {"prev", "⏮ Предыдущий трек"},
            {"ff", "⏩ Перемотка вперёд"},
            {"rw", "⏪ Перемотка назад"},
            {"vol_up", "🔊 Громкость +"},
            {"vol_down", "🔉 Громкость −"},
            {"mute", "🔇 Без звука (вкл/выкл)"},
            {"stop", "⏹ Стоп"},
    };

    private static final int MOTION_MASK = InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_MOUSE_RELATIVE
            | InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_TRACKBALL;
    private static final long GESTURE_GAP_MS = 150;
    private static final long LEARN_TIMEOUT_MS = 20000;

    private Context ctx;
    private Handler main;
    private SharedPreferences prefs;

    /** подпись кнопки -> id функции */
    private final Map<String, String> bound = new ConcurrentHashMap<String, String>();
    private volatile String learnFn;

    // журнал для монитора
    private final Object lock = new Object();
    private final long[] stamps = new long[200];
    private int stampN;
    private String lastEvent = "—";

    // накопление свайпа указки
    private float gdx, gdy;
    private float lastX = Float.NaN, lastY = Float.NaN;
    private String gDev = "";

    private final Runnable gestureEnd = new Runnable() {
        @Override public void run() { finishGesture(); }
    };
    private final Runnable learnTimeout = new Runnable() {
        @Override public void run() {
            if (learnFn != null) {
                learnFn = null;
                toast("Назначение отменено: кнопка не нажата");
            }
        }
    };

    @Override public String getName() { return NAME; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Bluetooth-пульт: запись кнопок и назначение медиафункций";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        ctx = context;
        main = new Handler(Looper.getMainLooper());
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadBindings();
        applyMotionCapture(prefs.getBoolean("motion", false));
    }

    @Override
    public void stop() {
        applyMotionCapture(false);
        if (main != null) {
            main.removeCallbacks(gestureEnd);
            main.removeCallbacks(learnTimeout);
        }
        learnFn = null;
        ctx = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    /**
     * execute({"command": "monitor:<key>"}) — данные для монитора в настройках;
     * execute({"command": "run:<id функции>"}) — выполнить функцию (для других модулей).
     */
    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("command");
        if (!(cmd instanceof String)) return null;
        String c = (String) cmd;
        if (c.startsWith("monitor:")) return monitor();
        if (c.startsWith("run:")) {
            run(c.substring(4));
            return "ok";
        }
        return null;
    }

    // ------------------------------------------------------------------ клавиши

    @Override
    public boolean onKeyEvent(KeyEvent e) {
        if (ctx == null || prefs == null) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME
                || code == KeyEvent.KEYCODE_APP_SWITCH) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int rep = e.getRepeatCount();
        String sig = "K|" + code + "|" + devName(e.getDevice());

        String learn = learnFn;
        if (learn != null) {
            // пока ждём кнопку, поглощаем всё, чтобы пульт не нажал что-то в приложении
            if (down && rep == 0) {
                note(sig);
                finishLearn(learn, sig);
            }
            return true;
        }
        if (down && rep == 0) note(sig);

        String fn = bound.get(sig);
        if (fn == null) return false;
        if (down && (rep == 0 || "vol_up".equals(fn) || "vol_down".equals(fn))) run(fn);
        return prefs.getBoolean("swallow", true);
    }

    // ------------------------------------------------------------------ указка (Android 14+)

    @Override
    public void onMotionEvent(MotionEvent e) {
        if (ctx == null || prefs == null || main == null) return;
        int src = e.getSource();
        if ((src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return;   // стики геймпада — не наше

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE: {
                if ((src & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE) {
                    gdx += e.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
                    gdy += e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
                } else {
                    float x = e.getX(), y = e.getY();
                    if (!Float.isNaN(lastX)) {
                        gdx += x - lastX;
                        gdy += y - lastY;
                    }
                    lastX = x;
                    lastY = y;
                }
                gDev = devName(e.getDevice());
                main.removeCallbacks(gestureEnd);
                main.postDelayed(gestureEnd, GESTURE_GAP_MS);
                break;
            }
            case MotionEvent.ACTION_BUTTON_PRESS: {
                int b = e.getActionButton();
                String name = b == MotionEvent.BUTTON_PRIMARY ? "CLICK"
                        : b == MotionEvent.BUTTON_SECONDARY ? "RCLICK" : "BTN" + b;
                trigger("M|" + name + "|" + devName(e.getDevice()));
                break;
            }
            case MotionEvent.ACTION_SCROLL: {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
                String d = Math.abs(v) >= Math.abs(h) ? (v > 0 ? "SCROLL_UP" : "SCROLL_DOWN")
                        : (h > 0 ? "SCROLL_RIGHT" : "SCROLL_LEFT");
                if (v != 0 || h != 0) trigger("M|" + d + "|" + devName(e.getDevice()));
                break;
            }
            default:
                break;
        }
    }

    /** Пауза в движении указки: решаем, был ли это свайп, и в какую сторону. */
    private void finishGesture() {
        float ax = Math.abs(gdx), ay = Math.abs(gdy);
        int min = prefs != null ? prefs.getInt("swipe_min", 24) : 24;
        if (Math.max(ax, ay) >= min) {
            String d = ax > ay ? (gdx > 0 ? "RIGHT" : "LEFT") : (gdy > 0 ? "DOWN" : "UP");
            trigger("M|SWIPE_" + d + "|" + gDev);
        } else if (Math.max(ax, ay) > 0) {
            synchronized (lock) {
                lastEvent = "указка дрогнула: " + Math.round(gdx) + ", " + Math.round(gdy) + " px (меньше порога)";
            }
        }
        gdx = 0;
        gdy = 0;
    }

    /** Общий вход для сигналов указки: запись кнопки, если ждём её, иначе выполнение назначенной функции. */
    private void trigger(String sig) {
        note(sig);
        String learn = learnFn;
        if (learn != null) {
            finishLearn(learn, sig);
            return;
        }
        String fn = bound.get(sig);
        if (fn != null) run(fn);
    }

    /** Включает или выключает перехват указки у службы (Android 14+), не трогая биты других модулей. */
    private void applyMotionCapture(boolean on) {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            Class<?> c = Class.forName("im.manus.universalhost.CoreAccessibilityService");
            Object comp = c.getField("Companion").get(null);
            Object inst = comp.getClass().getMethod("getInstance").invoke(comp);
            if (!(inst instanceof AccessibilityService)) return;
            AccessibilityService svc = (AccessibilityService) inst;
            AccessibilityServiceInfo info = svc.getServiceInfo();
            if (info == null) return;
            Method get = AccessibilityServiceInfo.class.getMethod("getMotionEventSources");
            Method set = AccessibilityServiceInfo.class.getMethod("setMotionEventSources", int.class);
            int cur = ((Integer) get.invoke(info)).intValue();
            int next = on ? (cur | MOTION_MASK) : (cur & ~MOTION_MASK);
            if (next != cur) {
                set.invoke(info, Integer.valueOf(next));
                svc.setServiceInfo(info);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ назначение

    private void startLearn(String fn) {
        if (label(fn) == null || main == null) return;
        learnFn = fn;
        main.removeCallbacks(learnTimeout);
        main.postDelayed(learnTimeout, LEARN_TIMEOUT_MS);
        toast("Нажмите кнопку на пульте для: " + label(fn) + " (20 с)");
    }

    private void finishLearn(String fn, String sig) {
        learnFn = null;
        if (main != null) main.removeCallbacks(learnTimeout);
        SharedPreferences.Editor ed = prefs.edit();
        for (String[] f : FNS) {
            if (sig.equals(prefs.getString("bind_" + f[0], null))) ed.remove("bind_" + f[0]);   // одна кнопка — одна функция
        }
        ed.putString("bind_" + fn, sig);
        ed.apply();
        loadBindings();
        toast("Назначено: " + label(fn) + " ← " + describe(sig));
    }

    private void loadBindings() {
        bound.clear();
        for (String[] f : FNS) {
            String sig = prefs.getString("bind_" + f[0], null);
            if (sig != null) bound.put(sig, f[0]);
        }
    }

    // ------------------------------------------------------------------ медиафункции

    private void run(String fn) {
        Context c = ctx;
        if (c == null || fn == null) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if ("play_pause".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            else if ("next".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_NEXT);
            else if ("prev".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            else if ("ff".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            else if ("rw".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_REWIND);
            else if ("stop".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_STOP);
            else if ("vol_up".equals(fn))
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            else if ("vol_down".equals(fn))
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            else if ("mute".equals(fn))
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    // ------------------------------------------------------------------ монитор

    private void note(String sig) {
        synchronized (lock) {
            stamps[stampN % stamps.length] = SystemClock.uptimeMillis();
            stampN++;
            lastEvent = describe(sig);
        }
    }

    private Map<String, Object> monitor() {
        Map<String, Object> m = new HashMap<String, Object>();
        int[] buckets = new int[30];            // события по секундам за последние 30 секунд
        String last;
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            for (long t : stamps) {
                if (t == 0) continue;
                long age = now - t;
                if (age >= 0 && age < 30000) buckets[29 - (int) (age / 1000)]++;
            }
            last = lastEvent;
        }
        String learn = learnFn;
        m.put("values", buckets);
        m.put("value", last);
        m.put("status", learn != null
                ? "⏺ Нажмите кнопку на пульте для: " + label(learn)
                : "Назначено кнопок: " + bound.size() + " из " + FNS.length);
        return m;
    }

    // ------------------------------------------------------------------ настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        SharedPreferences p = schemaPrefs();
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("🎛 Пульт"));
        l.add(SettingItem.monitor("live", "📡 Что присылает пульт",
                "Нажмите кнопку на пульте: последнее событие и активность за 30 секунд"));
        l.add(SettingItem.section("🎯 Назначение: нажмите функцию, затем кнопку на пульте"));
        for (String[] f : FNS) {
            String sig = p == null ? null : p.getString("bind_" + f[0], null);
            l.add(SettingItem.action("learn_" + f[0], f[1],
                    sig == null ? "Не назначено" : "Сейчас: " + describe(sig)));
        }
        l.add(SettingItem.action("unbind_all", "🗑 Сбросить все назначения", "Пульт снова работает как обычно"));
        l.add(SettingItem.section("⚙ Параметры"));
        l.add(SettingItem.toggle("swallow", "🙈 Не пускать нажатия дальше",
                "Назначенная кнопка не доходит до приложений (например, не листает TikTok)", true));
        l.add(SettingItem.toggle("motion", "🖱 Ловить указку пульта (Android 14+)",
                "Свайпы и клики пульта-«мыши» становятся кнопками. Пока включено, обычная мышь в других приложениях не работает",
                false));
        l.add(SettingItem.slider("swipe_min", "📏 Минимальный свайп указки",
                "Сколько пикселей должна пройти указка, чтобы считаться свайпом", 4, 300, 2, 24));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (key == null || prefs == null) return;
        if (key.startsWith("learn_")) {
            startLearn(key.substring(6));
        } else if ("unbind_all".equals(key)) {
            SharedPreferences.Editor ed = prefs.edit();
            for (String[] f : FNS) ed.remove("bind_" + f[0]);
            ed.apply();
            loadBindings();
            toast("Все назначения сброшены");
        } else if ("motion".equals(key)) {
            boolean on = prefs.getBoolean("motion", false);
            applyMotionCapture(on);
            if (on && Build.VERSION.SDK_INT < 34) toast("Указка работает только на Android 14 и новее");
        }
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        return null;        // хост нарисует монограмму
    }

    // ------------------------------------------------------------------ вспомогательное

    /** Настройки для экрана: модуль может быть ещё не запущен, поэтому контекст берём у приложения. */
    private SharedPreferences schemaPrefs() {
        try {
            Context c = ctx;
            if (c == null) {
                Class<?> at = Class.forName("android.app.ActivityThread");
                c = (Context) at.getMethod("currentApplication").invoke(null);
            }
            return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String devName(InputDevice d) {
        if (d == null || d.getName() == null) return "";
        return d.getName().replace('|', '/');
    }

    private static String label(String fn) {
        for (String[] f : FNS) if (f[0].equals(fn)) return f[1];
        return null;
    }

    /** Подпись кнопки человеческим языком: «NEXTSONG · BT Remote» или «свайп вверх · BT Mouse». */
    private static String describe(String sig) {
        String[] p = sig.split("\\|", 3);
        if (p.length < 2) return sig;
        String dev = p.length > 2 && p[2].length() > 0 ? " · " + p[2] : "";
        if ("K".equals(p[0])) {
            try {
                String s = KeyEvent.keyCodeToString(Integer.parseInt(p[1]));
                return (s.startsWith("KEYCODE_") ? s.substring(8) : s) + dev;
            } catch (NumberFormatException ex) {
                return sig;
            }
        }
        String g = p[1];
        if ("CLICK".equals(g)) g = "клик";
        else if ("RCLICK".equals(g)) g = "правый клик";
        else if ("SWIPE_UP".equals(g)) g = "свайп вверх";
        else if ("SWIPE_DOWN".equals(g)) g = "свайп вниз";
        else if ("SWIPE_LEFT".equals(g)) g = "свайп влево";
        else if ("SWIPE_RIGHT".equals(g)) g = "свайп вправо";
        else if ("SCROLL_UP".equals(g)) g = "колесо вверх";
        else if ("SCROLL_DOWN".equals(g)) g = "колесо вниз";
        else if ("SCROLL_LEFT".equals(g)) g = "колесо влево";
        else if ("SCROLL_RIGHT".equals(g)) g = "колесо вправо";
        return g + dev;
    }

    private void toast(final String text) {
        final Context c = ctx;
        if (c == null || main == null) return;
        main.post(new Runnable() {
            @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
        });
    }
}
