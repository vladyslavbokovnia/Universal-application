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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth-пульт (например, для TikTok): записывает его кнопки и назначает на них функции.
 *
 * Сигналы читаются напрямую из ядра: через Shizuku запускается «getevent -l», и модуль видит всё, что шлёт пульт
 * (клавиши, клики и движения «мыши», колесо), даже если Android до службы специальных возможностей это не довёл.
 * Работает на любой версии Android, в том числе с выключенным экраном; нужен запущенный Shizuku.
 *
 * На одну кнопку можно назначить три разные функции: одно нажатие, двойное и долгое. Функции бывают двух видов:
 * системные медиакнопки и команды модуля «Аудиоплеер» (те же, что у геймпада: скорость, избранное, «назвать трек»).
 *
 * Как назначить: в настройках выбрать тип нажатия, удержать нужную функцию и сделать такое нажатие на пульте.
 * Читать события — не значит их гасить: getevent ничего не перехватывает, поэтому родное действие пульта
 * (курсор, пролистывание в TikTok) никуда не девается.
 *
 * Задержки: пока на кнопке нет двойного и долгого нажатий, одно нажатие срабатывает сразу. Если назначено долгое,
 * одно нажатие срабатывает при отпускании; если назначено двойное, оно ждёт паузу двойного нажатия.
 */
public class BtRemoteModule implements IPlugin, ISettingsProvider {

    private static final String NAME = "BtRemote";
    private static final String PREFS = "module_" + NAME;

