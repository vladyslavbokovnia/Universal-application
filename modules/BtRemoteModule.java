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
import java.util.Collections;
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
 * Контексты «активная программа» (CTXS): пока программа активна (будильник звонит), кнопки работают в её режиме: действия
 * «при звонке» назначаются отдельно от обычных (на одну кнопку можно повесить и функцию плеера, и действие будильника),
 * а кнопки без назначения выполняют действие по умолчанию (у будильника это «отложить»). Другая программа добавляется строкой в CTXS.
 * Настройка «Забирать пульт у Android»: помощник (класс Grab, запускается через Shizuku от имени оболочки) берёт устройства пульта
 * в исключительное пользование (EVIOCGRAB), Android перестаёт получать их события (указка и клавиши не дёргают экран), а модуль
 * получает те же события от помощника.
 */
public class BtRemoteModule implements IPlugin, ISettingsProvider, ISettingsViewProvider {

    private static final String NAME = "BtRemote";
    private static final String PREFS = "module_" + NAME;
    private static final String PLAYER_LIVE_KEY = "manus.audioplayer.live";
    /**
     * Контекст «активная программа». Пока в System properties есть метка liveKey, кнопки работают в режиме этой программы:
     * назначения берутся из её собственной таблицы (fns — что можно назначать), а одиночное нажатие без назначения выполняет fallback.
     */
    private static final class Ctx {
        final String id, label, liveKey, fallback;
        final String[][] fns;
        Ctx(String id, String label, String liveKey, String fallback, String[][] fns) {
            this.id = id; this.label = label; this.liveKey = liveKey; this.fallback = fallback; this.fns = fns;
        }
    }

