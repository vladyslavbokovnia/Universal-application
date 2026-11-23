package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.PadMapper;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth-пульт (TikTok и подобные): кнопки читаются из ядра через Shizuku (getevent), работает и с выключенным экраном.
 * Нажатие кнопки на пульте открывает её на схеме, и для неё выбираются три действия: одно, двойное, долгое нажатие.
 * Пока открыт экран настроек, действия не выполняются: нажатия только выбирают кнопку на схеме.
 * Кнопки-«указка» (клик или свайп по экрану, которыми пульт имитирует касания) различаются по движению: тап или свайп
 * в какую сторону, поэтому центральная кнопка не срабатывает от стрелок.
 * Ниже схемы пульта показывается схема геймпада из модуля «Аудиоплеер» (его PadMapper).
 */
public class BtRemoteModule implements IPlugin, ISettingsProvider, ISettingsViewProvider {

    private static final String NAME = "BtRemote";
    private static final String PREFS = "module_" + NAME;
    private static final String PLAYER_LIVE_KEY = "manus.audioplayer.live";

    /** id функции, подпись (эмодзи в начале рисуется на схеме). "ap:<id>" — команда модуля «Аудиоплеер». */
    private static final String[][] FNS = {
            {"play_pause", "⏯ Пауза / воспроизведение"}, {"next", "⏭ Следующий трек"},
            {"prev", "⏮ Предыдущий трек"}, {"ff", "⏩ Перемотка вперёд"}, {"rw", "⏪ Перемотка назад"},
            {"vol_up", "🔊 Громкость +"}, {"vol_down", "🔉 Громкость −"}, {"mute", "🔇 Без звука"},
            {"stop", "⏹ Стоп"},
            {"ap:play_pause", "▶️ Плеер: пауза / пуск"}, {"ap:next", "➡️ Плеер: следующий трек"},
            {"ap:prev", "⬅️ Плеер: предыдущий трек"}, {"ap:seek_back", "↩️ Плеер: назад на шаг"},
            {"ap:seek_fwd", "↪️ Плеер: вперёд на шаг"}, {"ap:later", "🕓 Плеер: послушать позже"},
            {"ap:fav", "⭐ Плеер: в избранное"}, {"ap:pl_switch", "🔀 Плеер: сменить список"},
            {"ap:say", "🗣 Плеер: назвать трек"}, {"ap:say_pct", "📊 Плеер: сколько прослушано"},
            {"ap:speed_up", "🐇 Плеер: быстрее"}, {"ap:speed_down", "🐢 Плеер: медленнее"},
            {"ap:speed_reset", "🎚 Плеер: скорость 1×"}, {"ap:reverse", "🔃 Плеер: обратный порядок"},
            {"ap:stop_after", "🛑 Плеер: стоп после трека"}, {"ap:lock", "🔒 Плеер: блок касаний"},
    };

    /** Типы нажатия: S — одно, D — двойное, L — долгое. */
    private static final String[] G = {"S", "D", "L"};
    private static final String[] G_NAME = {"Одно нажатие", "Двойное нажатие", "Долгое нажатие"};

    /** Кнопки на схеме: id, подпись, x, y, радиус (поле 1000 × 1500). */
    private static final Object[][] SLOTS = {
            {"up", "▲", 500, 250, 120}, {"left", "◀", 290, 460, 120}, {"right", "▶", 710, 460, 120},
            {"down", "▼", 500, 670, 120}, {"heart", "♥", 500, 460, 105}, {"cam", "📷", 500, 1120, 150},
    };

    private static final long GESTURE_GAP_MS = 150;

    private Context ctx;
    private Handler main;
    private SharedPreferences prefs;

    /** "<кнопка>#<тип>" -> id функции */
    private final Map<String, String> bound = new ConcurrentHashMap<String, String>();
    private volatile int uiOpen;                       // открытых экранов настроек: действия не выполняются
    private volatile String lastSig;
    private volatile long lastSigAt;
    private volatile Runnable pressCb;                 // уведомление экрана настроек (главный поток)