    /** id функции, подпись. Первые SYSTEM_COUNT — системные; "ap:<id>" — команда модуля «Аудиоплеер» (его doPerform). */
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
            {"ap:play_pause", "▶️ Плеер: пауза / пуск"},
            {"ap:next", "➡️ Плеер: следующий трек"},
            {"ap:prev", "⬅️ Плеер: предыдущий трек"},
            {"ap:seek_back", "↩️ Плеер: назад на шаг"},
            {"ap:seek_fwd", "↪️ Плеер: вперёд на шаг"},
            {"ap:later", "🕓 Плеер: послушать позже"},
            {"ap:fav", "⭐ Плеер: в избранное"},
            {"ap:pl_switch", "🔀 Плеер: сменить список"},
            {"ap:say", "🗣 Плеер: назвать трек"},
            {"ap:say_pct", "📊 Плеер: сколько прослушано"},
            {"ap:speed_up", "🐇 Плеер: быстрее"},
            {"ap:speed_down", "🐢 Плеер: медленнее"},
            {"ap:speed_reset", "🎚 Плеер: скорость 1×"},
            {"ap:reverse", "🔃 Плеер: обратный порядок"},
            {"ap:stop_after", "🛑 Плеер: стоп после трека"},
            {"ap:lock", "🔒 Плеер: блок касаний"},
    };
    private static final int SYSTEM_COUNT = 9;

    /** Типы нажатия: S — одно, D — двойное, L — долгое. */
    private static final String[] G = {"S", "D", "L"};
    private static final String[] G_NAME = {"одно нажатие", "двойное нажатие", "долгое нажатие"};
    private static final String[] G_MARK = {"1×", "2×", "⏱"};

    /** Модуль «Аудиоплеер» кладёт свой работающий экземпляр в системные свойства JVM под этим ключом. */
    private static final String PLAYER_LIVE_KEY = "manus.audioplayer.live";

    private static final long GESTURE_GAP_MS = 150;
    private static final long LEARN_TIMEOUT_MS = 20000;

    private Context ctx;
    private Handler main;
    private SharedPreferences prefs;

    /** "<подпись кнопки>#<тип нажатия>" -> id функции */
    private final Map<String, String> bound = new ConcurrentHashMap<String, String>();

    // назначение (всё, кроме запуска, — на главном потоке)
    private volatile String learnFn;
    private volatile String learnG = "S";
    private String learnTapSig;
    private long learnTapAt;

    /** Состояние одной кнопки для распознавания одно/двойное/долгое (главный поток). */
    private static final class KeyState {
        boolean down, longFired, suppress;
        Runnable longTimer, tapTimer;
    }
    private final Map<String, KeyState> keyStates = new HashMap<String, KeyState>();

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
    @Override public int getVersion() { return 4; }
    @Override public String getDescription() {
        return "Bluetooth-пульт через Shizuku: кнопки, двойное и долгое нажатие, медиа и команды плеера";
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
        if (main != null) main.removeCallbacksAndMessages(null);    // таймеры нажатий и назначения этого модуля
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

    private void postMain(Runnable r) {
        Handler h = main;
        if (h != null) h.post(r);
    }

    /** Разбор одной строки «getevent -l» (поток чтения). Логика нажатий уходит на главный поток. */
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
            final String sig = "K|" + code + "|" + dev;
            if ("DOWN".equals(val)) {
                postMain(new Runnable() { @Override public void run() { keyDown(sig); } });
            } else if ("UP".equals(val)) {
                postMain(new Runnable() { @Override public void run() { keyUp(sig); } });
            } else if ("REPEAT".equals(val)) {
                postMain(new Runnable() { @Override public void run() { keyRepeat(sig); } });
            }
        } else if ("EV_REL".equals(type)) {
            markSeen(dev);
            int v = parseHex(val);
            if ("REL_X".equals(code) || "REL_Y".equals(code)) {
                addRel(dev, "REL_X".equals(code), v);
            } else if ("REL_WHEEL".equals(code)) {
                if (v != 0) postTrigger("R|WHEEL_" + (v > 0 ? "UP" : "DOWN") + "|" + dev);
            } else if ("REL_HWHEEL".equals(code)) {
                if (v != 0) postTrigger("R|HWHEEL_" + (v > 0 ? "RIGHT" : "LEFT") + "|" + dev);
            }
        }
    }

    private void postTrigger(final String sig) {
        postMain(new Runnable() { @Override public void run() { trigger(sig); } });
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

    // ------------------------------------------------------------------ кнопки: одно, двойное, долгое

    private long longMs() { return prefs != null ? prefs.getInt("long_ms", 600) : 600; }
    private long doubleMs() { return prefs != null ? prefs.getInt("double_ms", 350) : 350; }

    private boolean has(String sig, String g) { return bound.containsKey(sig + "#" + g); }

    private void fire(String sig, String g) {
        String fn = bound.get(sig + "#" + g);
        if (fn != null) run(fn);
    }

    private KeyState stateOf(String sig) {
        KeyState st = keyStates.get(sig);
        if (st == null) {
            st = new KeyState();
            keyStates.put(sig, st);
        }
        return st;
    }

    private void keyDown(final String sig) {
        final KeyState st = stateOf(sig);
        if (st.longTimer != null) { main.removeCallbacks(st.longTimer); st.longTimer = null; }
        st.down = true;
        st.longFired = false;
        st.suppress = false;
        note(sig);

        String learn = learnFn;
        if (learn != null) {
            learnDown(learn, sig, st);
            return;
        }
        boolean hasL = has(sig, "L"), hasD = has(sig, "D");
        if (!hasL && !hasD) {                       // обычная кнопка: одно нажатие сразу, без задержки
            fire(sig, "S");
            return;
        }
        if (hasL) {
            st.longTimer = new Runnable() {
                @Override public void run() {
                    st.longTimer = null;
                    if (st.down) {
                        st.longFired = true;
                        fire(sig, "L");
                    }
                }
            };
            main.postDelayed(st.longTimer, longMs());
        }
    }

    private void keyUp(final String sig) {
        final KeyState st = stateOf(sig);
        if (!st.down) return;
        st.down = false;
        if (st.longTimer != null) { main.removeCallbacks(st.longTimer); st.longTimer = null; }
        if (st.suppress) { st.suppress = false; return; }        // нажатие уже ушло в назначение
        if (st.longFired) { st.longFired = false; return; }

        if (learnFn != null) {
            learnUp(learnFn, sig);
            return;
        }
        boolean hasL = has(sig, "L"), hasD = has(sig, "D");
        if (!hasL && !hasD) return;                  // одно нажатие уже сработало при нажатии
        if (hasD) {
            if (st.tapTimer != null) {               // второе нажатие в пределах паузы — двойное
                main.removeCallbacks(st.tapTimer);
                st.tapTimer = null;
                fire(sig, "D");
            } else {
                st.tapTimer = new Runnable() {
                    @Override public void run() {
                        st.tapTimer = null;
                        fire(sig, "S");
                    }
                };
                main.postDelayed(st.tapTimer, doubleMs());
            }
        } else {
            fire(sig, "S");
        }
    }

    /** Удержание кнопки с автоповтором: повторяем только громкость, и только когда у кнопки нет долгого и двойного. */
    private void keyRepeat(String sig) {
        if (learnFn != null || has(sig, "L") || has(sig, "D")) return;
        String fn = bound.get(sig + "#S");
        if ("vol_up".equals(fn) || "vol_down".equals(fn)) run(fn);
    }

    // ------------------------------------------------------------------ указка: рывки и колесо

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

    /** Пауза в движении указки: решаем, был ли это заметный рывок, и в какую сторону (главный поток). */
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

    /** Мгновенные сигналы без отпускания (рывок указки, колесо): только одно нажатие. */
    private void trigger(String sig) {
        note(sig);
        String learn = learnFn;
        if (learn != null) {
            if ("S".equals(learnG)) finishLearn(learn, sig, "S");   // двойное и долгое ждут именно кнопку
            return;
        }
        fire(sig, "S");
    }

    // ------------------------------------------------------------------ назначение

    private int gestureIndex() {
        int gi = prefs != null ? prefs.getInt("gesture", 0) : 0;
        return gi < 0 || gi >= G.length ? 0 : gi;
    }

    private static String learnHint(String g) {
        if ("D".equals(g)) return "Дважды нажмите кнопку на пульте";
        if ("L".equals(g)) return "Нажмите кнопку на пульте и держите";
        return "Нажмите кнопку на пульте";
    }

    private void startLearn(String fn) {
        if (label(fn) == null || main == null) return;
        learnG = G[gestureIndex()];
        learnTapSig = null;
        learnFn = fn;
        main.removeCallbacks(learnTimeout);
        main.postDelayed(learnTimeout, LEARN_TIMEOUT_MS);
        toast(learnHint(learnG) + " для: " + label(fn) + " (20 с)");
    }

    /** Кнопка нажата, пока ждём назначения. */
    private void learnDown(final String fn, final String sig, final KeyState st) {
        if ("S".equals(learnG)) {
            finishLearn(fn, sig, "S");
            st.suppress = true;
        } else if ("L".equals(learnG)) {
            st.longTimer = new Runnable() {
                @Override public void run() {
                    st.longTimer = null;
                    if (st.down && fn.equals(learnFn)) {
                        finishLearn(fn, sig, "L");
                        st.suppress = true;
                    }
                }
            };
            main.postDelayed(st.longTimer, longMs());
        }
    }

    /** Кнопка отпущена, пока ждём назначения: для двойного нажатия сравниваем паузу между отпусканиями. */
    private void learnUp(String fn, String sig) {
        if (!"D".equals(learnG)) return;
        long now = SystemClock.uptimeMillis();
        if (sig.equals(learnTapSig) && now - learnTapAt <= doubleMs()) {
            finishLearn(fn, sig, "D");
        } else {
            learnTapSig = sig;
            learnTapAt = now;
        }
    }

    private static String bindKey(String fn, String g) {
        return ("S".equals(g) ? "bind_" : "bind" + g + "_") + fn;
    }

    private void finishLearn(String fn, String sig, String g) {
        learnFn = null;
        learnTapSig = null;
        if (main != null) main.removeCallbacks(learnTimeout);
        SharedPreferences.Editor ed = prefs.edit();
        for (String[] f : FNS) {
            if (sig.equals(prefs.getString(bindKey(f[0], g), null))) ed.remove(bindKey(f[0], g));   // одна кнопка и тип нажатия — одна функция
        }
        ed.putString(bindKey(fn, g), sig);
        ed.apply();
        loadBindings();
        toast("Назначено (" + G_NAME[Arrays.asList(G).indexOf(g)] + "): " + label(fn) + " ← " + describe(sig));
    }

    private void loadBindings() {
        bound.clear();
        for (String[] f : FNS) {
            for (String g : G) {
                String sig = prefs.getString(bindKey(f[0], g), null);
                if (sig != null) bound.put(sig + "#" + g, f[0]);
            }
        }
    }

    // ------------------------------------------------------------------ функции

    private void run(String fn) {
        Context c = ctx;
        if (c == null || fn == null) return;
        if (fn.startsWith("ap:")) {
            runPlayer(fn.substring(3));
            return;
        }
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

    /**
     * Команда модуля «Аудиоплеер»: тот же doPerform(id), что вызывается нажатием кнопки геймпада. Экземпляр плеера
     * лежит в системных свойствах JVM, метод закрытый, поэтому вызов через рефлексию. Плеер должен быть включён.
     */
    private void runPlayer(String id) {
        try {
            Object ap = System.getProperties().get(PLAYER_LIVE_KEY);
            if (ap == null) {
                toast("Аудиоплеер не запущен");
                return;
            }
            Method m = ap.getClass().getDeclaredMethod("doPerform", String.class);
            m.setAccessible(true);
            m.invoke(ap, id);
        } catch (Throwable t) {
            t.printStackTrace();
            toast("Не удалось вызвать команду плеера");
        }
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
            status = "⏺ " + learnHint(learnG) + " для: " + label(learn);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Shizuku: ").append(readerState);
            if (devs.length() > 0) sb.append("\nУстройства: ").append(devs);
            sb.append("\nНазначено: ").append(bound.size());
            for (String[] f : FNS) {
                for (int gi = 0; gi < G.length; gi++) {
                    for (Map.Entry<String, String> e : bound.entrySet()) {
                        String k = e.getKey();
                        if (f[0].equals(e.getValue()) && k.endsWith("#" + G[gi])) {
                            sb.append("\n").append(G_MARK[gi]).append(" ").append(f[1])
                                    .append(" ← ").append(describe(k.substring(0, k.length() - 2)));
                        }
                    }
                }
            }
            status = sb.toString();
        }
        m.put("values", buckets);
        m.put("value", last);
        m.put("status", status);
        return m;
    }

    // ------------------------------------------------------------------ настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("🎛 Пульт (нужен запущенный Shizuku)"));
        l.add(SettingItem.monitor("live", "📡 Что присылает пульт",
                "Нажмите кнопку на пульте: последнее событие, активность за 30 секунд, устройства и текущие назначения"));
        l.add(SettingItem.action("restart", "🔄 Перезапустить чтение", "Если пульт переподключали или Shizuku запустили позже"));
        l.add(SettingItem.section("👆 Какое нажатие назначать"));
        l.add(SettingItem.choice("gesture", "👆 Тип нажатия",
                "Выбранный тип назначается на функцию, которую вы удержите ниже",
                Arrays.asList("1× Одно нажатие", "2× Двойное нажатие", "⏱ Долгое нажатие"), 0));
        l.add(SettingItem.section("🎯 Системные медиафункции: удерживайте функцию, затем нажмите кнопку на пульте"));
        // Состояние в подписи не пишем: экран настроек её не обновляет, а озвучивает при каждом нажатии.
        // Текущие назначения видны в мониторе вверху.
        for (int i = 0; i < SYSTEM_COUNT; i++) {
            l.add(SettingItem.action("learn_" + FNS[i][0], FNS[i][1], "Удерживайте, затем нажмите кнопку на пульте"));
        }
        l.add(SettingItem.section("🎧 Команды плеера (модуль «Аудиоплеер» должен быть включён)"));
        for (int i = SYSTEM_COUNT; i < FNS.length; i++) {
            l.add(SettingItem.action("learn_" + FNS[i][0], FNS[i][1], "Удерживайте, затем нажмите кнопку на пульте"));
        }
        l.add(SettingItem.action("unbind_all", "🗑 Сбросить все назначения", "Пульт снова работает как обычно"));
        l.add(SettingItem.section("⚙ Параметры"));
        l.add(SettingItem.slider("long_ms", "⏱ Долгое нажатие, мс",
                "Сколько держать кнопку, чтобы нажатие считалось долгим", 300, 1500, 50, 600));
        l.add(SettingItem.slider("double_ms", "⚡ Пауза двойного нажатия, мс",
                "Максимальная пауза между двумя нажатиями; чем больше, тем позже сработает одиночное", 150, 800, 25, 350));
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
            for (String[] f : FNS) {
                for (String g : G) ed.remove(bindKey(f[0], g));
            }
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