    /** Известные активные программы. Новая программа: строка здесь + метка liveKey в System properties, пока она активна. */
    private static final Ctx[] CTXS = {
            new Ctx("alarm", "⏰ Будильник звонит", "manus.alarmclock.ringing", "alarm_snooze",
                    new String[][]{{"alarm_snooze", "💤 Отложить"}, {"alarm_dismiss", "⏹ Выключить"}}),
    };

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
    };
    // Действий будильника (отложить, выключить) здесь нет: они назначаются отдельно, «при звонке» (CTXS), на ту же кнопку,
    // что и функция плеера, и не отнимают её.

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
    /** "<программа>#<кнопка>#<тип>" -> id функции: назначения «при активной программе» (см. CTXS). */
    private final Map<String, String> ctxBound = new ConcurrentHashMap<String, String>();
    private volatile int uiOpen;                       // открытых экранов настроек: действия не выполняются
    private volatile String lastSig;
    private volatile long lastSigAt;
    private volatile Runnable pressCb;                 // уведомление экрана настроек (главный поток)

    private volatile boolean dialMode;                 // режим «Циферблат»: стрелки переходят по месту в треке
    private Runnable dialTimer;                        // автовыключение режима (главный поток)

    // захват пульта: помощник от имени оболочки забирает устройства пульта у Android и сам присылает их события
    private final Object grabLock = new Object();
    private final Object lineLock = new Object();      // строки getevent и помощника разбирает один поток за раз
    private int grabGen;                               // номер текущего цикла захвата (под grabLock)
    private volatile Process grabProc;
    private volatile String grabState = "выключено";
    private final LinkedList<String> grabLog = new LinkedList<String>();
    private final Set<String> grabbed = new HashSet<String>();

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
    private final Runnable readyCb = new Runnable() { @Override public void run() { startReader(); startGrab(); } };

    @Override public String getName() { return NAME; }
    @Override public int getVersion() { return 15; }
    @Override public String getDescription() {
        return "Bluetooth-пульт через Shizuku: схема кнопок, одно/двойное/долгое нажатие, команды плеера, циферблат по треку, свои назначения при активной программе (будильник)";
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
        startGrab();
    }

    @Override
    public void stop() {
        alive = false;
        dialMode = false;
        ShizukuBridge.removeReadyListener(readyCb);
        synchronized (grabLock) { grabGen++; }
        killGrab();
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
                    while (alive && (line = r.readLine()) != null) feed(line);
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
            if (dev.startsWith("BLE-M3") && "KEY_VOLUMEUP".equals(code)) code = "KEY_VOLUMEDOWN";   // камера шлёт то VOLUMEUP, то VOLUMEDOWN: это одна кнопка
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
        if (uiOpen > 0) return;
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

    /** Клавиша пришла сразу после прыжка указки (короткое нажатие): прыжок закрепляется за этой клавишей, прежнее соответствие заменяется. */
    private void learnPreKey(Pre pr, String sig) {
        if (pr == null || sig == null || prefs == null) return;
        if (SystemClock.uptimeMillis() - pr.at > 400) return;
        if (sig.equals(preMap.get(pr.key))) return;
        preMap.put(pr.key, sig);
        prefs.edit().putString("pre#" + pr.key, sig).apply();
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

    // ------------------------------------------------------------------ захват пульта (Android не получает его события)

    private static final Map<Integer, String> KEY_NAMES = table("0:KEY_RESERVED,KEY_ESC,KEY_1,KEY_2,KEY_3,KEY_4,KEY_5,KEY_6,KEY_7,KEY_8,KEY_9,KEY_0,KEY_MINUS,KEY_EQUAL,KEY_BACKSPACE,KEY_TAB,KEY_Q,KEY_W,KEY_E,KEY_R,KEY_T,KEY_Y,KEY_U,KEY_I,KEY_O,KEY_P,KEY_LEFTBRACE,KEY_RIGHTBRACE,KEY_ENTER,KEY_LEFTCTRL,KEY_A,KEY_S,KEY_D,KEY_F,KEY_G,KEY_H,KEY_J,KEY_K,KEY_L,KEY_SEMICOLON,KEY_APOSTROPHE,KEY_GRAVE,KEY_LEFTSHIFT,KEY_BACKSLASH,KEY_Z,KEY_X,KEY_C,KEY_V,KEY_B,KEY_N,KEY_M,KEY_COMMA,KEY_DOT,KEY_SLASH,KEY_RIGHTSHIFT,KEY_KPASTERISK,KEY_LEFTALT,KEY_SPACE,KEY_CAPSLOCK,KEY_F1,KEY_F2,KEY_F3,KEY_F4,KEY_F5,KEY_F6,KEY_F7,KEY_F8,KEY_F9,KEY_F10,KEY_NUMLOCK,KEY_SCROLLLOCK,KEY_KP7,KEY_KP8,KEY_KP9,KEY_KPMINUS,KEY_KP4,KEY_KP5,KEY_KP6,KEY_KPPLUS,KEY_KP1,KEY_KP2,KEY_KP3,KEY_KP0,KEY_KPDOT;85:KEY_ZENKAKUHANKAKU,KEY_102ND,KEY_F11,KEY_F12,KEY_RO,KEY_KATAKANA,KEY_HIRAGANA,KEY_HENKAN,KEY_KATAKANAHIRAGANA,KEY_MUHENKAN,KEY_KPJPCOMMA,KEY_KPENTER,KEY_RIGHTCTRL,KEY_KPSLASH,KEY_SYSRQ,KEY_RIGHTALT,KEY_LINEFEED,KEY_HOME,KEY_UP,KEY_PAGEUP,KEY_LEFT,KEY_RIGHT,KEY_END,KEY_DOWN,KEY_PAGEDOWN,KEY_INSERT,KEY_DELETE,KEY_MACRO,KEY_MUTE,KEY_VOLUMEDOWN,KEY_VOLUMEUP,KEY_POWER,KEY_KPEQUAL,KEY_KPPLUSMINUS,KEY_PAUSE,KEY_SCALE,KEY_KPCOMMA,KEY_HANGEUL,KEY_HANJA,KEY_YEN,KEY_LEFTMETA,KEY_RIGHTMETA,KEY_COMPOSE,KEY_STOP,KEY_AGAIN,KEY_PROPS,KEY_UNDO,KEY_FRONT,KEY_COPY,KEY_OPEN,KEY_PASTE,KEY_FIND,KEY_CUT,KEY_HELP,KEY_MENU,KEY_CALC,KEY_SETUP,KEY_SLEEP,KEY_WAKEUP,KEY_FILE,KEY_SENDFILE,KEY_DELETEFILE,KEY_XFER,KEY_PROG1,KEY_PROG2,KEY_WWW,KEY_MSDOS,KEY_COFFEE,KEY_ROTATE_DISPLAY,KEY_CYCLEWINDOWS,KEY_MAIL,KEY_BOOKMARKS,KEY_COMPUTER,KEY_BACK,KEY_FORWARD,KEY_CLOSECD,KEY_EJECTCD,KEY_EJECTCLOSECD,KEY_NEXTSONG,KEY_PLAYPAUSE,KEY_PREVIOUSSONG,KEY_STOPCD,KEY_RECORD,KEY_REWIND,KEY_PHONE,KEY_ISO,KEY_CONFIG,KEY_HOMEPAGE,KEY_REFRESH,KEY_EXIT,KEY_MOVE,KEY_EDIT,KEY_SCROLLUP,KEY_SCROLLDOWN,KEY_KPLEFTPAREN,KEY_KPRIGHTPAREN,KEY_NEW,KEY_REDO,KEY_F13,KEY_F14,KEY_F15,KEY_F16,KEY_F17,KEY_F18,KEY_F19,KEY_F20,KEY_F21,KEY_F22,KEY_F23,KEY_F24;200:KEY_PLAYCD,KEY_PAUSECD,KEY_PROG3,KEY_PROG4,KEY_ALL_APPLICATIONS,KEY_SUSPEND,KEY_CLOSE,KEY_PLAY,KEY_FASTFORWARD,KEY_BASSBOOST,KEY_PRINT,KEY_HP,KEY_CAMERA,KEY_SOUND,KEY_QUESTION,KEY_EMAIL,KEY_CHAT,KEY_SEARCH,KEY_CONNECT,KEY_FINANCE,KEY_SPORT,KEY_SHOP,KEY_ALTERASE,KEY_CANCEL,KEY_BRIGHTNESSDOWN,KEY_BRIGHTNESSUP,KEY_MEDIA,KEY_SWITCHVIDEOMODE,KEY_KBDILLUMTOGGLE,KEY_KBDILLUMDOWN,KEY_KBDILLUMUP,KEY_SEND,KEY_REPLY,KEY_FORWARDMAIL,KEY_SAVE,KEY_DOCUMENTS,KEY_BATTERY,KEY_BLUETOOTH,KEY_WLAN,KEY_UWB,KEY_UNKNOWN,KEY_VIDEO_NEXT,KEY_VIDEO_PREV,KEY_BRIGHTNESS_CYCLE,KEY_BRIGHTNESS_AUTO,KEY_DISPLAY_OFF,KEY_WWAN,KEY_RFKILL,KEY_MICMUTE;256:BTN_MISC,BTN_1,BTN_2,BTN_3,BTN_4,BTN_5,BTN_6,BTN_7,BTN_8,BTN_9;272:BTN_MOUSE,BTN_RIGHT,BTN_MIDDLE,BTN_SIDE,BTN_EXTRA,BTN_FORWARD,BTN_BACK,BTN_TASK;288:BTN_JOYSTICK,BTN_THUMB,BTN_THUMB2,BTN_TOP,BTN_TOP2,BTN_PINKIE,BTN_BASE,BTN_BASE2,BTN_BASE3,BTN_BASE4,BTN_BASE5,BTN_BASE6;303:BTN_DEAD,BTN_GAMEPAD,BTN_EAST,BTN_C,BTN_NORTH,BTN_WEST,BTN_Z,BTN_TL,BTN_TR,BTN_TL2,BTN_TR2,BTN_SELECT,BTN_START,BTN_MODE,BTN_THUMBL,BTN_THUMBR;320:BTN_DIGI,BTN_TOOL_RUBBER,BTN_TOOL_BRUSH,BTN_TOOL_PENCIL,BTN_TOOL_AIRBRUSH,BTN_TOOL_FINGER,BTN_TOOL_MOUSE,BTN_TOOL_LENS,BTN_TOOL_QUINTTAP,BTN_STYLUS3,BTN_TOUCH,BTN_STYLUS,BTN_STYLUS2,BTN_TOOL_DOUBLETAP,BTN_TOOL_TRIPLETAP,BTN_TOOL_QUADTAP,BTN_WHEEL,BTN_GEAR_UP;352:KEY_OK,KEY_SELECT,KEY_GOTO,KEY_CLEAR,KEY_POWER2,KEY_OPTION,KEY_INFO,KEY_TIME,KEY_VENDOR,KEY_ARCHIVE,KEY_PROGRAM,KEY_CHANNEL,KEY_FAVORITES,KEY_EPG,KEY_PVR,KEY_MHP,KEY_LANGUAGE,KEY_TITLE,KEY_SUBTITLE,KEY_ANGLE,KEY_FULL_SCREEN,KEY_MODE,KEY_KEYBOARD,KEY_ASPECT_RATIO,KEY_PC,KEY_TV,KEY_TV2,KEY_VCR,KEY_VCR2,KEY_SAT,KEY_SAT2,KEY_CD,KEY_TAPE,KEY_RADIO,KEY_TUNER,KEY_PLAYER,KEY_TEXT,KEY_DVD,KEY_AUX,KEY_MP3,KEY_AUDIO,KEY_VIDEO,KEY_DIRECTORY,KEY_LIST,KEY_MEMO,KEY_CALENDAR,KEY_RED,KEY_GREEN,KEY_YELLOW,KEY_BLUE,KEY_CHANNELUP,KEY_CHANNELDOWN,KEY_FIRST,KEY_LAST,KEY_AB,KEY_NEXT,KEY_RESTART,KEY_SLOW,KEY_SHUFFLE,KEY_BREAK,KEY_PREVIOUS,KEY_DIGITS,KEY_TEEN,KEY_TWEN,KEY_VIDEOPHONE,KEY_GAMES,KEY_ZOOMIN,KEY_ZOOMOUT,KEY_ZOOMRESET,KEY_WORDPROCESSOR,KEY_EDITOR,KEY_SPREADSHEET,KEY_GRAPHICSEDITOR,KEY_PRESENTATION,KEY_DATABASE,KEY_NEWS,KEY_VOICEMAIL,KEY_ADDRESSBOOK,KEY_MESSENGER,KEY_DISPLAYTOGGLE,KEY_SPELLCHECK,KEY_LOGOFF,KEY_DOLLAR,KEY_EURO,KEY_FRAMEBACK,KEY_FRAMEFORWARD,KEY_CONTEXT_MENU,KEY_MEDIA_REPEAT,KEY_10CHANNELSUP,KEY_10CHANNELSDOWN,KEY_IMAGES;444:KEY_NOTIFICATION_CENTER,KEY_PICKUP_PHONE,KEY_HANGUP_PHONE,KEY_LINK_PHONE,KEY_DEL_EOL,KEY_DEL_EOS,KEY_INS_LINE,KEY_DEL_LINE;464:KEY_FN,KEY_FN_ESC,KEY_FN_F1,KEY_FN_F2,KEY_FN_F3,KEY_FN_F4,KEY_FN_F5,KEY_FN_F6,KEY_FN_F7,KEY_FN_F8,KEY_FN_F9,KEY_FN_F10,KEY_FN_F11,KEY_FN_F12,KEY_FN_1,KEY_FN_2,KEY_FN_D,KEY_FN_E,KEY_FN_F,KEY_FN_S,KEY_FN_B,KEY_FN_RIGHT_SHIFT;497:KEY_BRL_DOT1,KEY_BRL_DOT2,KEY_BRL_DOT3,KEY_BRL_DOT4,KEY_BRL_DOT5,KEY_BRL_DOT6,KEY_BRL_DOT7,KEY_BRL_DOT8,KEY_BRL_DOT9,KEY_BRL_DOT10;512:KEY_NUMERIC_0,KEY_NUMERIC_1,KEY_NUMERIC_2,KEY_NUMERIC_3,KEY_NUMERIC_4,KEY_NUMERIC_5,KEY_NUMERIC_6,KEY_NUMERIC_7,KEY_NUMERIC_8,KEY_NUMERIC_9,KEY_NUMERIC_STAR,KEY_NUMERIC_POUND,KEY_NUMERIC_A,KEY_NUMERIC_B,KEY_NUMERIC_C,KEY_NUMERIC_D,KEY_CAMERA_FOCUS,KEY_WPS_BUTTON,KEY_TOUCHPAD_TOGGLE,KEY_TOUCHPAD_ON,KEY_TOUCHPAD_OFF,KEY_CAMERA_ZOOMIN,KEY_CAMERA_ZOOMOUT,KEY_CAMERA_UP,KEY_CAMERA_DOWN,KEY_CAMERA_LEFT,KEY_CAMERA_RIGHT,KEY_ATTENDANT_ON,KEY_ATTENDANT_OFF,KEY_ATTENDANT_TOGGLE,KEY_LIGHTS_TOGGLE;544:BTN_DPAD_UP,BTN_DPAD_DOWN,BTN_DPAD_LEFT,BTN_DPAD_RIGHT;560:KEY_ALS_TOGGLE,KEY_ROTATE_LOCK_TOGGLE,KEY_REFRESH_RATE_TOGGLE;576:KEY_BUTTONCONFIG,KEY_TASKMANAGER,KEY_JOURNAL,KEY_CONTROLPANEL,KEY_APPSELECT,KEY_SCREENSAVER,KEY_VOICECOMMAND,KEY_ASSISTANT,KEY_KBD_LAYOUT_NEXT,KEY_EMOJI_PICKER,KEY_DICTATE,KEY_CAMERA_ACCESS_ENABLE,KEY_CAMERA_ACCESS_DISABLE,KEY_CAMERA_ACCESS_TOGGLE,KEY_ACCESSIBILITY,KEY_DO_NOT_DISTURB,KEY_BRIGHTNESS_MIN;608:KEY_KBDINPUTASSIST_PREV,KEY_KBDINPUTASSIST_NEXT,KEY_KBDINPUTASSIST_PREVGROUP,KEY_KBDINPUTASSIST_NEXTGROUP,KEY_KBDINPUTASSIST_ACCEPT,KEY_KBDINPUTASSIST_CANCEL,KEY_RIGHT_UP,KEY_RIGHT_DOWN,KEY_LEFT_UP,KEY_LEFT_DOWN,KEY_ROOT_MENU,KEY_MEDIA_TOP_MENU,KEY_NUMERIC_11,KEY_NUMERIC_12,KEY_AUDIO_DESC,KEY_3D_MODE,KEY_NEXT_FAVORITE,KEY_STOP_RECORD,KEY_PAUSE_RECORD,KEY_VOD,KEY_UNMUTE,KEY_FASTREVERSE,KEY_SLOWREVERSE,KEY_DATA,KEY_ONSCREEN_KEYBOARD,KEY_PRIVACY_SCREEN_TOGGLE,KEY_SELECTIVE_SCREENSHOT,KEY_NEXT_ELEMENT,KEY_PREVIOUS_ELEMENT,KEY_AUTOPILOT_ENGAGE_TOGGLE,KEY_MARK_WAYPOINT,KEY_SOS,KEY_NAV_CHART,KEY_FISHING_CHART,KEY_SINGLE_RANGE_RADAR,KEY_DUAL_RANGE_RADAR,KEY_RADAR_OVERLAY,KEY_TRADITIONAL_SONAR,KEY_CLEARVU_SONAR,KEY_SIDEVU_SONAR,KEY_NAV_INFO,KEY_BRIGHTNESS_MENU;656:KEY_MACRO1,KEY_MACRO2,KEY_MACRO3,KEY_MACRO4,KEY_MACRO5,KEY_MACRO6,KEY_MACRO7,KEY_MACRO8,KEY_MACRO9,KEY_MACRO10,KEY_MACRO11,KEY_MACRO12,KEY_MACRO13,KEY_MACRO14,KEY_MACRO15,KEY_MACRO16,KEY_MACRO17,KEY_MACRO18,KEY_MACRO19,KEY_MACRO20,KEY_MACRO21,KEY_MACRO22,KEY_MACRO23,KEY_MACRO24,KEY_MACRO25,KEY_MACRO26,KEY_MACRO27,KEY_MACRO28,KEY_MACRO29,KEY_MACRO30;688:KEY_MACRO_RECORD_START,KEY_MACRO_RECORD_STOP,KEY_MACRO_PRESET_CYCLE,KEY_MACRO_PRESET1,KEY_MACRO_PRESET2,KEY_MACRO_PRESET3;696:KEY_KBD_LCD_MENU1,KEY_KBD_LCD_MENU2,KEY_KBD_LCD_MENU3,KEY_KBD_LCD_MENU4,KEY_KBD_LCD_MENU5;704:BTN_TRIGGER_HAPPY,BTN_TRIGGER_HAPPY2,BTN_TRIGGER_HAPPY3,BTN_TRIGGER_HAPPY4,BTN_TRIGGER_HAPPY5,BTN_TRIGGER_HAPPY6,BTN_TRIGGER_HAPPY7,BTN_TRIGGER_HAPPY8,BTN_TRIGGER_HAPPY9,BTN_TRIGGER_HAPPY10,BTN_TRIGGER_HAPPY11,BTN_TRIGGER_HAPPY12,BTN_TRIGGER_HAPPY13,BTN_TRIGGER_HAPPY14,BTN_TRIGGER_HAPPY15,BTN_TRIGGER_HAPPY16,BTN_TRIGGER_HAPPY17,BTN_TRIGGER_HAPPY18,BTN_TRIGGER_HAPPY19,BTN_TRIGGER_HAPPY20,BTN_TRIGGER_HAPPY21,BTN_TRIGGER_HAPPY22,BTN_TRIGGER_HAPPY23,BTN_TRIGGER_HAPPY24,BTN_TRIGGER_HAPPY25,BTN_TRIGGER_HAPPY26,BTN_TRIGGER_HAPPY27,BTN_TRIGGER_HAPPY28,BTN_TRIGGER_HAPPY29,BTN_TRIGGER_HAPPY30,BTN_TRIGGER_HAPPY31,BTN_TRIGGER_HAPPY32,BTN_TRIGGER_HAPPY33,BTN_TRIGGER_HAPPY34,BTN_TRIGGER_HAPPY35,BTN_TRIGGER_HAPPY36,BTN_TRIGGER_HAPPY37,BTN_TRIGGER_HAPPY38,BTN_TRIGGER_HAPPY39,BTN_TRIGGER_HAPPY40");
    private static final Map<Integer, String> REL_NAMES = table("0:REL_X,REL_Y,REL_Z,REL_RX,REL_RY,REL_RZ,REL_HWHEEL,REL_DIAL,REL_WHEEL,REL_MISC,REL_RESERVED,REL_WHEEL_HI_RES,REL_HWHEEL_HI_RES");
    private static final Map<Integer, String> ABS_NAMES = table("0:ABS_X,ABS_Y,ABS_Z,ABS_RX,ABS_RY,ABS_RZ,ABS_THROTTLE,ABS_RUDDER,ABS_WHEEL,ABS_GAS,ABS_BRAKE;16:ABS_HAT0X,ABS_HAT0Y,ABS_HAT1X,ABS_HAT1Y,ABS_HAT2X,ABS_HAT2Y,ABS_HAT3X,ABS_HAT3Y,ABS_PRESSURE,ABS_DISTANCE,ABS_TILT_X,ABS_TILT_Y,ABS_TOOL_WIDTH;32:ABS_VOLUME,ABS_PROFILE;40:ABS_MISC;46:ABS_RESERVED,ABS_MT_SLOT,ABS_MT_TOUCH_MAJOR,ABS_MT_TOUCH_MINOR,ABS_MT_WIDTH_MAJOR,ABS_MT_WIDTH_MINOR,ABS_MT_ORIENTATION,ABS_MT_POSITION_X,ABS_MT_POSITION_Y,ABS_MT_TOOL_TYPE,ABS_MT_BLOB_ID,ABS_MT_TRACKING_ID,ABS_MT_PRESSURE,ABS_MT_DISTANCE,ABS_MT_TOOL_X,ABS_MT_TOOL_Y");

    /** Таблица имён кодов: «начало:имя,имя,...;начало:...». Имена как в «getevent -l», чтобы записанные кнопки совпадали. */
    private static Map<Integer, String> table(String spec) {
        Map<Integer, String> m = new HashMap<Integer, String>();
        for (String run : spec.split(";")) {
            int c = run.indexOf(':');
            int n = Integer.parseInt(run.substring(0, c));
            for (String name : run.substring(c + 1).split(",")) m.put(Integer.valueOf(n++), name);
        }
        return m;
    }

    private static String codeName(Map<Integer, String> m, int code) {
        String s = m.get(Integer.valueOf(code));
        return s != null ? s : String.format("%04x", code);
    }

    /** Событие помощника (тип, код, значение) в виде строки «getevent -l», которую разбирает handleLine. */
    private static String eventLine(String path, int type, int code, int val) {
        if (type == 1) return path + ": EV_KEY       " + codeName(KEY_NAMES, code) + " " + (val == 0 ? "UP" : val == 1 ? "DOWN" : "REPEAT");
        if (type == 2) return path + ": EV_REL       " + codeName(REL_NAMES, code) + " " + String.format("%08x", val);
        if (type == 3) return path + ": EV_ABS       " + codeName(ABS_NAMES, code) + " " + String.format("%08x", val);
        return null;
    }

    private void feed(String line) {
        synchronized (lineLock) { handleLine(line); }
    }

    private boolean grabOn() { return prefs != null && prefs.getBoolean("grab", false); }

    private void glog(String s) {
        synchronized (lock) { grabLog.addFirst(s); while (grabLog.size() > 8) grabLog.removeLast(); }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }

    /** Запускает цикл захвата заново (старый цикл и его помощник останавливаются), если включена настройка «grab». */
    private void startGrab() {
        if (!alive || ctx == null || prefs == null) return;
        final int gen;
        synchronized (grabLock) { gen = ++grabGen; }
        killGrab();
        if (!grabOn()) { grabState = "выключено"; return; }
        if (!ShizukuBridge.hasPermission()) {
            grabState = ShizukuBridge.isRunning() ? "у приложения нет разрешения Shizuku" : "Shizuku не запущен";
            return;
        }
        grabState = "запуск…";
        Thread t = new Thread(new Runnable() { @Override public void run() { grabLoop(gen); } }, "BtRemote-grab");
        t.setDaemon(true);
        t.start();
    }

    /** Останавливает помощника: закрытие его ввода и сигнал завершения, захват снимается вместе с процессом. */
    private void killGrab() {
        Process p = grabProc;
        grabProc = null;
        if (p != null) {
            try { p.getOutputStream().close(); } catch (Throwable t) { }
            try { p.destroy(); } catch (Throwable t) { }
        }
    }

    private boolean gen(int gen) {
        synchronized (grabLock) { return alive && gen == grabGen && grabOn(); }
    }

    /** Имена устройств по путям из «getevent -lp» (пульт мог переподключиться под другим номером). */
    private void scanNames() {
        try {
            String out = ShizukuBridge.exec("getevent -lp").getOut();
            Map<String, String> fresh = new HashMap<String, String>();
            String path = null;
            for (String raw : out.split("\n")) {
                String s = raw.trim();
                if (s.startsWith("add device")) {
                    int i = s.indexOf(": ");
                    path = i > 0 ? s.substring(i + 2).trim() : null;
                } else if (s.startsWith("name:") && path != null) {
                    int a = s.indexOf('"'), b = s.lastIndexOf('"');
                    if (a >= 0 && b > a) fresh.put(path, s.substring(a + 1, b));
                }
            }
            if (!fresh.isEmpty()) synchronized (devNames) { devNames.clear(); devNames.putAll(fresh); }
        } catch (Throwable t) { }
    }

    private static void addDev(Set<String> out, String sig) {
        if (sig == null) return;
        String[] p = sig.split("\\|", 3);
        if (p.length == 3 && p[2].length() > 0) out.add(p[2]);
    }

    /** Устройства, от которых есть назначенные кнопки (имена из сигналов), кроме геймпадов и самого телефона. */
    private List<String> grabPaths() {
        Set<String> names = new HashSet<String>();
        for (String k : bound.keySet()) { int i = k.lastIndexOf('#'); addDev(names, i > 0 ? k.substring(0, i) : k); }
        for (Object[] sl : SLOTS) addDev(names, slotSig((String) sl[0]));
        for (String v : preMap.values()) addDev(names, v);
        List<String> res = new ArrayList<String>();
        synchronized (devNames) {
            for (Map.Entry<String, String> e : devNames.entrySet()) {
                String path = e.getKey();
                String nm = e.getValue().replace('|', '/').replace('#', '/');
                if (!names.contains(nm) || !path.matches("/dev/input/event\\d+") || padPaths.contains(path)) continue;
                synchronized (lock) { if (excluded.contains(path)) continue; }
                res.add(path);
            }
        }
        Collections.sort(res);
        return res;
    }

    private void grabLoop(int gen) {
        while (gen(gen)) {
            scanNames();
            List<String> paths = grabPaths();
            if (paths.isEmpty()) { grabState = "жду пульт: нет устройств с назначенными кнопками"; sleepMs(4000); continue; }
            java.io.File dex;
            try {
                dex = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        "UniversalPlugins/BtRemoteModule.dex");
            } catch (Throwable t) { dex = new java.io.File("/sdcard/Download/UniversalPlugins/BtRemoteModule.dex"); }
            if (!dex.isFile()) { grabState = "не найден файл модуля: " + dex; sleepMs(10000); continue; }
            StringBuilder cmd = new StringBuilder("CLASSPATH='").append(dex.getAbsolutePath())
                    .append("' exec app_process /system/bin 'im.manus.plugins.BtRemoteModule$Grab'");
            for (String p : paths) cmd.append(' ').append(p);
            cmd.append(" 2>&1");
            Process p = ShizukuBridge.startProcess(cmd.toString());
            if (p == null) { grabState = "не удалось запустить помощника через Shizuku"; sleepMs(5000); continue; }
            if (!gen(gen)) { try { p.destroy(); } catch (Throwable t) { } return; }
            grabProc = p;
            synchronized (lock) { grabbed.clear(); }
            grabState = "помощник запущен: " + paths;
            glog("запуск: " + paths);
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                try {
                    String line;
                    while ((line = r.readLine()) != null) grabLine(line);
                } finally { r.close(); }
            } catch (Throwable t) { }
            try { p.getOutputStream().close(); } catch (Throwable t) { }
            try { p.destroy(); } catch (Throwable t) { }
            if (grabProc == p) grabProc = null;
            if (!gen(gen)) return;
            grabState = "помощник завершился, перезапуск";
            sleepMs(3000);
        }
    }

    /** Строка от помощника: «E путь тип код значение» — событие; «S ...» — состояние; остальное — вывод ошибок. */
    private void grabLine(String line) {
        if (line.startsWith("E ")) {
            String[] f = line.split(" ");
            if (f.length < 5) return;
            try {
                String l = eventLine(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                if (l != null) feed(l);
            } catch (NumberFormatException e) { }
            return;
        }
        glog(line);
        if (line.startsWith("S OK ")) {
            int n;
            synchronized (lock) { grabbed.add(line.substring(5)); n = grabbed.size(); }
            grabState = "забрал устройств: " + n;
        } else if (line.startsWith("S ERR ")) {
            grabState = "ошибка: " + line.substring(6);
        }
    }

    /**
     * Помощник захвата. Запускается через Shizuku от имени оболочки: app_process с .dex модуля в CLASSPATH.
     * Для каждого устройства берёт его в исключительное пользование (EVIOCGRAB: Android событий больше не получает)
     * и печатает события: «E путь тип код значение». Захват снимается, когда процесс завершается; завершается он,
     * когда модуль закрывает его ввод или читать выход больше некому.
     */
    public static final class Grab {
        private static final int EVIOCGRAB = 0x40044590;
        private static int open;

        private static synchronized void say(String s) {
            System.out.println(s);
            System.out.flush();
            if (System.out.checkError()) Runtime.getRuntime().halt(0);
        }

        /** Возвращает null при успехе, иначе текст ошибки. */
        private static String grab(java.io.FileDescriptor fd) {
            try {
                Class<?> lc = Class.forName("libcore.io.Libcore");
                Object os = lc.getField("os").get(null);
                Class<?> osi = Class.forName("libcore.io.Os");
                try {
                    osi.getMethod("ioctlInt", java.io.FileDescriptor.class, int.class).invoke(os, fd, Integer.valueOf(EVIOCGRAB));
                } catch (NoSuchMethodException e) {
                    Class<?> ref = Class.forName("android.system.Int32Ref");
                    Object v = ref.getConstructor(int.class).newInstance(Integer.valueOf(1));
                    osi.getMethod("ioctlInt", java.io.FileDescriptor.class, int.class, ref).invoke(os, fd, Integer.valueOf(EVIOCGRAB), v);
                }
                return null;
            } catch (java.lang.reflect.InvocationTargetException e) {
                return String.valueOf(e.getCause());
            } catch (Throwable t) {
                return String.valueOf(t);
            }
        }

        private static void pump(String path, int sz, int off) {
            java.io.FileInputStream in = null;
            try {
                in = new java.io.FileInputStream(path);
                String err = null;
                for (int i = 0; i < 15; i++) {
                    err = grab(in.getFD());
                    if (err == null || !err.contains("EBUSY")) break;
                    try { Thread.sleep(200); } catch (InterruptedException e) { }
                }
                if (err != null) { say("S ERR " + path + " " + err); return; }
                say("S OK " + path);
                byte[] buf = new byte[sz * 64];
                int len;
                while ((len = in.read(buf)) > 0) {
                    for (int p = 0; p + sz <= len; p += sz) {
                        int type = (buf[p + off] & 0xff) | ((buf[p + off + 1] & 0xff) << 8);
                        int code = (buf[p + off + 2] & 0xff) | ((buf[p + off + 3] & 0xff) << 8);
                        int val = (buf[p + off + 4] & 0xff) | ((buf[p + off + 5] & 0xff) << 8)
                                | ((buf[p + off + 6] & 0xff) << 16) | ((buf[p + off + 7] & 0xff) << 24);
                        if (type == 1 || type == 2 || type == 3) say("E " + path + " " + type + " " + code + " " + val);
                    }
                }
            } catch (Throwable t) {
                say("S END " + path + " " + t);
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable t) { } }
                synchronized (Grab.class) { open--; if (open <= 0) Runtime.getRuntime().halt(0); }
            }
        }

        public static void main(String[] args) {
            boolean b64 = android.os.Process.is64Bit();
            final int sz = b64 ? 24 : 16;
            final int off = b64 ? 16 : 8;
            open = args.length;
            for (final String path : args) {
                Thread t = new Thread(new Runnable() { @Override public void run() { pump(path, sz, off); } });
                t.start();
            }
            try {
                while (System.in.read() >= 0) { }
            } catch (Throwable t) { }
            Runtime.getRuntime().halt(0);
        }
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
        sb.append("Захват пульта: ").append(grabOn() ? "включён, " : "выключен, ").append(grabState).append('\n');
        synchronized (lock) { for (String g : grabLog) sb.append("  помощник: ").append(g).append('\n'); }
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
    /** Назначено ли действие на этот тип нажатия: в режиме активной программы — по её таблице (действие по умолчанию не считается). */
    private boolean has(String sig, String g) {
        Ctx cx = activeCtx();
        return cx != null ? ctxBound.containsKey(cx.id + "#" + sig + "#" + g) : bound.containsKey(sig + "#" + g);
    }

    private void fire(String sig, String g) {
        if (uiOpen > 0) return;                       // экран настроек открыт: нажатия только выбирают кнопку
        String fn = fnFor(sig, g);
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
        Pre kp = pre;
        if (kp != null && sig.startsWith("K|") && SystemClock.uptimeMillis() - kp.at < 10000) {
            if (kp.fired && sig.equals(preMap.get(kp.key))) {   // долгое нажатие уже выполнено по таймеру: клавиша пришла при отпускании
                pre = null;
                st.suppress = true;
                return;
            }
            learnPreKey(kp, sig);
        }
        if (uiOpen > 0) { st.suppress = true; return; }
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
        if (uiOpen > 0 || has(sig, "L") || has(sig, "D")) return;
        if (dialMode && dialIndex(sig) >= 0) return;
        String fn = fnFor(sig, "S");
        if ("vol_up".equals(fn) || "vol_down".equals(fn)) run(fn);
    }

    /** Завершённое нажатие указки или колеса: длительность известна только в конце, поэтому долгое срабатывает при отпускании. */
    private void pressDone(String sig, long dur) {
        note(sig);
        if (dur > 0) synchronized (lock) { if (!evlog.isEmpty()) evlog.set(0, evlog.getFirst() + " · " + dur + " мс"); }
        if (uiOpen > 0) return;
        if (dialIntercept(sig)) return;               // циферблат: стрелка = переход по треку
        if (dur >= longMs() && has(sig, "L")) { fire(sig, "L"); return; }
        shortPress(sig, stateOf(sig), false);
    }

    // ------------------------------------------------------------------ контексты: активная программа -> свои назначения

    /** Активная сейчас программа из CTXS (её метка есть в System properties); null — обычный режим. */
    private static Ctx activeCtx() {
        for (Ctx c : CTXS) {
            try { if (System.getProperties().get(c.liveKey) != null) return c; } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * Функция для нажатия. В обычном режиме — назначение кнопки. Пока активна программа из CTXS — только её назначения
     * («при звонке»), а одиночное нажатие без назначения выполняет действие по умолчанию (у будильника «отложить»);
     * обычные функции кнопок в это время не выполняются.
     */
    private String fnFor(String sig, String g) {
        Ctx cx = activeCtx();
        if (cx == null) return bound.get(sig + "#" + g);
        String f = ctxBound.get(cx.id + "#" + sig + "#" + g);
        return f == null && "S".equals(g) ? cx.fallback : f;
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
        if (!dialMode || uiOpen > 0 || activeCtx() != null) return false;
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
        sayPlayerPercent();
    }

    /** При включении режима голос называет только текущий процент трека (его собственный sayPercent у плеера). */
    private void sayPlayerPercent() {
        try {
            Object ap = playerObj();
            if (ap == null) { toast("Аудиоплеер не запущен"); return; }
            Method m = ap.getClass().getDeclaredMethod("sayPercent");
            m.setAccessible(true);
            m.invoke(ap);
        } catch (Throwable t) { }
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
                if (dialMode) dialStop(false);   // автовыключение молча
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
        moveAlarmToCtx();
        mergeVolKeys();
    }

    /** Камера пульта шлёт то VOLUMEUP, то VOLUMEDOWN: это одна кнопка (VOLUMEDOWN), прежние назначения на VOLUMEUP переносятся, если место свободно. */
    private void mergeVolKeys() {
        if (prefs.getBoolean("mig15", false)) return;
        final String up = "K|KEY_VOLUMEUP|BLE-M3", down = "K|KEY_VOLUMEDOWN|BLE-M3";
        Map<String, Object> all = new HashMap<String, Object>(prefs.getAll());
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if ((k.startsWith("act#") || k.startsWith("cx#")) && k.contains(up)) {
                String nk = k.replace(up, down);
                ed.remove(k);
                if (!all.containsKey(nk) && v instanceof String) ed.putString(nk, (String) v);
            } else if ((k.startsWith("slot_") || k.startsWith("pre#")) && v instanceof String && ((String) v).startsWith(up)) {
                ed.putString(k, down + ((String) v).substring(up.length()));
            }
        }
        ed.putBoolean("mig15", true);
        ed.apply();
    }

    /** Действия будильника переехали из общего списка в контекст «будильник звонит»: прежние назначения переносятся туда, кнопки освобождаются. */
    private void moveAlarmToCtx() {
        if (prefs.getBoolean("mig14", false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            String v = e.getValue() instanceof String ? (String) e.getValue() : null;
            if (!k.startsWith("act#") || v == null || !v.startsWith("alarm_")) continue;
            ed.remove(k);
            ed.putString("cx#alarm#" + k.substring(4), v);
        }
        ed.putBoolean("mig14", true);
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
        Map<String, String> cm = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("cx#") && e.getValue() instanceof String) cm.put(k.substring(3), (String) e.getValue());
        }
        ctxBound.clear();
        ctxBound.putAll(cm);
        loadPre();
    }

    private void setAct(String sig, String g, String fn) {
        String k = "act#" + sig + "#" + g;
        SharedPreferences.Editor ed = prefs.edit();
        if (fn == null) ed.remove(k); else ed.putString(k, fn);
        ed.apply();
        loadBindings();
    }

    private void setCtxAct(Ctx cx, String sig, String g, String fn) {
        String k = "cx#" + cx.id + "#" + sig + "#" + g;
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
        for (Ctx c : CTXS) for (String[] f : c.fns) if (f[0].equals(fn)) return f[1];
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
        l.add(SettingItem.toggle("grab", "🛡 Забирать пульт у Android", "Пока включено, Android не получает события пульта (указка и клавиши не дёргают экран), их читает только модуль. Нужен Shizuku. Выключено — как раньше", false));
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
                if (k.startsWith("act#") || k.startsWith("cx#") || k.startsWith("slot_") || k.startsWith("pre#")) ed.remove(k);
            }
            ed.apply();
            loadBindings();
            toast("Все назначения сброшены");
        } else if ("grab".equals(key)) {
            startGrab();
            toast(grabOn() ? "Захват пульта включается, состояние в журнале" : "Захват пульта выключен");
        } else if ("restart".equals(key)) {
            killProcess();
            startReader();
            startGrab();
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
            if (grabOn()) sb.append("\nЗахват пульта: ").append(grabState);
            if (pending != null) sb.append("\nНажата: ").append(describe(pending)).append(". Коснитесь на схеме, какая это кнопка.");
            else if (current == null) sb.append("\nНажмите кнопку на пульте, и откроются её действия.");
            sb.append("\nДействия при открытом экране не выполняются.");
            for (Ctx cx : CTXS) sb.append("\n").append(cx.label).append(": работают назначения «при активной программе», остальные кнопки — ").append(fnLabel(cx.fallback));
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

        private boolean hasAnyAct(String sig) {
            for (String g : G) {
                if (bound.containsKey(sig + "#" + g)) return true;
                for (Ctx cx : CTXS) if (ctxBound.containsKey(cx.id + "#" + sig + "#" + g)) return true;
            }
            return false;
        }

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
                        new OnClickListener() { @Override public void onClick(View v) { choose(sig, gi, null); } });
            }
            for (final Ctx cx : CTXS) {
                row(null, cx.label + " — что делает эта кнопка:", null);
                for (int i = 0; i < 3; i++) {
                    final int gi = i;
                    String fn = ctxBound.get(cx.id + "#" + sig + "#" + G[i]);
                    String shown = fn != null ? fnLabel(fn)
                            : ("S".equals(G[i]) ? "по умолчанию — " + fnLabel(cx.fallback) : "не назначено");
                    row(fn == null ? "○" : fnEmoji(fn), G_NAME[i] + ": " + shown,
                            new OnClickListener() { @Override public void onClick(View v) { choose(sig, gi, cx); } });
                }
            }
            row(null, "Закрыть", new OnClickListener() {
                @Override public void onClick(View v) { current = null; panel.removeAllViews(); refreshStatus(); dia.invalidate(); }
            });
            dia.invalidate();
        }

        /** Выбор функции для нажатия: обычной ([cx] == null) или «при активной программе» из её списка. */
        private void choose(final String sig, final int gi, final Ctx cx) {
            panel.removeAllViews();
            final String cur = cx == null ? bound.get(sig + "#" + G[gi]) : ctxBound.get(cx.id + "#" + sig + "#" + G[gi]);
            row(null, (cx == null ? "" : cx.label + " · ") + G_NAME[gi] + ":", null);
            String none = "Не назначено" + (cx != null && "S".equals(G[gi]) ? " (по умолчанию: " + fnLabel(cx.fallback) + ")" : "");
            row(null, (cur == null ? "✓ " : "") + none, new OnClickListener() {
                @Override public void onClick(View v) {
                    if (cx == null) setAct(sig, G[gi], null); else setCtxAct(cx, sig, G[gi], null);
                    edit(sig);
                }
            });
            for (final String[] f : (cx == null ? FNS : cx.fns)) {
                row(null, (f[0].equals(cur) ? "✓ " : "") + f[1], new OnClickListener() {
                    @Override public void onClick(View v) {
                        if (cx == null) setAct(sig, G[gi], f[0]); else setCtxAct(cx, sig, G[gi], f[0]);
                        edit(sig);
                    }
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
