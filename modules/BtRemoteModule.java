package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth-пульт (например, для TikTok): записывает его кнопки и назначает на них медиафункции.
 *
 * Сигналы читаются напрямую из ядра: через Shizuku запускается «getevent -l», и модуль видит всё, что шлёт пульт
 * (клавиши, клики и движения «мыши», колесо), даже если Android до службы специальных возможностей это не довёл.
 * Работает на любой версии Android, нужен только запущенный Shizuku с разрешением для хоста.
 *
 * Как назначить: в настройках модуля нажать нужную функцию, затем нажать кнопку на пульте. Кнопка запоминается.
 * Читать события — не значит их гасить: getevent ничего не перехватывает, поэтому родное действие пульта
 * (курсор, пролистывание в TikTok) никуда не девается.
 */
public class BtRemoteModule implements IPlugin, ISettingsProvider {

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

    private static final long GESTURE_GAP_MS = 150;
    private static final long LEARN_TIMEOUT_MS = 20000;

    private Context ctx;
    private Handler main;
    private SharedPreferences prefs;

    /** подпись кнопки -> id функции */
    private final Map<String, String> bound = new ConcurrentHashMap<String, String>();
    private volatile String learnFn;

    // чтение getevent
    private volatile boolean alive;
    private volatile Process proc;
    private Thread reader;
    private volatile String readerState = "не запущено";
    private final Map<String, String> devNames = new HashMap<String, String>();   // /dev/input/eventN -> имя устройства
    private String curPath;

    // журнал для монитора
    private final Object lock = new Object();
    private final long[] stamps = new long[200];
    private int stampN;
    private String lastEvent = "—";
    private final LinkedHashSet<String> seen = new LinkedHashSet<String>();

    // накопление движения указки
    private float gdx, gdy;
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
    private final Runnable readyCb = new Runnable() {
        @Override public void run() { startReader(); }
    };

    @Override public String getName() { return NAME; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() {
        return "Bluetooth-пульт через Shizuku: запись кнопок и назначение медиафункций";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        ctx = context;
        alive = true;
        main = new Handler(Looper.getMainLooper());
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadBindings();
        ShizukuBridge.addReadyListener(readyCb);   // Shizuku мог ещё не запуститься: стартуем, когда он будет готов
        startReader();
    }

    @Override
    public void stop() {
        alive = false;
        ShizukuBridge.removeReadyListener(readyCb);
        killProcess();
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

    // ------------------------------------------------------------------ чтение событий ядра

    private void startReader() {
        if (!alive || ctx == null) return;
        synchronized (lock) {
            if (reader != null && reader.isAlive()) return;
        }
        if (!ShizukuBridge.hasPermission()) {
            readerState = ShizukuBridge.isRunning()
                    ? "у приложения нет разрешения Shizuku"
                    : "Shizuku не запущен";
            return;                                  // readyCb запустит чтение, когда Shizuku будет готов
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "BtRemote-getevent");
        t.setDaemon(true);
        synchronized (lock) { reader = t; }
        t.start();
    }

    private void killProcess() {
        Process p = proc;
        proc = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable t) { /* уже закрыт */ }
        }
    }