    private static final class KeyState {
        boolean down, longFired, suppress;
        Runnable longTimer, tapTimer;
    }
    private final Map<String, KeyState> keyStates = new HashMap<String, KeyState>();

    /** Состояние «указки» одного устройства (поток чтения). */
    private static final class Ptr {
        boolean down;
        long t0;
        float sx, sy, cx, cy, rx, ry;
    }
    private final Map<String, Ptr> ptrs = new HashMap<String, Ptr>();
    private final Set<String> excluded = new HashSet<String>();    // сенсорный экран и кнопки самого телефона

    private volatile boolean alive;
    private volatile Process proc;
    private Thread reader;
    private volatile String readerState = "не запущено";
    private final Map<String, String> devNames = new HashMap<String, String>();
    private String curPath;

    private final Object lock = new Object();
    private final LinkedList<String> evlog = new LinkedList<String>();
    private float gdx, gdy;
    private String gDev = "";

    private final Runnable gestureEnd = new Runnable() { @Override public void run() { finishGesture(); } };
    private final Runnable readyCb = new Runnable() { @Override public void run() { startReader(); } };

    @Override public String getName() { return NAME; }
    @Override public int getVersion() { return 5; }
    @Override public String getDescription() {
        return "Bluetooth-пульт через Shizuku: схема кнопок, одно/двойное/долгое нажатие, команды плеера";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        ctx = context;
        alive = true;
        main = new Handler(Looper.getMainLooper());
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrate();
        loadBindings();
        ShizukuBridge.addReadyListener(readyCb);
        startReader();
    }

    @Override
    public void stop() {
        alive = false;
        ShizukuBridge.removeReadyListener(readyCb);
        killProcess();
        if (main != null) main.removeCallbacksAndMessages(null);
        ctx = null;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    /** execute({"command": "run:<id функции>"}) — выполнить функцию (для других модулей). */
    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("command");
        if (cmd instanceof String && ((String) cmd).startsWith("run:")) {
            run(((String) cmd).substring(4));
            return "ok";
        }
        return null;
    }

    // ------------------------------------------------------------------ чтение событий ядра

    private void startReader() {
        if (!alive || ctx == null) return;
        synchronized (lock) { if (reader != null && reader.isAlive()) return; }
        if (!ShizukuBridge.hasPermission()) {
            readerState = ShizukuBridge.isRunning() ? "у приложения нет разрешения Shizuku" : "Shizuku не запущен";
            return;
        }
        Thread t = new Thread(new Runnable() { @Override public void run() { readLoop(); } }, "BtRemote-getevent");
        t.setDaemon(true);
        synchronized (lock) { reader = t; }
        t.start();
    }

    private void killProcess() {
        Process p = proc;
        proc = null;
        if (p != null) { try { p.destroy(); } catch (Throwable t) { } }
    }

    /** Сенсорный экран (INPUT_PROP_DIRECT) и кнопки самого телефона (шина 0019) пульта не касаются: не читаем. */
    private void scanExcluded() {
        try {
            String out = ShizukuBridge.exec("getevent -lp").getOut();
            String path = null, bus = "";
            boolean direct = false;
            Set<String> ex = new HashSet<String>();
            for (String raw : (out + "\nadd device").split("\n")) {
                String s = raw.trim();
                if (s.startsWith("add device")) {
                    if (path != null && (direct || "0019".equals(bus))) ex.add(path);
                    int i = s.indexOf(": ");
                    path = i > 0 ? s.substring(i + 2).trim() : null;
                    bus = "";
                    direct = false;
                } else if (s.startsWith("bus:")) {
                    bus = s.substring(4).trim();
                } else if (s.contains("INPUT_PROP_DIRECT")) {
                    direct = true;
                }
            }
            synchronized (lock) { excluded.clear(); excluded.addAll(ex); }
        } catch (Throwable t) { }
    }

