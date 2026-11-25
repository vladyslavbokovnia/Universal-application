package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.view.InputDevice;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bluetooth-пульт (TikTok и подобные): кнопки читаются из ядра через Shizuku (getevent), работает и с выключенным экраном.
 * Нажатие кнопки на пульте открывает её на схеме, и для неё выбираются три действия: одно, двойное, долгое нажатие.
 * Пока открыт экран настроек, действия не выполняются: нажатия только выбирают кнопку на схеме.
 * Кнопки-«указка» (клик или свайп по экрану, которыми пульт имитирует касания) различаются по движению: тап или свайп
 * в какую сторону, поэтому центральная кнопка не срабатывает от стрелок.
 * Ниже схемы пульта показывается схема геймпада из модуля «Аудиоплеер» (его PadMapper).
 * Действие «Циферблат»: после его нажатия (голос: «Циферблат») стрелки пульта вверх, вправо, вниз, влево переходят на
 * 0, 25, 50, 75% трека (по часовой стрелке, как стик-циферблат геймпада), каждый переход озвучивается; выключается тем же действием или сам через несколько секунд.
 * Будильник: пока он звонит, любая кнопка пульта его выключает (кнопка с назначенным «отложить» откладывает), а действия кнопок
 * в это время не выполняются. Когда будильник не звонит, кнопки работают как обычно: под выключение ничего назначать не нужно.
 */
public class BtRemoteModule implements IPlugin, ISettingsProvider, ISettingsViewProvider {

    private static final String NAME = "BtRemote";
    private static final String PREFS = "module_" + NAME;
    private static final String PLAYER_LIVE_KEY = "manus.audioplayer.live";
    /** Метка модуля «Будильник»: есть, пока будильник звонит. */
    private static final String ALARM_LIVE_KEY = "manus.alarmclock.ringing";

    /** id функции, подпись (эмодзи в начале рисуется на схеме). "ap:<id>" — команда модуля «Аудиоплеер». */
    private static final String[][] FNS = {
            {"play_pause", "⏯ Пауза / воспроизведение"}, {"next", "⏭ Следующий трек"},
            {"prev", "⏮ Предыдущий трек"}, {"ff", "⏩ Перемотка вперёд"}, {"rw", "⏪ Перемотка назад"},
            {"vol_up", "🔊 Громкость +"}, {"vol_down", "🔉 Громкость −"},
            {"ap:play_pause", "▶️ Плеер: пауза / пуск"}, {"ap:next", "➡️ Плеер: следующий трек"},
            {"ap:prev", "⬅️ Плеер: предыдущий трек"}, {"ap:seek_back", "↩️ Плеер: назад на шаг"},
            {"ap:seek_fwd", "↪️ Плеер: вперёд на шаг"}, {"ap:later", "🕓 Плеер: послушать позже"},
            {"ap:fav", "⭐ Плеер: в избранное"}, {"ap:pl_switch", "🔀 Плеер: сменить список"},
            {"ap:say", "🗣 Плеер: назвать трек"}, {"ap:say_pct", "📊 Плеер: сколько прослушано"},
            {"ap:speed_up", "🐇 Плеер: быстрее"}, {"ap:speed_down", "🐢 Плеер: медленнее"},
            {"ap:speed_reset", "🎚 Плеер: скорость 1×"}, {"ap:reverse", "🔃 Плеер: обратный порядок"},
            {"ap:stop_after", "🛑 Плеер: стоп после трека"}, {"ap:lock", "🔒 Плеер: блок касаний"},
            {"ap:auto_delete", "🗑 Плеер: автоудаление (вкл/выкл)"},
            {"dial", "🕒 Циферблат: стрелки → 0/25/50/75% трека"},
            {"alarm_snooze", "💤 Будильник: отложить (только пока звонит)"},
    };
    // «Будильник: выключить» в списке нет намеренно: пока будильник звонит, его выключает любая кнопка пульта (alarmIntercept),
    // а в остальное время кнопки работают как обычно, и ни одна из них под выключение не занята.

    /** Типы нажатия: S — одно, D — двойное, L — долгое. */
    private static final String[] G = {"S", "D", "L"};
    private static final String[] G_NAME = {"Одно нажатие", "Двойное нажатие", "Долгое нажатие"};

    /** Кнопки на схеме: id, подпись, x, y, радиус (поле 1000 × 1500). */
    private static final Object[][] SLOTS = {
            {"up", "▲", 500, 250, 120}, {"left", "◀", 290, 460, 120}, {"right", "▶", 710, 460, 120},
            {"down", "▼", 500, 670, 120}, {"heart", "♥", 500, 460, 105}, {"cam", "📷", 500, 1120, 150},
    };

    private static final long GESTURE_GAP_MS = 150;