    private void readLoop() {
        while (alive) {
            Process p = ShizukuBridge.startProcess("exec getevent -l");
            if (p == null) {
                readerState = "не удалось запустить getevent через Shizuku";
                return;
            }
            proc = p;
            readerState = "читаю события пульта";
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                try {
                    String line;
                    while (alive && (line = r.readLine()) != null) handleLine(line);
                } finally {
                    r.close();
                }
            } catch (Throwable t) {
                // поток закрылся — перезапустим ниже
            }
            killProcess();
            if (!alive) return;
            readerState = "getevent завершился, перезапуск";
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Разбор одной строки «getevent -l». */
    private void handleLine(String line) {
        String s = line.trim();
        if (s.length() == 0) return;
        if (s.startsWith("add device")) {
            int i = s.indexOf(": ");
            curPath = i > 0 ? s.substring(i + 2).trim() : null;
            return;
        }
        if (s.startsWith("name:")) {
            int a = s.indexOf('"'), b = s.lastIndexOf('"');
            if (curPath != null && a >= 0 && b > a) {
                synchronized (devNames) { devNames.put(curPath, s.substring(a + 1, b)); }
            }
            return;
        }
        if (!s.startsWith("/dev/input/")) return;
        int c = s.indexOf(':');
        if (c < 0) return;
        String path = s.substring(0, c);
        String[] t = s.substring(c + 1).trim().split("\\s+");
        if (t.length < 3) return;
        String type = t[0], code = t[1], val = t[2];

        if ("EV_SYN".equals(type) || "EV_MSC".equals(type) || "EV_LED".equals(type)) return;
        if ("EV_ABS".equals(type)) {
            if (!code.startsWith("ABS_MT")) markSeen(path);   // экран трогаем постоянно, его не считаем
            return;
        }

        String dev;
        synchronized (devNames) { dev = devNames.get(path); }
        if (dev == null) dev = path;
        dev = dev.replace('|', '/');

        if ("EV_KEY".equals(type)) {
            if ("BTN_TOUCH".equals(code) || code.startsWith("BTN_TOOL")) return;   // касания экрана
            markSeen(dev);
            if ("DOWN".equals(val)) {
                trigger("K|" + code + "|" + dev);
            } else if ("REPEAT".equals(val)) {
                String fn = bound.get("K|" + code + "|" + dev);      // удержание: только громкость
                if ("vol_up".equals(fn) || "vol_down".equals(fn)) run(fn);
            }
        } else if ("EV_REL".equals(type)) {
            markSeen(dev);
            int v = parseHex(val);
            if ("REL_X".equals(code) || "REL_Y".equals(code)) {
                addRel(dev, "REL_X".equals(code), v);
            } else if ("REL_WHEEL".equals(code)) {
                if (v != 0) trigger("R|WHEEL_" + (v > 0 ? "UP" : "DOWN") + "|" + dev);
            } else if ("REL_HWHEEL".equals(code)) {
                if (v != 0) trigger("R|HWHEEL_" + (v > 0 ? "RIGHT" : "LEFT") + "|" + dev);
            }
        }
    }

    private static int parseHex(String h) {
        try {
            return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void markSeen(String name) {
        synchronized (lock) {
            if (seen.size() < 8) seen.add(name);
        }
    }

    // ------------------------------------------------------------------ указка: рывки

    private void addRel(String dev, boolean isX, int v) {
        synchronized (lock) {
            if (isX) gdx += v; else gdy += v;
            gDev = dev;
        }
        if (main != null) {
            main.removeCallbacks(gestureEnd);
            main.postDelayed(gestureEnd, GESTURE_GAP_MS);
        }
    }

    /** Пауза в движении указки: решаем, был ли это заметный рывок, и в какую сторону. */
    private void finishGesture() {
        float dx, dy;
        String dev;
        synchronized (lock) {
            dx = gdx;
            dy = gdy;
            dev = gDev;
            gdx = 0;
            gdy = 0;
        }
        float ax = Math.abs(dx), ay = Math.abs(dy), mx = Math.max(ax, ay);
        int min = prefs != null ? prefs.getInt("swipe_min", 20) : 20;
        if (mx >= min) {
            String d = (ay >= mx * 0.4f ? (dy > 0 ? "DOWN" : "UP") : "")
                    + (ax >= mx * 0.4f ? (dx > 0 ? "RIGHT" : "LEFT") : "");
            trigger("R|MOVE_" + d + "|" + dev);
        } else if (mx > 0) {
            synchronized (lock) {
                lastEvent = "указка дрогнула: " + Math.round(dx) + ", " + Math.round(dy) + " (меньше порога)";
            }
        }
    }

    /** Общий вход: запись кнопки, если ждём её, иначе выполнение назначенной функции. */
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
        String devs;
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            for (long t : stamps) {
                if (t == 0) continue;
                long age = now - t;
                if (age >= 0 && age < 30000) buckets[29 - (int) (age / 1000)]++;
            }
            last = lastEvent;
            StringBuilder sb = new StringBuilder();
            for (String d : seen) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(d);
            }
            devs = sb.toString();
        }
        String learn = learnFn;
        String status;
        if (learn != null) {
            status = "⏺ Нажмите кнопку на пульте для: " + label(learn);
        } else {
            status = "Shizuku: " + readerState + ". Назначено: " + bound.size() + " из " + FNS.length
                    + (devs.length() > 0 ? ". Устройства: " + devs : "");
        }
        m.put("values", buckets);
        m.put("value", last);
        m.put("status", status);
        return m;
    }