    private void readLoop() {
        while (alive) {
            scanExcluded();
            Process p = ShizukuBridge.startProcess("exec getevent -l");
            if (p == null) { readerState = "не удалось запустить getevent через Shizuku"; return; }
            proc = p;
            readerState = "читаю события пульта";
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                try {
                    String line;
                    while (alive && (line = r.readLine()) != null) handleLine(line);
                } finally { r.close(); }
            } catch (Throwable t) { }
            killProcess();
            if (!alive) return;
            readerState = "getevent завершился, перезапуск";
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private void postMain(Runnable r) { Handler h = main; if (h != null) h.post(r); }

    private static int parseHex(String h) {
        try { return (int) Long.parseLong(h, 16); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean isPointerBtn(String code) {
        return "BTN_LEFT".equals(code) || "BTN_TOUCH".equals(code) || "BTN_MOUSE".equals(code);
    }

    /** Разбор строки «getevent -l» (поток чтения). */
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
            if (curPath != null && a >= 0 && b > a) synchronized (devNames) { devNames.put(curPath, s.substring(a + 1, b)); }
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
        synchronized (lock) { if (excluded.contains(path)) return; }

        String dev;
        synchronized (devNames) { dev = devNames.get(path); }
        if (dev == null) dev = path;
        dev = dev.replace('|', '/').replace('#', '/');
        Ptr p = ptrs.get(dev);
        if (p == null) { p = new Ptr(); ptrs.put(dev, p); }

        if ("EV_ABS".equals(type)) {
            int v = parseHex(val);
            if ("ABS_X".equals(code) || "ABS_MT_POSITION_X".equals(code)) p.cx = v;
            else if ("ABS_Y".equals(code) || "ABS_MT_POSITION_Y".equals(code)) p.cy = v;
        } else if ("EV_REL".equals(type)) {
            int v = parseHex(val);
            if ("REL_X".equals(code) || "REL_Y".equals(code)) {
                if (p.down) { if ("REL_X".equals(code)) p.rx += v; else p.ry += v; }
                else addRel(dev, "REL_X".equals(code), v);
            } else if ("REL_WHEEL".equals(code) && v != 0) {
                postPress("R|WHEEL_" + (v > 0 ? "UP" : "DOWN") + "|" + dev, 0);
            } else if ("REL_HWHEEL".equals(code) && v != 0) {
                postPress("R|HWHEEL_" + (v > 0 ? "RIGHT" : "LEFT") + "|" + dev, 0);
            }
        } else if ("EV_KEY".equals(type)) {
            if (code.startsWith("BTN_TOOL")) return;
            if (isPointerBtn(code)) {
                if ("DOWN".equals(val)) {
                    p.down = true; p.t0 = SystemClock.uptimeMillis();
                    p.sx = p.cx; p.sy = p.cy; p.rx = 0; p.ry = 0;
                } else if ("UP".equals(val) && p.down) {
                    p.down = false;
                    float dx = p.cx - p.sx + p.rx, dy = p.cy - p.sy + p.ry;
                    float ax = Math.abs(dx), ay = Math.abs(dy);
                    int min = prefs != null ? prefs.getInt("swipe_min", 20) : 20;
                    String g = Math.max(ax, ay) < min ? "TAP"
                            : ay >= ax ? (dy > 0 ? "SWIPE_DOWN" : "SWIPE_UP") : (dx > 0 ? "SWIPE_RIGHT" : "SWIPE_LEFT");
                    postPress("P|" + g + "|" + dev, SystemClock.uptimeMillis() - p.t0);
                }
                return;
            }
            final String sig = "K|" + code + "|" + dev;
            if ("DOWN".equals(val)) postMain(new Runnable() { @Override public void run() { keyDown(sig); } });
            else if ("UP".equals(val)) postMain(new Runnable() { @Override public void run() { keyUp(sig); } });
            else if ("REPEAT".equals(val)) postMain(new Runnable() { @Override public void run() { keyRepeat(sig); } });
        }
    }

    private void postPress(final String sig, final long dur) {
        postMain(new Runnable() { @Override public void run() { pressDone(sig, dur); } });
    }

    private void addRel(String dev, boolean isX, int v) {
        synchronized (lock) { if (isX) gdx += v; else gdy += v; gDev = dev; }
        if (main != null) { main.removeCallbacks(gestureEnd); main.postDelayed(gestureEnd, GESTURE_GAP_MS); }
    }

    /** Пауза в движении указки без нажатия: заметный рывок становится кнопкой. */
    private void finishGesture() {
        float dx, dy; String dev;
        synchronized (lock) { dx = gdx; dy = gdy; dev = gDev; gdx = 0; gdy = 0; }
        float ax = Math.abs(dx), ay = Math.abs(dy), mx = Math.max(ax, ay);
        int min = prefs != null ? prefs.getInt("swipe_min", 20) : 20;
        if (mx < min) return;
        String d = (ay >= mx * 0.4f ? (dy > 0 ? "DOWN" : "UP") : "") + (ax >= mx * 0.4f ? (dx > 0 ? "RIGHT" : "LEFT") : "");
        pressDone("R|MOVE_" + d + "|" + dev, 0);
    }

    // ------------------------------------------------------------------ одно, двойное, долгое нажатие

    private long longMs() { return prefs != null ? prefs.getInt("long_ms", 600) : 600; }
    private long doubleMs() { return prefs != null ? prefs.getInt("double_ms", 350) : 350; }
    private boolean has(String sig, String g) { return bound.containsKey(sig + "#" + g); }

    private void fire(String sig, String g) {
        if (uiOpen > 0) return;                       // экран настроек открыт: нажатия только выбирают кнопку
        String fn = bound.get(sig + "#" + g);
        if (fn != null) run(fn);
    }

    private KeyState stateOf(String sig) {
        KeyState st = keyStates.get(sig);
        if (st == null) { st = new KeyState(); keyStates.put(sig, st); }
        return st;
    }

    /** Любая нажатая кнопка: в журнал и на открытый экран настроек (главный поток). */
    private void note(String sig) {
        synchronized (lock) {
            evlog.addFirst(describe(sig));
            while (evlog.size() > 12) evlog.removeLast();
        }
        lastSig = sig;
        lastSigAt = SystemClock.uptimeMillis();
        Runnable cb = pressCb;
        if (cb != null) cb.run();
    }

    private void keyDown(final String sig) {
        final KeyState st = stateOf(sig);
        if (st.longTimer != null) { main.removeCallbacks(st.longTimer); st.longTimer = null; }
        st.down = true; st.longFired = false; st.suppress = false;
        note(sig);
        if (uiOpen > 0) { st.suppress = true; return; }
        boolean hasL = has(sig, "L"), hasD = has(sig, "D");
        if (!hasL && !hasD) { fire(sig, "S"); return; }
        if (hasL) {
            st.longTimer = new Runnable() {
                @Override public void run() {
                    st.longTimer = null;
                    if (st.down) { st.longFired = true; fire(sig, "L"); }
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
        if (st.suppress) { st.suppress = false; return; }
        if (st.longFired) { st.longFired = false; return; }
        shortPress(sig, st, true);
    }

    /** Короткое нажатие: одно или двойное. [afterRelease] — одиночное без двойного и долгого уже сработало при нажатии. */
    private void shortPress(final String sig, final KeyState st, boolean afterRelease) {
        boolean hasL = has(sig, "L"), hasD = has(sig, "D");
        if (afterRelease && !hasL && !hasD) return;
        if (hasD) {
            if (st.tapTimer != null) {
                main.removeCallbacks(st.tapTimer); st.tapTimer = null;
                fire(sig, "D");
            } else {
                st.tapTimer = new Runnable() {
                    @Override public void run() { st.tapTimer = null; fire(sig, "S"); }
                };
                main.postDelayed(st.tapTimer, doubleMs());
            }
        } else {
            fire(sig, "S");
        }
    }

    private void keyRepeat(String sig) {
        if (uiOpen > 0 || has(sig, "L") || has(sig, "D")) return;
        String fn = bound.get(sig + "#S");
        if ("vol_up".equals(fn) || "vol_down".equals(fn)) run(fn);
    }

    /** Завершённое нажатие указки или колеса: длительность известна только в конце, поэтому долгое срабатывает при отпускании. */
    private void pressDone(String sig, long dur) {
        note(sig);
        if (uiOpen > 0) return;
        if (dur >= longMs() && has(sig, "L")) { fire(sig, "L"); return; }
        shortPress(sig, stateOf(sig), false);
    }

    // ------------------------------------------------------------------ назначения

    private static String oldKey(String fn, String g) { return ("S".equals(g) ? "bind_" : "bind" + g + "_") + fn; }

    /** Назначения прежних версий (по функциям) переносятся в «кнопка#тип -> функция». */
    private void migrate() {
        if (prefs.getBoolean("mig5", false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (String[] f : FNS) {
            for (String g : G) {
                String sig = prefs.getString(oldKey(f[0], g), null);
                if (sig == null) continue;
                ed.putString("act#" + sig + "#" + g, f[0]);
                ed.remove(oldKey(f[0], g));
            }
        }
        ed.putBoolean("mig5", true);
        ed.apply();
    }

    private void loadBindings() {
        Map<String, String> m = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("act#") || !(e.getValue() instanceof String)) continue;
            int i = k.lastIndexOf('#');
            if (i <= 4) continue;
            m.put(k.substring(4, i) + "#" + k.substring(i + 1), (String) e.getValue());
        }
        bound.clear();
        bound.putAll(m);
    }

    private void setAct(String sig, String g, String fn) {
        String k = "act#" + sig + "#" + g;
        SharedPreferences.Editor ed = prefs.edit();
        if (fn == null) ed.remove(k); else ed.putString(k, fn);
        ed.apply();
        loadBindings();
    }

    private String slotSig(String id) { return prefs.getString("slot_" + id, null); }

    private String slotOf(String sig) {
        for (Object[] s : SLOTS) if (sig.equals(slotSig((String) s[0]))) return (String) s[0];
        return null;
    }

    // ------------------------------------------------------------------ функции

    private void run(String fn) {
        Context c = ctx;
        if (c == null || fn == null) return;
        if (fn.startsWith("ap:")) { runPlayer(fn.substring(3)); return; }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if ("play_pause".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            else if ("next".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_NEXT);
            else if ("prev".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            else if ("ff".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            else if ("rw".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_REWIND);
            else if ("stop".equals(fn)) media(am, KeyEvent.KEYCODE_MEDIA_STOP);
            else if ("vol_up".equals(fn)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            else if ("vol_down".equals(fn)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            else if ("mute".equals(fn)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        } catch (Throwable t) { t.printStackTrace(); }
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** Команда модуля «Аудиоплеер»: его закрытый doPerform(id) через рефлексию (тот же, что у геймпада). */
    private void runPlayer(String id) {
        try {
            Object ap = System.getProperties().get(PLAYER_LIVE_KEY);
            if (ap == null) { toast("Аудиоплеер не запущен"); return; }
            Method m = ap.getClass().getDeclaredMethod("doPerform", String.class);
            m.setAccessible(true);
            m.invoke(ap, id);
        } catch (Throwable t) { t.printStackTrace(); toast("Не удалось вызвать команду плеера"); }
    }

    // ------------------------------------------------------------------ подписи

    private static String fnLabel(String fn) {
        for (String[] f : FNS) if (f[0].equals(fn)) return f[1];
        return null;
    }

    private static String fnEmoji(String fn) {
        String l = fnLabel(fn);
        if (l == null) return "";
        int sp = l.indexOf(' ');
        return sp > 0 ? l.substring(0, sp) : "";
    }

    private static String describe(String sig) {
        String[] p = sig.split("\\|", 3);
        if (p.length < 2) return sig;
        String dev = p.length > 2 && p[2].length() > 0 ? " · " + p[2] : "";
        String g = p[1];
        if ("K".equals(p[0])) return g + dev;
        if ("TAP".equals(g)) g = "тап";
        else if (g.startsWith("SWIPE_")) g = "свайп " + dirName(g.substring(6));
        else if (g.startsWith("MOVE_")) g = "рывок указки " + dirName(g.substring(5));
        else if (g.startsWith("WHEEL")) g = "колесо " + (g.endsWith("UP") ? "вверх" : "вниз");
        else if (g.startsWith("HWHEEL")) g = "колесо " + (g.endsWith("LEFT") ? "влево" : "вправо");
        return g + dev;
    }

    private static String dirName(String d) {
        return d.replace("UP", "↑").replace("DOWN", "↓").replace("LEFT", "←").replace("RIGHT", "→");
    }

    private void toast(final String text) {
        final Context c = ctx;
        if (c == null || main == null) return;
        main.post(new Runnable() { @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); } });
    }

    // ------------------------------------------------------------------ настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("🎛 Схема пульта"));
        l.add(SettingItem.custom("view", "🎛 Пульт и геймпад", "Нажмите кнопку на пульте, потом выберите действия"));
        l.add(SettingItem.section("⚙ Параметры"));
        l.add(SettingItem.slider("long_ms", "⏱ Долгое нажатие, мс", "Сколько держать кнопку", 300, 1500, 50, 600));
        l.add(SettingItem.slider("double_ms", "⚡ Пауза двойного нажатия, мс", "Чем больше, тем позже сработает одиночное", 150, 800, 25, 350));
        l.add(SettingItem.slider("swipe_min", "📏 Порог свайпа указки", "Меньше порога — тап, больше — свайп", 2, 500, 2, 20));
        l.add(SettingItem.action("restart", "🔄 Перезапустить чтение", "Если пульт переподключали"));
        l.add(SettingItem.action("unbind_all", "🗑 Сбросить все назначения", "Кнопки и действия"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (key == null || prefs == null) return;
        if ("unbind_all".equals(key)) {
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
                if (k.startsWith("act#") || k.startsWith("slot_")) ed.remove(k);
            }
            ed.apply();
            loadBindings();
            toast("Все назначения сброшены");
        } else if ("restart".equals(key)) {
            killProcess();
            startReader();
            toast("Чтение событий перезапущено");
        }
    }

    @Override public Bitmap createIcon(int sizePx) { return null; }

    @Override
    public View createSettingsView(Context context, String key) {
        if (prefs == null) prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (bound.isEmpty()) { migrate(); loadBindings(); }
        return new RemoteView(context);
    }

    // ------------------------------------------------------------------ экран настроек: схема пульта и геймпада

    private int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    private TextView tv(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Схема геймпада из модуля «Аудиоплеер»: его PadMapper рисует картинку и сам хранит назначения. */
    private View gamepadView(Context c) {
        try {
            Object ap = System.getProperties().get(PLAYER_LIVE_KEY);
            if (ap == null) return tv(c, "Включите модуль «Аудиоплеер», чтобы здесь появилась схема геймпада.", 14f, Color.LTGRAY);
            Object m = null;
            try {
                Field f = ap.getClass().getDeclaredField("mapper");
                f.setAccessible(true);
                m = f.get(ap);
            } catch (Throwable ignored) { }
            if (m == null) {
                Method mm = ap.getClass().getDeclaredMethod("mapper", Context.class);
                mm.setAccessible(true);
                m = mm.invoke(ap, c);
            }
            return ((PadMapper) m).createView(c, "");
        } catch (Throwable t) {
            return tv(c, "Схема геймпада недоступна: " + t, 14f, Color.LTGRAY);
        }
    }

    private final class RemoteView extends LinearLayout {
        private final Diagram dia;
        private final LinearLayout panel;
        private final TextView status;
        private String pending;                 // нажатая кнопка, которую ещё не поставили на схему
        private String current;                 // кнопка, чьи действия открыты

        private final Runnable onPress = new Runnable() { @Override public void run() { pressed(); } };

        RemoteView(Context c) {
            super(c);
            setOrientation(VERTICAL);
            status = tv(c, "Нажмите кнопку на пульте.", 14f, Color.LTGRAY);
            status.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
            dia = new Diagram(c);
            panel = new LinearLayout(c);
            panel.setOrientation(VERTICAL);
            panel.setBackgroundColor(Color.rgb(26, 26, 26));
            addView(status);
            addView(dia, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(panel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView h = tv(c, "🎮 Геймпад (схема из плеера)", 18f, Color.WHITE);
            h.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 8));
            addView(h);
            addView(gamepadView(c));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            uiOpen++;
            pressCb = onPress;
            refreshStatus();
        }

        @Override protected void onDetachedFromWindow() {
            if (uiOpen > 0) uiOpen--;
            if (uiOpen == 0) pressCb = null;
            super.onDetachedFromWindow();
        }

        private void refreshStatus() {
            StringBuilder sb = new StringBuilder("Shizuku: " + readerState);
            if (pending != null) sb.append("\nНажата: ").append(describe(pending)).append(". Коснитесь на схеме, какая это кнопка.");
            else if (current == null) sb.append("\nНажмите кнопку на пульте, и откроются её действия.");
            sb.append("\nДействия при открытом экране не выполняются.");
            synchronized (lock) {
                if (!evlog.isEmpty()) sb.append("\nЖурнал:");
                for (String s : evlog) sb.append("\n  ").append(s);
            }
            status.setText(sb.toString());
        }

        private void pressed() {
            String sig = lastSig;
            if (sig == null) return;
            if (slotOf(sig) != null || hasAnyAct(sig)) { pending = null; edit(sig); }
            else { pending = sig; current = null; panel.removeAllViews(); otherRow(sig); }
            refreshStatus();
            dia.invalidate();
        }

        private boolean hasAnyAct(String sig) { return has(sig, "S") || has(sig, "D") || has(sig, "L"); }

        private void otherRow(final String sig) {
            row(null, "Это другая кнопка (нет на схеме)", new OnClickListener() {
                @Override public void onClick(View v) { pending = null; edit(sig); refreshStatus(); }
            });
        }

        private void row(String emoji, String text, OnClickListener click) {
            Context c = getContext();
            TextView t = tv(c, (emoji == null ? "" : emoji + "  ") + text, 18f, Color.WHITE);
            t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
            t.setMinHeight(dp(c, 56));
            t.setGravity(Gravity.CENTER_VERTICAL);
            if (click != null) t.setOnClickListener(click);
            panel.addView(t, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View sep = new View(c);
            sep.setBackgroundColor(Color.rgb(70, 70, 70));
            panel.addView(sep, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        private void edit(final String sig) {
            current = sig;
            panel.removeAllViews();
            String sl = slotOf(sig);
            String name = sl == null ? "Другая кнопка" : "Кнопка " + slotGlyph(sl);
            row(null, name + ": " + describe(sig), null);
            for (int i = 0; i < 3; i++) {
                final int gi = i;
                String fn = bound.get(sig + "#" + G[i]);
                row(fn == null ? "○" : fnEmoji(fn), G_NAME[i] + ": " + (fn == null ? "не назначено" : fnLabel(fn)),
                        new OnClickListener() { @Override public void onClick(View v) { choose(sig, gi); } });
            }
            row(null, "Закрыть", new OnClickListener() {
                @Override public void onClick(View v) { current = null; panel.removeAllViews(); refreshStatus(); dia.invalidate(); }
            });
            dia.invalidate();
        }

        private void choose(final String sig, final int gi) {
            panel.removeAllViews();
            final String cur = bound.get(sig + "#" + G[gi]);
            row(null, G_NAME[gi] + ":", null);
            row(null, (cur == null ? "✓ " : "") + "Не назначено", new OnClickListener() {
                @Override public void onClick(View v) { setAct(sig, G[gi], null); edit(sig); }
            });
            for (final String[] f : FNS) {
                row(null, (f[0].equals(cur) ? "✓ " : "") + f[1], new OnClickListener() {
                    @Override public void onClick(View v) { setAct(sig, G[gi], f[0]); edit(sig); }
                });
            }
            row(null, "← Назад", new OnClickListener() { @Override public void onClick(View v) { edit(sig); } });
        }

        private String slotGlyph(String id) {
            for (Object[] s : SLOTS) if (id.equals(s[0])) return (String) s[1];
            return id;
        }

        /** Крупная схема пульта: кнопки как на самом пульте, на каждой значки назначенных действий. */
        private final class Diagram extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            Diagram(Context c) { super(c); }

            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                setMeasuredDimension(width, Math.round(width * 1.5f));
            }

            private void text(Canvas c, String s, float x, float y, float size, int color) {
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(size);
                p.setColor(color);
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, p);
            }

            @Override protected void onDraw(Canvas c) {
                float sc = getWidth() / 1000f;
                c.drawColor(Color.rgb(18, 18, 18));
                c.save();
                c.scale(sc, sc);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.rgb(34, 34, 34));
                c.drawRoundRect(new RectF(60, 20, 940, 1460), 420, 420, p);
                for (Object[] s : SLOTS) {
                    String id = (String) s[0];
                    float x = ((Integer) s[2]).intValue(), y = ((Integer) s[3]).intValue(), r = ((Integer) s[4]).intValue();
                    String sig = slotSig(id);
                    boolean sel = sig != null && sig.equals(current);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(sel ? 70 : 46, sel ? 70 : 46, sel ? 70 : 46));
                    c.drawCircle(x, y, r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(sel ? 10 : 5);
                    p.setColor(sel ? Color.WHITE : (sig == null ? Color.rgb(90, 90, 90) : Color.rgb(190, 190, 190)));
                    c.drawCircle(x, y, r - 4, p);
                    String sf = sig == null ? null : bound.get(sig + "#S");
                    String df = sig == null ? null : bound.get(sig + "#D");
                    String lf = sig == null ? null : bound.get(sig + "#L");
                    if (sf == null && df == null && lf == null) {
                        text(c, (String) s[1], x, y, r * 0.8f, Color.rgb(150, 150, 150));
                    } else {
                        text(c, sf == null ? (String) s[1] : fnEmoji(sf), x, y - r * 0.2f, r * 0.8f, sf == null ? Color.rgb(110, 110, 110) : Color.WHITE);
                        String sm = (df == null ? "" : "2×" + fnEmoji(df)) + (df != null && lf != null ? " " : "") + (lf == null ? "" : "⏱" + fnEmoji(lf));
                        text(c, sm, x, y + r * 0.55f, r * 0.34f, Color.WHITE);
                    }
                }
                c.restore();
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return true;
                float sc = getWidth() / 1000f;
                float x = e.getX() / sc, y = e.getY() / sc;
                for (Object[] s : SLOTS) {
                    float dx = x - ((Integer) s[2]).intValue(), dy = y - ((Integer) s[3]).intValue();
                    float r = ((Integer) s[4]).intValue() * 1.15f;
                    if (dx * dx + dy * dy > r * r) continue;
                    String id = (String) s[0];
                    if (pending != null) {
                        prefs.edit().putString("slot_" + id, pending).apply();
                        String sig = pending;
                        pending = null;
                        edit(sig);
                    } else if (slotSig(id) != null) {
                        edit(slotSig(id));
                    } else {
                        current = null;
                        panel.removeAllViews();
                        status.setText("Эта кнопка ещё не привязана: нажмите её на пульте.");
                        return true;
                    }
                    refreshStatus();
                    performClick();
                    return true;
                }
                return true;
            }

            @Override public boolean performClick() { return super.performClick(); }
        }
    }
}