    /** Циферблат: стрелки схемы по часовой стрелке (вверх, вправо, вниз, влево) и доля трека, на которую каждая переходит. */
    private static final String[] DIAL_SLOTS = {"up", "right", "down", "left"};
    private static final int[] DIAL_PCT = {0, 25, 50, 75};

    private Context ctx;
    private Handler main;
    private SharedPreferences prefs;

    /** "<кнопка>#<тип>" -> id функции */
    private final Map<String, String> bound = new ConcurrentHashMap<String, String>();
    private volatile int uiOpen;                       // открытых экранов настроек: действия не выполняются
    private volatile String lastSig;
    private volatile long lastSigAt;
    private volatile Runnable pressCb;                 // уведомление экрана настроек (главный поток)

    private volatile boolean dialMode;                 // режим «Циферблат»: стрелки переходят по месту в треке
    private Runnable dialTimer;                        // автовыключение режима (главный поток)

    private static final class KeyState {
        boolean down, longFired, suppress;
        Runnable longTimer, tapTimer;
    }
    private final Map<String, KeyState> keyStates = new HashMap<String, KeyState>();
    /** Нажатие, начатое «прыжком указки»: по нему идёт таймер долгого нажатия (главный поток). */
    private static final class Pre { String key; long at; boolean fired; Runnable timer; }
    private Pre pre;
    /** «Прыжок указки» -> кнопка (выучивается по коротким нажатиям); "?" — у разных кнопок одинаковый прыжок. */
    private final Map<String, String> preMap = new ConcurrentHashMap<String, String>();

    /** Состояние «указки» одного устройства (поток чтения). */
    private static final class Ptr {
        boolean down;
        long t0;
        float sx, sy, cx, cy, rx, ry;
        long upAt, jAt;                        // конец прошлого жеста; последний «прыжок» указки
        float jx, jy, ox, oy;                  // «прыжок» в угол и сдвиг после него
        int oc;                                // сколько компонент сдвига уже пришло
    }
    private final Map<String, Ptr> ptrs = new HashMap<String, Ptr>();
    private final Set<String> excluded = new HashSet<String>();    // сенсорный экран и кнопки самого телефона

    // диагностика кнопок
    private volatile String capLabel;
    private long capT0;
    private final StringBuilder capBuf = new StringBuilder();
    private final Map<String, Integer> capCount = new HashMap<String, Integer>();
    private volatile String devDump = "";
    private volatile Runnable diagCb;