    // ------------------------------------------------------------------ настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        SharedPreferences p = schemaPrefs();
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("🎛 Пульт (нужен запущенный Shizuku)"));
        l.add(SettingItem.monitor("live", "📡 Что присылает пульт",
                "Нажмите кнопку на пульте: последнее событие, активность за 30 секунд и найденные устройства"));
        l.add(SettingItem.action("restart", "🔄 Перезапустить чтение", "Если пульт переподключали или Shizuku запустили позже"));
        l.add(SettingItem.section("🎯 Назначение: нажмите функцию, затем кнопку на пульте"));
        for (String[] f : FNS) {
            String sig = p == null ? null : p.getString("bind_" + f[0], null);
            l.add(SettingItem.action("learn_" + f[0], f[1],
                    sig == null ? "Не назначено" : "Сейчас: " + describe(sig)));
        }
        l.add(SettingItem.action("unbind_all", "🗑 Сбросить все назначения", "Пульт снова работает как обычно"));
        l.add(SettingItem.section("⚙ Параметры"));
        l.add(SettingItem.slider("swipe_min", "📏 Минимальный рывок указки",
                "Сколько единиц должна пройти указка пульта, чтобы считаться кнопкой", 2, 500, 2, 20));
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
        } else if ("restart".equals(key)) {
            killProcess();                            // цикл чтения сам запустит getevent заново
            startReader();
            toast("Чтение событий перезапущено");
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

    private static String label(String fn) {
        for (String[] f : FNS) if (f[0].equals(fn)) return f[1];
        return null;
    }

    private static String arrows(String d) {
        if ("UP".equals(d)) return "↑";
        if ("DOWN".equals(d)) return "↓";
        if ("LEFT".equals(d)) return "←";
        if ("RIGHT".equals(d)) return "→";
        if ("UPLEFT".equals(d)) return "↖";
        if ("UPRIGHT".equals(d)) return "↗";
        if ("DOWNLEFT".equals(d)) return "↙";
        if ("DOWNRIGHT".equals(d)) return "↘";
        return d;
    }

    /** Подпись кнопки человеческим языком: «KEY_NEXTSONG · BT Remote» или «рывок указки ↑ · BT Mouse». */
    private static String describe(String sig) {
        String[] p = sig.split("\\|", 3);
        if (p.length < 2) return sig;
        String dev = p.length > 2 && p[2].length() > 0 ? " · " + p[2] : "";
        if ("K".equals(p[0])) return p[1] + dev;
        String g = p[1];
        if (g.startsWith("MOVE_")) g = "рывок указки " + arrows(g.substring(5));
        else if ("WHEEL_UP".equals(g)) g = "колесо вверх";
        else if ("WHEEL_DOWN".equals(g)) g = "колесо вниз";
        else if ("HWHEEL_LEFT".equals(g)) g = "колесо влево";
        else if ("HWHEEL_RIGHT".equals(g)) g = "колесо вправо";
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