    // геймпад через getevent: кнопки и оси идут в PadMapper модуля «Аудиоплеер», в том числе при выключенном экране
    private static final Pattern ABS_RE = Pattern.compile("(ABS_[A-Z0-9_]+)\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
    private final Map<String, Map<String, int[]>> absRange = new HashMap<String, Map<String, int[]>>();
    private final Set<String> padPaths = new HashSet<String>();
    private final Map<String, float[]> padAxes = new HashMap<String, float[]>();     // lx, ly, rx, ry, hx, hy, lt, rt
    private final Map<String, float[]> padSent = new HashMap<String, float[]>();
    private final Map<String, Boolean> padVirt = new HashMap<String, Boolean>();

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
    @Override public int getVersion() { return 13; }
    @Override public String getDescription() {
        return "Bluetooth-пульт через Shizuku: схема кнопок, одно/двойное/долгое нажатие, команды плеера, циферблат по треку, выключение будильника любой кнопкой";
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
        dialMode = false;
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

    /**
     * Читает «getevent -lp»: сенсорный экран (INPUT_PROP_DIRECT) и кнопки самого телефона (шина 0019) пульта не касаются,
     * их не читаем; геймпады (есть BTN_GAMEPAD/BTN_SOUTH/BTN_TL) и пределы осей запоминаем; текст описания устройств идёт в отчёт.
     */
    private void scanExcluded() {
        try {
            String out = ShizukuBridge.exec("getevent -lp").getOut();
            Set<String> ex = new HashSet<String>(), pads = new HashSet<String>();
            Map<String, Map<String, int[]>> ranges = new HashMap<String, Map<String, int[]>>();
            StringBuilder dump = new StringBuilder(), blk = new StringBuilder();
            Map<String, int[]> rng = new HashMap<String, int[]>();
            String path = null, bus = "";
            boolean direct = false, pad = false;
            for (String raw : (out + "\nadd device").split("\n")) {
                String s = raw.trim();
                if (s.startsWith("add device")) {
                    if (path != null) {
                        if (direct || "0019".equals(bus)) ex.add(path);
                        else { dump.append(blk).append('\n'); ranges.put(path, rng); if (pad) pads.add(path); }
                    }
                    int i = s.indexOf(": ");
                    path = i > 0 ? s.substring(i + 2).trim() : null;
                    bus = ""; direct = false; pad = false;
                    blk.setLength(0); blk.append(s).append('\n');
                    rng = new HashMap<String, int[]>();
                    continue;
                }
                blk.append(raw).append('\n');
                if (s.startsWith("bus:")) bus = s.substring(4).trim();
                if (s.contains("INPUT_PROP_DIRECT")) direct = true;
                if (s.contains("BTN_GAMEPAD") || s.contains("BTN_SOUTH") || s.contains("BTN_TL")) pad = true;
                Matcher m = ABS_RE.matcher(s);
                if (m.find()) rng.put(m.group(1), new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))});
            }
            synchronized (lock) { excluded.clear(); excluded.addAll(ex); devDump = dump.toString(); }
            padPaths.clear(); padPaths.addAll(pads);
            absRange.clear(); absRange.putAll(ranges);
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
        synchronized (lock) { if (excluded.contains(path)) return; }
        capture(path, s);
        if (padPaths.contains(path)) { handlePad(path, type, code, val); return; }
        if ("EV_SYN".equals(type) || "EV_MSC".equals(type) || "EV_LED".equals(type)) return;

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
                else { preamble(p, code, v); addRel(dev, "REL_X".equals(code), v); }
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
                    postMain(new Runnable() { @Override public void run() { cancelPre(); } });
                } else if ("UP".equals(val) && p.down) {
                    p.down = false;
                    p.upAt = SystemClock.uptimeMillis();
                    float dx = p.cx - p.sx + p.rx, dy = p.cy - p.sy + p.ry;
                    float ax = Math.abs(dx), ay = Math.abs(dy);
                    int min = prefs != null ? prefs.getInt("swipe_min", 20) : 20;
                    String g = Math.max(ax, ay) < min ? "TAP"
                            : ay >= ax ? (dy > 0 ? "SWIPE_DOWN" : "SWIPE_UP") : (dx > 0 ? "SWIPE_RIGHT" : "SWIPE_LEFT");
                    Long ka; synchronized (keyAt) { ka = keyAt.get(devBase(dev)); }
                    if ("TAP".equals(g) && ka != null && SystemClock.uptimeMillis() - ka < 500) return; // камера: клавиша + тап, тап лишний
                    final String psig = "P|" + g + "|" + dev;
                    final long pdur = SystemClock.uptimeMillis() - p.t0;
                    postMain(new Runnable() { @Override public void run() { pointerGesture(psig, pdur); } });
                }
                return;
            }
            final String sig = "K|" + code + "|" + dev;
            if ("DOWN".equals(val)) synchronized (keyAt) { keyAt.put(devBase(dev), SystemClock.uptimeMillis()); }
            if ("DOWN".equals(val)) postMain(new Runnable() { @Override public void run() { keyDown(sig); } });
            else if ("UP".equals(val)) postMain(new Runnable() { @Override public void run() { keyUp(sig); } });
            else if ("REPEAT".equals(val)) postMain(new Runnable() { @Override public void run() { keyRepeat(sig); } });
        }
    }

    private final java.util.HashMap<String, Long> keyAt = new java.util.HashMap<String, Long>();
    /** «BLE-M3 Mouse» и «BLE-M3 Consumer Control» — один пульт: общая часть имени до первого пробела. */
    private static String devBase(String dev) { int i = dev.indexOf(' '); return i > 0 ? dev.substring(0, i) : dev; }

    private void postPress(final String sig, final long dur) {
        postMain(new Runnable() { @Override public void run() { pressDone(sig, dur); } });
    }

    /**
     * «Прыжок указки» (поток чтения): в начале каждого нажатия пульт бросает указку в угол и сдвигает её на величину,
     * своя у каждой кнопки. Пока кнопку держат, приходит только он: по нему запускается таймер долгого нажатия.
     * Такой же прыжок пульт делает сразу после жеста (хвост): его отсекаем по времени.
     */
    private void preamble(Ptr p, String code, int v) {
        boolean isX = "REL_X".equals(code);
        long now = SystemClock.uptimeMillis();
        if (now - p.upAt < 250) return;
        if (Math.abs(v) >= 1500) {
            if (now - p.jAt > 150) { p.jx = 0; p.jy = 0; p.ox = 0; p.oy = 0; p.oc = 0; }
            p.jAt = now;
            if (isX) p.jx = v; else p.jy = v;
            return;
        }
        if (p.jAt == 0 || now - p.jAt > 150) return;
        if (isX) p.ox = v; else p.oy = v;
        if (p.oc++ > 0) return;
        final Ptr fp = p;
        Handler h = main;
        if (h != null) h.postDelayed(new Runnable() { @Override public void run() { armPre(preKey(fp)); } }, 40);
    }

    private static String preKey(Ptr p) {
        return "c" + (p.jx > 0 ? "p" : "m") + (p.jy > 0 ? "p" : "m") + "_" + (int) p.ox + "_" + (int) p.oy;
    }

    /** Начало нажатия: если для этого прыжка известна кнопка с долгим действием, запускается таймер (главный поток). */
    private void armPre(String key) {
        cancelPre();
        final Pre pr = new Pre();
        pr.key = key;
        pr.at = SystemClock.uptimeMillis();
        pre = pr;
        if (uiOpen > 0 || alarmRinging()) return;      // при звонке долгое действие не запускаем: жест выключит будильник
        final String sig = preMap.get(key);
        if (sig == null || "?".equals(sig) || !has(sig, "L")) return;
        if (dialMode && dialIndex(sig) >= 0) return;   // циферблат: стрелка не должна запускать своё долгое действие
        pr.timer = new Runnable() {
            @Override public void run() {
                pr.timer = null;
                if (pre == pr && uiOpen == 0) { pr.fired = true; fire(sig, "L"); }
            }
        };
        main.postDelayed(pr.timer, longMs());
    }

    private void cancelPre() {
        Pre pr = pre;
        if (pr != null && pr.timer != null) { main.removeCallbacks(pr.timer); pr.timer = null; }
    }

    /** Жест указки закончился (главный поток): если долгое уже выполнено по таймеру, жест пропускается, иначе идёт как обычно. */
    private void pointerGesture(String sig, long dur) {
        Pre pr = pre;
        cancelPre();
        pre = null;
        if (pr != null && pr.fired) {
            synchronized (lock) { evlog.addFirst("   жест после долгого пропущен"); while (evlog.size() > 12) evlog.removeLast(); }
            return;
        }
        learnPre(pr, sig);
        pressDone(sig, dur);
    }

    /** Прыжок указки перед жестом запоминается за кнопкой этого жеста; если за одним прыжком пришли разные жесты, он помечается "?". */
    private void learnPre(Pre pr, String sig) {
        if (pr == null || sig == null || prefs == null) return;
        if (SystemClock.uptimeMillis() - pr.at > 500) return;
        String old = preMap.get(pr.key);
        if (old == null) {
            preMap.put(pr.key, sig);
            prefs.edit().putString("pre#" + pr.key, sig).apply();
        } else if (!old.equals(sig) && !"?".equals(old)) {
            preMap.put(pr.key, "?");
            prefs.edit().putString("pre#" + pr.key, "?").apply();
        }
    }

    /** Выученные соответствия «прыжок указки -> кнопка» из настроек. */
    private void loadPre() {
        Map<String, String> pm = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("pre#") && e.getValue() instanceof String) pm.put(k.substring(4), (String) e.getValue());
        }
        preMap.clear();
        preMap.putAll(pm);
    }

    private void addRel(String dev, boolean isX, int v) {
        synchronized (lock) { if (isX) gdx += v; else gdy += v; gDev = dev; }
        if (main != null) { main.removeCallbacks(gestureEnd); main.postDelayed(gestureEnd, GESTURE_GAP_MS); }
    }

    /** Пауза в движении указки без нажатия: заметный рывок становится кнопкой. */
    private void finishGesture() {
        float dx, dy; String dev;
        synchronized (lock) { dx = gdx; dy = gdy; dev = gDev; gdx = 0; gdy = 0; }
        if (true) return; // рывки указки пульт делает сам после каждой кнопки: это шум, а не кнопка
        float ax = Math.abs(dx), ay = Math.abs(dy), mx = Math.max(ax, ay);
        int min = prefs != null ? prefs.getInt("swipe_min", 20) : 20;
        if (mx < min) return;
        String d = (ay >= mx * 0.4f ? (dy > 0 ? "DOWN" : "UP") : "") + (ax >= mx * 0.4f ? (dx > 0 ? "RIGHT" : "LEFT") : "");
        pressDone("R|MOVE_" + d + "|" + dev, 0);
    }

    // ------------------------------------------------------------------ диагностика кнопок

    /** Запись сырых строк getevent выбранной кнопки (поток чтения). */
    private void capture(String path, String raw) {
        if (capLabel == null) return;
        String dev;
        synchronized (devNames) { dev = devNames.get(path); }
        synchronized (lock) {
            String label = capLabel;
            if (label == null || capBuf.length() > 60000) return;
            capBuf.append('+').append(SystemClock.uptimeMillis() - capT0).append(' ').append(dev == null ? path : dev)
                    .append(' ').append(raw.substring(raw.indexOf(':') + 1).trim()).append('\n');
            if (!raw.contains("EV_SYN")) { Integer n = capCount.get(label); capCount.put(label, n == null ? 1 : n + 1); }
        }
    }

    private void startCapture(final String label, long ms) {
        synchronized (lock) {
            capLabel = label;
            capT0 = SystemClock.uptimeMillis();
            capBuf.append("\n== ").append(label).append(" ==\n");
            capCount.put(label, 0);
        }
        if (main == null) return;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (lock) { if (label.equals(capLabel)) capLabel = null; }
                Runnable cb = diagCb;
                if (cb != null) cb.run();
            }
        }, ms);
    }

    /** Для общей диагностики хоста (значок в верхней панели). */
    public String diagnosticsReport() { return report(); }

    private String report() {
        String pads;
        try { pads = String.valueOf(padPaths); } catch (RuntimeException e) { pads = "?"; }
        StringBuilder sb = new StringBuilder("BtRemote v").append(getVersion()).append('\n');
        sb.append("Shizuku: ").append(readerState).append('\n');
        synchronized (lock) {
            sb.append("исключены: ").append(excluded).append("\nгеймпады: ").append(pads).append("\nжурнал:\n");
            for (String s : evlog) sb.append("  ").append(s).append('\n');
            sb.append(capBuf);
        }
        sb.append("\n== устройства ==\n").append(devDump);
        String r = sb.toString();
        return r.length() > 90000 ? r.substring(0, 90000) : r;
    }

    // ------------------------------------------------------------------ геймпад через getevent (работает и при выключенном экране)

    private static final Map<String, Integer> PAD_KEYS = new HashMap<String, Integer>();
    static {
        String[] n = {"BTN_GAMEPAD", "BTN_SOUTH", "BTN_A", "BTN_EAST", "BTN_B", "BTN_NORTH", "BTN_X", "BTN_WEST", "BTN_Y", "BTN_TL", "BTN_TR",
                "BTN_TL2", "BTN_TR2", "BTN_SELECT", "BTN_START", "BTN_MODE", "BTN_THUMBL", "BTN_THUMBR",
                "BTN_DPAD_UP", "BTN_DPAD_DOWN", "BTN_DPAD_LEFT", "BTN_DPAD_RIGHT"};
        int[] k = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
        for (int i = 0; i < n.length; i++) PAD_KEYS.put(n[i], Integer.valueOf(k[i]));
    }

    /** События геймпада из getevent (поток чтения): кнопки и оси переводятся в события Android и идут в PadMapper плеера. */
    private void handlePad(String path, String type, String code, String val) {
        if ("EV_KEY".equals(type)) {
            Integer k = PAD_KEYS.get(code);
            if (k == null) return;
            if ("DOWN".equals(val)) postPadKey(k.intValue(), true);
            else if ("UP".equals(val)) postPadKey(k.intValue(), false);
            return;
        }
        float[] a = padAxes.get(path);
        if (a == null) { a = new float[8]; padAxes.put(path, a); padSent.put(path, new float[8]); }
        if ("EV_SYN".equals(type)) {
            float[] last = padSent.get(path);
            boolean changed = false;
            for (int i = 0; i < 6; i++) if (a[i] != last[i]) { changed = true; break; }
            if (changed) { System.arraycopy(a, 0, last, 0, 8); postPadAxes(a.clone()); }
            virt(path, KeyEvent.KEYCODE_BUTTON_L2, a[6]);
            virt(path, KeyEvent.KEYCODE_BUTTON_R2, a[7]);
            return;
        }
        if (!"EV_ABS".equals(type)) return;
        Map<String, int[]> rg = absRange.get(path);
        int[] r = rg == null ? null : rg.get(code);
        int v = parseHex(val);
        float span = r == null ? 0f : (float) (r[1] - r[0]);
        float n = span == 0f ? 0f : (v - r[0]) * 2f / span - 1f;
        float u = span == 0f ? 0f : (v - r[0]) / span;
        boolean hasRx = rg != null && rg.containsKey("ABS_RX");
        if ("ABS_X".equals(code)) a[0] = n;
        else if ("ABS_Y".equals(code)) a[1] = n;
        else if ("ABS_RX".equals(code)) a[2] = n;
        else if ("ABS_RY".equals(code)) a[3] = n;
        else if ("ABS_Z".equals(code)) { if (hasRx) a[6] = u; else a[2] = n; }
        else if ("ABS_RZ".equals(code)) { if (hasRx) a[7] = u; else a[3] = n; }
        else if ("ABS_BRAKE".equals(code)) a[6] = u;
        else if ("ABS_GAS".equals(code)) a[7] = u;
        else if ("ABS_HAT0X".equals(code)) a[4] = v;
        else if ("ABS_HAT0Y".equals(code)) a[5] = v;
    }

    /** Аналоговые курки как кнопки L2/R2 с гистерезисом 0.55 / 0.35. */
    private void virt(String path, int key, float v) {
        String k = path + key;
        Boolean cur = padVirt.get(k);
        boolean on = (cur != null && cur.booleanValue()) ? v > 0.35f : v > 0.55f;
        if (cur == null ? on : on != cur.booleanValue()) { padVirt.put(k, Boolean.valueOf(on)); postPadKey(key, on); }
    }

    /** PadMapper модуля «Аудиоплеер» (его закрытое поле mapper); null, если плеер не запущен. */
    private PadMapper padMapper() {
        try {
            Object ap = System.getProperties().get(PLAYER_LIVE_KEY);
            if (ap == null) return null;
            Field f = ap.getClass().getDeclaredField("mapper");
            f.setAccessible(true);
            Object m = f.get(ap);
            return m instanceof PadMapper ? (PadMapper) m : null;
        } catch (Throwable t) { return null; }
    }

    private void postPadKey(final int key, final boolean down) {
        postMain(new Runnable() {
            @Override public void run() {
                PadMapper m = padMapper();
                if (m == null) return;
                long t = SystemClock.uptimeMillis();
                m.onKeyEvent(new KeyEvent(t, t, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, key, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD));
            }
        });
    }

    private void postPadAxes(final float[] a) {
        postMain(new Runnable() {
            @Override public void run() {
                PadMapper m = padMapper();
                if (m == null) return;
                try {
                    MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
                    pp.id = 0;
                    pp.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
                    MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
                    pc.setAxisValue(MotionEvent.AXIS_X, a[0]);
                    pc.setAxisValue(MotionEvent.AXIS_Y, a[1]);
                    pc.setAxisValue(MotionEvent.AXIS_Z, a[2]);          // PadMapper без устройства читает правый стик из Z и RZ
                    pc.setAxisValue(MotionEvent.AXIS_RZ, a[3]);
                    pc.setAxisValue(MotionEvent.AXIS_HAT_X, a[4]);
                    pc.setAxisValue(MotionEvent.AXIS_HAT_Y, a[5]);
                    long t = SystemClock.uptimeMillis();
                    MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 1,
                            new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_JOYSTICK, 0);
                    m.onMotionEvent(e);
                    e.recycle();
                } catch (Throwable t) { }
            }
        });
    }

    // ------------------------------------------------------------------ одно, двойное, долгое нажатие

    private long longMs() { return prefs != null ? prefs.getInt("long_ms", 600) : 600; }
    private long doubleMs() { return prefs != null ? prefs.getInt("double_ms", 350) : 350; }
    private boolean has(String sig, String g) { return bound.containsKey(sig + "#" + g); }

    private void fire(String sig, String g) {
        if (uiOpen > 0) return;                       // экран настроек открыт: нажатия только выбирают кнопку
        String fn = bound.get(sig + "#" + g);
        if (fn != null) {
            synchronized (lock) {
                evlog.addFirst("   ⚡ " + g + " → " + fn);
                while (evlog.size() > 12) evlog.removeLast();
                if (capLabel != null) capBuf.append("  FIRE ").append(g).append(' ').append(fn).append('\n');
            }
            Runnable cb = pressCb; if (cb != null) cb.run();
            run(fn);
        }
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
            if (capLabel != null && capBuf.length() < 60000) capBuf.append("  -> ").append(sig).append('\n');
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
        if (alarmIntercept(sig)) { st.suppress = true; return; }  // будильник звонит: кнопка его выключает
        if (dialIntercept(sig)) { st.suppress = true; return; }   // циферблат: стрелка = переход по треку
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
        if (uiOpen > 0 || has(sig, "L") || has(sig, "D") || alarmRinging()) return;
        if (dialMode && dialIndex(sig) >= 0) return;
        String fn = bound.get(sig + "#S");
        if ("vol_up".equals(fn) || "vol_down".equals(fn)) run(fn);
    }

    /** Завершённое нажатие указки или колеса: длительность известна только в конце, поэтому долгое срабатывает при отпускании. */
    private void pressDone(String sig, long dur) {
        note(sig);
        if (dur > 0) synchronized (lock) { if (!evlog.isEmpty()) evlog.set(0, evlog.getFirst() + " · " + dur + " мс"); }
        if (uiOpen > 0) return;
        if (alarmIntercept(sig)) return;              // будильник звонит: кнопка его выключает
        if (dialIntercept(sig)) return;               // циферблат: стрелка = переход по треку
        if (dur >= longMs() && has(sig, "L")) { fire(sig, "L"); return; }
        shortPress(sig, stateOf(sig), false);
    }

    // ------------------------------------------------------------------ будильник: звонит -> любая кнопка выключает

    /** Модуль «Будильник» сейчас звонит. */
    private static boolean alarmRinging() {
        try { return System.getProperties().get(ALARM_LIVE_KEY) != null; } catch (Throwable t) { return false; }
    }

    /**
     * Пока будильник звонит, нажатие любой кнопки пульта выключает его, а действие кнопки не выполняется
     * (кнопка с назначенным «Будильник: отложить» откладывает). Когда будильник молчит, возвращает false и кнопки работают как обычно.
     * Главный поток; при открытом экране настроек не срабатывает.
     */
    private boolean alarmIntercept(String sig) {
        if (uiOpen > 0 || !alarmRinging()) return false;
        boolean snooze = "alarm_snooze".equals(bound.get(sig + "#S"));
        synchronized (lock) {
            evlog.addFirst("   ⏰ звонит: " + (snooze ? "отложить" : "выключить"));
            while (evlog.size() > 12) evlog.removeLast();
        }
        runAlarm(snooze ? "snooze" : "dismiss");
        return true;
    }

    // ------------------------------------------------------------------ циферблат: стрелки пульта -> место в треке

    /** Номер стрелки циферблата (0 вверх, 1 вправо, 2 вниз, 3 влево) для кнопки; -1, если это не стрелка. */
    private int dialIndex(String sig) {
        if (sig == null || prefs == null) return -1;
        for (int i = 0; i < DIAL_SLOTS.length; i++) {
            if (sig.equals(slotSig(DIAL_SLOTS[i]))) return i;
        }
        return -1;
    }

    /** Режим «Циферблат» включён и нажата стрелка: переход по треку вместо её обычного действия (главный поток). */
    private boolean dialIntercept(String sig) {
        if (!dialMode || uiOpen > 0) return false;
        int i = dialIndex(sig);
        if (i < 0) return false;
        dialJump(DIAL_PCT[i]);
        return true;
    }

    /** Действие «Циферблат»: включает режим стрелок (голос: «Циферблат») или выключает его. */
    private void toggleDial() {
        if (dialMode) { dialStop(true); return; }
        if (playerObj() == null) { toast("Аудиоплеер не запущен"); return; }
        dialMode = true;
        armDialTimer();
        sayPlayer("Циферблат");
    }

    private void dialStop(boolean say) {
        dialMode = false;
        Handler h = main;
        if (h != null && dialTimer != null) h.removeCallbacks(dialTimer);
        dialTimer = null;
        if (say) sayPlayer("Циферблат выключен");
    }

    /** Режим сам выключается через «dial_sec» секунд без нажатий стрелок; каждый переход отсчёт начинает заново. */
    private void armDialTimer() {
        final Handler h = main;
        if (h == null) return;
        if (dialTimer != null) h.removeCallbacks(dialTimer);
        dialTimer = new Runnable() {
            @Override public void run() {
                dialTimer = null;
                if (dialMode) dialStop(true);
            }
        };
        h.postDelayed(dialTimer, (prefs != null ? prefs.getInt("dial_sec", 10) : 10) * 1000L);
    }

    /** Переход на долю трека: тот же закрытый seekFractionAny плеера, что у стика-циферблата геймпада; голосом называется процент. */
    private void dialJump(int pct) {
        armDialTimer();
        try {
            Object ap = playerObj();
            if (ap == null) { toast("Аудиоплеер не запущен"); return; }
            Method m = ap.getClass().getDeclaredMethod("seekFractionAny", float.class);
            m.setAccessible(true);
            m.invoke(ap, Float.valueOf(pct / 100f));
            synchronized (lock) {
                evlog.addFirst("   🕒 переход на " + pct + "%");
                while (evlog.size() > 12) evlog.removeLast();
            }
            sayPlayer(pct + " процентов");
        } catch (Throwable t) { t.printStackTrace(); toast("Не удалось перемотать трек"); }
    }

    private Object playerObj() {
        try { return System.getProperties().get(PLAYER_LIVE_KEY); } catch (Throwable t) { return null; }
    }

    /** Озвучка голосом плеера (его закрытый speak); если плеера нет, текст показывается на экране. */
    private void sayPlayer(String text) {
        try {
            Object ap = playerObj();
            if (ap == null) { toast(text); return; }
            Method m = ap.getClass().getDeclaredMethod("speak", String.class);
            m.setAccessible(true);
            m.invoke(ap, text);
        } catch (Throwable t) { toast(text); }
    }

    // ------------------------------------------------------------------ назначения

    private static String oldKey(String fn, String g) { return ("S".equals(g) ? "bind_" : "bind" + g + "_") + fn; }

    /** Назначения прежних версий (по функциям) переносятся в «кнопка#тип -> функция». */
    private void migrate() {
        migrateOld();
        cleanRemoved();
        cleanAlarmDismiss();
    }

    /** «Будильник: выключить» больше не назначается на кнопки (выключает любая кнопка, пока звонит): прежние назначения снимаются, кнопки освобождаются. */
    private void cleanAlarmDismiss() {
        if (prefs.getBoolean("mig13", false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith("act#") && "alarm_dismiss".equals(e.getValue())) ed.remove(e.getKey());
        }
        ed.putBoolean("mig13", true);
        ed.apply();
    }

    /** Убранные из списка действия (стоп, без звука): прежние назначения на них сбрасываются. */
    private void cleanRemoved() {
        if (prefs.getBoolean("mig6", false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith("act#") && ("stop".equals(e.getValue()) || "mute".equals(e.getValue()))) ed.remove(e.getKey());
        }
        ed.putBoolean("mig6", true);
        ed.apply();
    }

    private void migrateOld() {
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
        loadPre();
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
        if ("dial".equals(fn)) { toggleDial(); return; }
        if (fn.startsWith("alarm_")) { runAlarm(fn.substring(6)); return; }
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

    /**
     * Команды модуля «Будильник» (отложить, выключить): его приёмник команд ALARM_SNOOZE / ALARM_DISMISS.
     * Звонящий будильник их выполняет и сам озвучивает («Отложено на 10 минут», «Будильник выключен»); если не звонит, ничего не происходит.
     */
    private void runAlarm(String cmd) {
        Context c = ctx;
        if (c == null) return;
        try {
            Intent i = new Intent("snooze".equals(cmd) ? "im.manus.universalhost.ALARM_SNOOZE" : "im.manus.universalhost.ALARM_DISMISS");
            i.setPackage(c.getPackageName());
            c.sendBroadcast(i);
        } catch (Throwable t) { t.printStackTrace(); toast("Не удалось передать команду будильнику"); }
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
        l.add(SettingItem.slider("dial_sec", "🕒 Циферблат: ждать стрелку, с", "Через сколько секунд без нажатий стрелок режим «Циферблат» выключится сам", 3, 30, 1, 10));
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
                if (k.startsWith("act#") || k.startsWith("slot_") || k.startsWith("pre#")) ed.remove(k);
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
            addView(diagView(c));
            TextView h = tv(c, "🎮 Геймпад (схема из плеера)", 18f, Color.WHITE);
            h.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 8));
            addView(h);
            addView(gamepadView(c));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            uiOpen++;
            pressCb = onPress;
            diagCb = onDiag;
            refreshStatus();
        }

        @Override protected void onDetachedFromWindow() {
            if (uiOpen > 0) uiOpen--;
            if (uiOpen == 0) { pressCb = null; diagCb = null; }
            super.onDetachedFromWindow();
        }

        private void refreshStatus() {
            StringBuilder sb = new StringBuilder("Shizuku: " + readerState);
            if (pending != null) sb.append("\nНажата: ").append(describe(pending)).append(". Коснитесь на схеме, какая это кнопка.");
            else if (current == null) sb.append("\nНажмите кнопку на пульте, и откроются её действия.");
            sb.append("\nДействия при открытом экране не выполняются.");
            sb.append("\nКогда звонит будильник, любая кнопка пульта его выключает: назначать это не нужно.");
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

        private final Runnable onDiag = new Runnable() { @Override public void run() { refreshDiag(); } };
        private final List<TextView> diagRows = new ArrayList<TextView>();
        private final String[] diagNames = {"▲ вверх", "◀ влево", "▶ вправо", "▼ вниз", "♥ сердечко", "📷 камера", "🎮 геймпад (любая кнопка)"};

        private void refreshDiag() {
            for (int i = 0; i < diagRows.size(); i++) {
                Integer n;
                synchronized (lock) { n = capCount.get(diagNames[i]); }
                String mark = diagNames[i].equals(capLabel) ? "⏺ нажмите сейчас…" : (n == null ? "" : "событий: " + n);
                diagRows.get(i).setText(diagNames[i] + (mark.length() > 0 ? "  —  " + mark : ""));
            }
        }

        private TextView diagButton(final Context c, String text, OnClickListener click) {
            TextView r = tv(c, text, 16f, Color.WHITE);
            r.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
            r.setBackgroundColor(Color.rgb(26, 26, 26));
            r.setOnClickListener(click);
            return r;
        }

        /** Диагностика: записывает сырые события каждой кнопки, отчёт можно скопировать или отправить. */
        private LinearLayout diagView(final Context c) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(VERTICAL);
            TextView h = tv(c, "🔬 Диагностика кнопок", 18f, Color.WHITE);
            h.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 4));
            box.addView(h);
            TextView hint = tv(c, "Нажмите строку и в течение 4 секунд один раз нажмите эту кнопку на пульте. Когда запишете все, нажмите значок диагностики 📋 вверху главного экрана (долгое нажатие: отправить) и вставьте текст сюда.", 13f, Color.LTGRAY);
            hint.setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 8));
            box.addView(hint);
            for (final String n : diagNames) {
                TextView r = diagButton(c, n, new OnClickListener() {
                    @Override public void onClick(View v) {
                        startCapture(n, 4000);
                        refreshDiag();
                        Toast.makeText(c, "Нажмите кнопку сейчас (4 с)", Toast.LENGTH_SHORT).show();
                    }
                });
                diagRows.add(r);
                box.addView(r, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            refreshDiag();
            return box;
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
