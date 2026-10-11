package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import im.manus.universalhost.CoreAccessibilityService;
import im.manus.universalhost.IKeyHandler;
import im.manus.universalhost.IMotionHandler;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;
import im.manus.universalhost.ShizukuResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Геймпад: кнопки, стики, курки и крестовина превращаются в медиа-команды, системные действия
 * и запуск приложений.
 *
 * Кнопки и оси читаются сразу по нескольким каналам, события одной и той же кнопки склеиваются:
 *  1. служба специальных возможностей, onKeyEvent: кнопки, работает при выключенном экране;
 *  2. служба специальных возможностей, onMotionEvent: стики, курки, крестовина (только Android 14+);
 *  3. сырые события ядра через Shizuku (getevent): всё, при любом экране и любой версии Android;
 *  4. окно настроек: пока оно открыто, ловит кнопки и оси напрямую (запасной путь для назначения).
 *
 * Стики дают по четыре направления (левый и правый), курки L2/R2 и крестовина тоже работают как кнопки.
 * Кнопки, которых нет на картинке, добавляются внизу, когда их нажмёшь при открытых настройках.
 * У каждой кнопки три действия: нажатие, двойное нажатие, долгое нажатие. Два профиля: основной и
 * «для выбранных приложений» (выбирается по приложению на переднем плане, незаданное берётся из основного).
 */
public class GamepadModule implements IPlugin, ISettingsProvider, ISettingsViewProvider, IKeyHandler, IMotionHandler {

    private static final String PREFS = "module_Gamepad";
    private static final String[] GK = {"t", "d", "l"};
    private static final String[] GNAME = {"Нажатие", "Двойное нажатие", "Долгое нажатие"};

    // каналы ввода (номер бита) и их названия
    static final int CH_SERVICE = 0;
    static final int CH_WINDOW = 1;
    static final int CH_MOTION = 2;
    static final int CH_EVDEV = 3;
    private static final String[] CHN = {"служба", "окно", "служба-оси", "evdev"};

    // виртуальные «кнопки» для направлений стиков; всё от EXTRA_BASE: неизвестный код evdev
    static final int V_LS_UP = 1001;
    static final int V_LS_DOWN = 1002;
    static final int V_LS_LEFT = 1003;
    static final int V_LS_RIGHT = 1004;
    static final int V_RS_UP = 1005;
    static final int V_RS_DOWN = 1006;
    static final int V_RS_LEFT = 1007;
    static final int V_RS_RIGHT = 1008;
    static final int EXTRA_BASE = 2000;

    /** id, подпись. Порядок = порядок в списке выбора. */
    private static final String[][] ACTIONS = {
        {"play_pause", "Пауза / воспроизведение"},
        {"play", "Воспроизведение"},
        {"pause", "Пауза"},
        {"next", "Следующий трек"},
        {"prev", "Предыдущий трек"},
        {"seek_fwd", "Перемотка вперёд"},
        {"seek_back", "Перемотка назад"},
        {"stop", "Стоп"},
        {"vol_up", "Громкость +"},
        {"vol_down", "Громкость −"},
        {"mute", "Без звука"},
        {"back", "Назад"},
        {"home", "Домой"},
        {"recents", "Недавние"},
        {"notifications", "Шторка уведомлений"},
        {"quick_settings", "Быстрые настройки"},
        {"lock", "Заблокировать экран"},
        {"screenshot", "Скриншот"},
        {"power", "Меню питания"},
        {"flashlight", "Фонарик"},
        {"swipe_up", "Прокрутка вниз (свайп вверх)"},
        {"swipe_down", "Прокрутка вверх (свайп вниз)"},
    };

    /** keyCode, x, y, радиус, цвет (0 белый, 1 зелёный, 2 красный, 3 синий, 4 жёлтый), направление (1 вверх, 2 вниз, 3 влево, 4 вправо); поле 1000x700. */
    private static final int[][] SLOTS = {
        {KeyEvent.KEYCODE_BUTTON_L2, 170, 45, 38, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_R2, 830, 45, 38, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_L1, 170, 125, 38, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_R1, 830, 125, 38, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_MODE, 500, 200, 44, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_SELECT, 400, 265, 30, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_START, 600, 265, 30, 0, 0},
        {KeyEvent.KEYCODE_BUTTON_THUMBL, 230, 300, 40, 0, 0},
        {V_LS_UP, 230, 205, 26, 0, 1},
        {V_LS_DOWN, 230, 395, 26, 0, 2},
        {V_LS_LEFT, 135, 300, 26, 0, 3},
        {V_LS_RIGHT, 325, 300, 26, 0, 4},
        {KeyEvent.KEYCODE_DPAD_UP, 370, 420, 30, 0, 1},
        {KeyEvent.KEYCODE_DPAD_LEFT, 310, 480, 30, 0, 3},
        {KeyEvent.KEYCODE_DPAD_RIGHT, 430, 480, 30, 0, 4},
        {KeyEvent.KEYCODE_DPAD_DOWN, 370, 540, 30, 0, 2},
        {KeyEvent.KEYCODE_BUTTON_THUMBR, 620, 480, 40, 0, 0},
        {V_RS_UP, 620, 385, 26, 0, 1},
        {V_RS_DOWN, 620, 575, 26, 0, 2},
        {V_RS_LEFT, 525, 480, 26, 0, 3},
        {V_RS_RIGHT, 715, 480, 26, 0, 4},
        {KeyEvent.KEYCODE_BUTTON_Y, 790, 240, 33, 4, 0},
        {KeyEvent.KEYCODE_BUTTON_X, 720, 310, 33, 3, 0},
        {KeyEvent.KEYCODE_BUTTON_B, 860, 310, 33, 2, 0},
        {KeyEvent.KEYCODE_BUTTON_A, 790, 380, 33, 1, 0},
    };

    private static final Map<String, String> DEF = new HashMap<String, String>();

    private static void def(int code, String g, String action) {
        DEF.put(key(0, code, g), action);
    }

    static {
        def(KeyEvent.KEYCODE_BUTTON_A, "t", "play_pause");
        def(KeyEvent.KEYCODE_BUTTON_B, "t", "back");
        def(KeyEvent.KEYCODE_DPAD_LEFT, "t", "prev");
        def(KeyEvent.KEYCODE_DPAD_LEFT, "l", "seek_back");
        def(KeyEvent.KEYCODE_DPAD_RIGHT, "t", "next");
        def(KeyEvent.KEYCODE_DPAD_RIGHT, "l", "seek_fwd");
        def(KeyEvent.KEYCODE_DPAD_UP, "t", "vol_up");
        def(KeyEvent.KEYCODE_DPAD_DOWN, "t", "vol_down");
        def(KeyEvent.KEYCODE_BUTTON_L1, "t", "seek_back");
        def(KeyEvent.KEYCODE_BUTTON_R1, "t", "seek_fwd");
        def(KeyEvent.KEYCODE_BUTTON_START, "t", "recents");
        def(KeyEvent.KEYCODE_BUTTON_SELECT, "t", "home");
        def(KeyEvent.KEYCODE_BUTTON_MODE, "t", "notifications");
        def(KeyEvent.KEYCODE_BUTTON_MODE, "l", "lock");
        def(KeyEvent.KEYCODE_BUTTON_THUMBL, "t", "flashlight");
        def(KeyEvent.KEYCODE_BUTTON_THUMBR, "t", "screenshot");
        def(V_LS_UP, "t", "vol_up");
        def(V_LS_DOWN, "t", "vol_down");
        def(V_LS_LEFT, "t", "prev");
        def(V_LS_RIGHT, "t", "next");
        def(V_RS_LEFT, "t", "seek_back");
        def(V_RS_RIGHT, "t", "seek_fwd");
        def(V_RS_UP, "t", "swipe_down");
        def(V_RS_DOWN, "t", "swipe_up");
    }

    static String key(int profile, int code, String g) {
        return "m" + profile + "_" + code + "_" + g;
    }

    static boolean isNone(String a) {
        return a == null || a.length() == 0 || a.equals("none");
    }

    private static boolean isRepeatable(String a) {
        return "vol_up".equals(a) || "vol_down".equals(a) || "seek_fwd".equals(a) || "seek_back".equals(a)
                || "swipe_up".equals(a) || "swipe_down".equals(a);
    }

    private static boolean isPadEvent(KeyEvent e) {
        int s = e.getSource();
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        return KeyEvent.isGamepadButton(e.getKeyCode());
    }

    /** Действие кнопки в профиле; во втором профиле незаданное берётся из основного. */
    static String action(SharedPreferences p, int profile, int code, String g) {
        String k = key(profile, code, g);
        if (profile == 1) {
            if (p.contains(k)) return p.getString(k, "none");
            k = key(0, code, g);
        }
        String d = DEF.get(k);
        return p.getString(k, d == null ? "none" : d);
    }

    // ---------------------------------------------------------------- состояние

    private static volatile PadView sPad;

    private volatile AccessibilityService svc;
    private volatile Context appCtx;
    private volatile String fgPackage;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SparseArray<KeyState> states = new SparseArray<KeyState>();
    private final SparseBooleanArray vstate = new SparseBooleanArray();
    private final int[] cnt = new int[4];
    private volatile String evStatus = "выключено";
    private Evdev evdev;
    private boolean torchOn;
    private String torchId;
    private long seekTarget;
    private long seekAt;
    private long axesNoteAt;

    private static final class KeyState {
        int mask;
        long maskAt;
        boolean down;
        boolean longFired;
        boolean repeating;
        Runnable longR;
        Runnable repeatR;
        Runnable tapR;
    }

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "Gamepad"; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() {
        return "Кнопки, стики и курки геймпада: медиа, система, приложения; профили по приложениям";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        appCtx = context;
        svc = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
        applyChannels();
    }

    @Override
    public void stop() {
        AccessibilityService s = svc;
        if (s instanceof CoreAccessibilityService) ((CoreAccessibilityService) s).setMotionCapture(false);
        stopEvdev();
        h.removeCallbacksAndMessages(null);
        states.clear();
        vstate.clear();
        svc = null;
    }

    @Override
    public Object execute(Map<String, ?> data) {
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        svc = service;
        if (appCtx == null) appCtx = service;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!isIgnoredPackage(service, pkg)) fgPackage = pkg;
        }
    }

    /** Свой пакет, SystemUI и клавиатура не считаются «приложением на переднем плане». */
    private static boolean isIgnoredPackage(Context c, String pkg) {
        if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.systemui")) return true;
        try {
            String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) {
                ComponentName cn = ComponentName.unflattenFromString(ime);
                if (cn != null && pkg.equals(cn.getPackageName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private SharedPreferences prefs() {
        Context c = appCtx;
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int currentProfile(SharedPreferences p) {
        String pkg = fgPackage;
        String csv = p.getString("profile_apps", "");
        if (pkg == null || csv == null || csv.length() == 0) return 0;
        for (String s : csv.split("[,; \\n]+")) {
            if (s.equals(pkg)) return 1;
        }
        return 0;
    }

    /** Включает или выключает дополнительные каналы по настройкам. */
    private void applyChannels() {
        SharedPreferences p = prefs();
        AccessibilityService s = svc;
        boolean axes = p == null || p.getBoolean("axes_service", true);
        if (s instanceof CoreAccessibilityService) ((CoreAccessibilityService) s).setMotionCapture(axes);
        boolean ev = p == null || p.getBoolean("evdev", true);
        if (ev) startEvdev();
        else stopEvdev();
    }

    // ---------------------------------------------------------------- каналы: служба

    @Override
    public boolean onKeyEvent(KeyEvent e) {
        try {
            return handleKey(e);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean handleKey(KeyEvent e) {
        if (!isPadEvent(e)) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() > 0) return consumes(code);
        cnt[CH_SERVICE]++;
        return route(code, down, CH_SERVICE);
    }

    /** Android 14+: стики, курки и крестовина через службу. */
    @Override
    public void onMotionEvent(MotionEvent e) {
        try {
            feedMotion(e, CH_MOTION);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- движения: оси -> виртуальные кнопки

    private static boolean hasAxis(InputDevice d, int axis) {
        return d != null && d.getMotionRange(axis) != null;
    }

    /** Разбирает MotionEvent джойстика (служба или окно) и передаёт оси дальше. */
    void feedMotion(MotionEvent e, int ch) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return;
        if (e.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        cnt[ch]++;
        InputDevice dev = e.getDevice();
        float lx = e.getAxisValue(MotionEvent.AXIS_X);
        float ly = e.getAxisValue(MotionEvent.AXIS_Y);
        float rx;
        float ry;
        float lt;
        float rt;
        boolean trig = hasAxis(dev, MotionEvent.AXIS_LTRIGGER) || hasAxis(dev, MotionEvent.AXIS_BRAKE);
        boolean rxy = hasAxis(dev, MotionEvent.AXIS_RX) && hasAxis(dev, MotionEvent.AXIS_RY);
        if (trig) {
            lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            if (rxy && !hasAxis(dev, MotionEvent.AXIS_Z)) {
                rx = e.getAxisValue(MotionEvent.AXIS_RX);
                ry = e.getAxisValue(MotionEvent.AXIS_RY);
            } else {
                rx = e.getAxisValue(MotionEvent.AXIS_Z);
                ry = e.getAxisValue(MotionEvent.AXIS_RZ);
            }
        } else if (rxy) {
            rx = e.getAxisValue(MotionEvent.AXIS_RX);
            ry = e.getAxisValue(MotionEvent.AXIS_RY);
            lt = Math.max(0f, e.getAxisValue(MotionEvent.AXIS_Z));
            rt = Math.max(0f, e.getAxisValue(MotionEvent.AXIS_RZ));
        } else {
            rx = e.getAxisValue(MotionEvent.AXIS_Z);
            ry = e.getAxisValue(MotionEvent.AXIS_RZ);
            lt = 0f;
            rt = 0f;
        }
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        feedAxes(ch, lx, ly, rx, ry, lt, rt, hx, hy);
    }

    private boolean vget(int ch, int code) {
        return vstate.get(ch * 100000 + code);
    }

    private void vset(int ch, int code, boolean on) {
        int k = ch * 100000 + code;
        if (vstate.get(k) == on) return;
        vstate.put(k, on);
        route(code, on, ch);
    }

    /** Порог с гистерезисом: включается выше 0.55, выключается ниже 0.35. */
    private void hyst(int ch, int code, float v) {
        boolean cur = vget(ch, code);
        vset(ch, code, cur ? v > 0.35f : v > 0.55f);
    }

    /** Стик как четыре кнопки; при диагонали побеждает ось с большим отклонением. */
    private void stick(int ch, int up, int down, int left, int right, float x, float y) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float xv = ax >= ay ? x : 0f;
        float yv = ay > ax ? y : 0f;
        hyst(ch, left, -xv);
        hyst(ch, right, xv);
        hyst(ch, up, -yv);
        hyst(ch, down, yv);
    }

    /** Все оси в нормированном виде: стики -1..1, курки 0..1, крестовина -1..1. Только главный поток. */
    void feedAxes(int ch, float lx, float ly, float rx, float ry, float lt, float rt, float hx, float hy) {
        stick(ch, V_LS_UP, V_LS_DOWN, V_LS_LEFT, V_LS_RIGHT, lx, ly);
        stick(ch, V_RS_UP, V_RS_DOWN, V_RS_LEFT, V_RS_RIGHT, rx, ry);
        hyst(ch, KeyEvent.KEYCODE_BUTTON_L2, lt);
        hyst(ch, KeyEvent.KEYCODE_BUTTON_R2, rt);
        hyst(ch, KeyEvent.KEYCODE_DPAD_LEFT, -hx);
        hyst(ch, KeyEvent.KEYCODE_DPAD_RIGHT, hx);
        hyst(ch, KeyEvent.KEYCODE_DPAD_UP, -hy);
        hyst(ch, KeyEvent.KEYCODE_DPAD_DOWN, hy);

        PadView v = sPad;
        long now = SystemClock.uptimeMillis();
        if (v != null && v.isLearning() && now - axesNoteAt > 150) {
            axesNoteAt = now;
            float big = Math.max(Math.max(Math.abs(lx), Math.abs(ly)), Math.max(Math.abs(rx), Math.abs(ry)));
            if (big > 0.3f || lt > 0.3f || rt > 0.3f || Math.abs(hx) > 0.3f || Math.abs(hy) > 0.3f) {
                v.noteAxes(CHN[ch] + " | левый стик " + fmt(lx) + " " + fmt(ly) + " | правый " + fmt(rx) + " "
                        + fmt(ry) + " | L2 " + fmt(lt) + " R2 " + fmt(rt) + " | крестовина " + fmt(hx) + " " + fmt(hy));
            }
        }
    }

    private static String fmt(float f) {
        return String.valueOf(Math.round(f * 100f) / 100f);
    }

    // ---------------------------------------------------------------- маршрутизация кнопок

    /** Есть ли у кнопки хоть одно действие в текущем профиле. */
    private boolean consumes(int code) {
        PadView v = sPad;
        if (v != null && v.isLearning()) return true;
        SharedPreferences p = prefs();
        if (p == null || !p.getBoolean("enabled", true)) return false;
        int profile = currentProfile(p);
        return !(isNone(action(p, profile, code, "t")) && isNone(action(p, profile, code, "d"))
                && isNone(action(p, profile, code, "l")));
    }

    /**
     * Единая точка входа для кнопок из любого канала. Нажатия одной кнопки из разных каналов склеиваются:
     * действие стартует с первого канала, а заканчивается, когда отпустил последний.
     * Возвращает true, если событие службы нужно поглотить.
     */
    boolean route(int code, boolean down, int ch) {
        PadView v = sPad;
        if (v != null && v.isLearning()) {
            if (down) v.onPhysicalKey(code, ch);
            return true;
        }
        SharedPreferences p = prefs();
        if (p == null || !p.getBoolean("enabled", true)) return false;
        int profile = currentProfile(p);
        final String t = action(p, profile, code, "t");
        final String d = action(p, profile, code, "d");
        final String l = action(p, profile, code, "l");
        if (isNone(t) && isNone(d) && isNone(l)) return false;

        KeyState s = states.get(code);
        if (s == null) {
            s = new KeyState();
            states.put(code, s);
        }
        long now = SystemClock.uptimeMillis();
        if (down) {
            if (s.mask != 0 && now - s.maskAt > 4000) s.mask = 0; // залипшая кнопка
            boolean first = s.mask == 0;
            s.mask |= 1 << ch;
            s.maskAt = now;
            if (first) pressDown(s, t, d, l, p);
        } else {
            if ((s.mask & (1 << ch)) == 0) return true; // это отпускание уже учтено
            s.mask &= ~(1 << ch);
            if (s.mask == 0) pressUp(s, t, d, l, p);
        }
        return true;
    }

    private void pressDown(final KeyState s, final String t, final String d, final String l, SharedPreferences p) {
        s.down = true;
        s.longFired = false;
        s.repeating = false;
        boolean fast = isRepeatable(t) && isNone(d) && isNone(l);
        if (fast) {
            // удержание повторяет действие (громкость, перемотка, прокрутка)
            perform(t);
            s.repeating = true;
            s.repeatR = new Runnable() {
                @Override public void run() {
                    if (!s.down) return;
                    perform(t);
                    h.postDelayed(this, 140);
                }
            };
            h.postDelayed(s.repeatR, 450);
        } else if (!isNone(l)) {
            s.longR = new Runnable() {
                @Override public void run() {
                    if (!s.down) return;
                    s.longFired = true;
                    perform(l);
                }
            };
            h.postDelayed(s.longR, p.getInt("long_ms", 500));
        }
    }

    private void pressUp(final KeyState s, final String t, final String d, final String l, SharedPreferences p) {
        s.down = false;
        if (s.longR != null) h.removeCallbacks(s.longR);
        if (s.repeatR != null) h.removeCallbacks(s.repeatR);
        if (s.repeating) {
            s.repeating = false;
            return;
        }
        if (s.longFired) {
            s.longFired = false;
            return;
        }
        int windowMs = p.getInt("double_ms", 300);
        if (!isNone(d)) {
            if (s.tapR != null) {
                // второе нажатие в окне: двойное
                h.removeCallbacks(s.tapR);
                s.tapR = null;
                perform(d);
            } else {
                s.tapR = new Runnable() {
                    @Override public void run() {
                        s.tapR = null;
                        if (!isNone(t)) perform(t);
                    }
                };
                h.postDelayed(s.tapR, windowMs);
            }
        } else if (!isNone(t)) {
            perform(t);
        }
    }

    // ---------------------------------------------------------------- канал: сырые события ядра через Shizuku

    private void startEvdev() {
        if (evdev != null) return;
        evdev = new Evdev();
        evdev.start();
    }

    private void stopEvdev() {
        Evdev e = evdev;
        evdev = null;
        if (e != null) e.shutdown();
        evStatus = "выключено";
    }

    /** Устройство ввода, найденное через getevent -pl. */
    private static final class EvDev {
        String path = "";
        String name = "";
        boolean pad;
        final boolean[] has = new boolean[0x20];
        final int[] amin = new int[0x20];
        final int[] amax = new int[0x20];
        final int[] abs = new int[0x20];
    }

    private static final String[] ABS_NAMES = new String[0x12];

    static {
        ABS_NAMES[0] = "ABS_X";
        ABS_NAMES[1] = "ABS_Y";
        ABS_NAMES[2] = "ABS_Z";
        ABS_NAMES[3] = "ABS_RX";
        ABS_NAMES[4] = "ABS_RY";
        ABS_NAMES[5] = "ABS_RZ";
        ABS_NAMES[6] = "ABS_THROTTLE";
        ABS_NAMES[7] = "ABS_RUDDER";
        ABS_NAMES[8] = "ABS_WHEEL";
        ABS_NAMES[9] = "ABS_GAS";
        ABS_NAMES[10] = "ABS_BRAKE";
        ABS_NAMES[16] = "ABS_HAT0X";
        ABS_NAMES[17] = "ABS_HAT0Y";
    }

    private static final Pattern ABS_RE = Pattern.compile(
            "(ABS_[A-Z0-9_]+)\\s*:\\s*value\\s+(-?\\d+),\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)");
    private static final Pattern PAD_RE = Pattern.compile("\\bBTN_(SOUTH|A|GAMEPAD|TL|MODE|START)\\b");

    private static int absIndex(String name) {
        for (int i = 0; i < ABS_NAMES.length; i++) {
            if (name.equals(ABS_NAMES[i])) return i;
        }
        return -1;
    }

    /** Код кнопки ядра (EV_KEY) в код Android; неизвестные уходят в «дополнительные кнопки». */
    private static int evToAndroid(int c) {
        switch (c) {
            case 0x130: return KeyEvent.KEYCODE_BUTTON_A;
            case 0x131: return KeyEvent.KEYCODE_BUTTON_B;
            case 0x133: return KeyEvent.KEYCODE_BUTTON_X;
            case 0x134: return KeyEvent.KEYCODE_BUTTON_Y;
            case 0x136: return KeyEvent.KEYCODE_BUTTON_L1;
            case 0x137: return KeyEvent.KEYCODE_BUTTON_R1;
            case 0x138: return KeyEvent.KEYCODE_BUTTON_L2;
            case 0x139: return KeyEvent.KEYCODE_BUTTON_R2;
            case 0x13a: return KeyEvent.KEYCODE_BUTTON_SELECT;
            case 0x13b: return KeyEvent.KEYCODE_BUTTON_START;
            case 0x13c: return KeyEvent.KEYCODE_BUTTON_MODE;
            case 0x13d: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case 0x13e: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            case 0x220: return KeyEvent.KEYCODE_DPAD_UP;
            case 0x221: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 0x222: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 0x223: return KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return EXTRA_BASE + c;
        }
    }

    private final class Evdev extends Thread {
        private volatile boolean stopFlag;
        private final List<Process> procs = new ArrayList<Process>();
        private final AtomicInteger alive = new AtomicInteger();

        Evdev() {
            setName("gamepad-evdev");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!stopFlag) {
                try {
                    if (!ShizukuBridge.hasPermission()) {
                        evStatus = "Shizuku не готов: запусти приложение Shizuku и выдай хосту доступ";
                    } else if (alive.get() == 0) {
                        connect();
                    }
                } catch (Throwable t) {
                    evStatus = "ошибка: " + t;
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            killAll();
        }

        void shutdown() {
            stopFlag = true;
            interrupt();
            killAll();
        }

        private void killAll() {
            synchronized (procs) {
                for (Process p : procs) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                procs.clear();
            }
        }

        /** Находит геймпады среди устройств ввода и запускает чтение сырых событий каждого. */
        private void connect() {
            ShizukuResult r = ShizukuBridge.exec("getevent -pl");
            if (!r.getOk() || r.getOut().length() == 0) {
                String err = r.getErr();
                evStatus = "getevent не сработал: " + (err.length() > 0 ? err : "код " + r.getCode());
                return;
            }
            List<EvDev> devs = parseDevices(r.getOut());
            if (devs.isEmpty()) {
                evStatus = "геймпад среди устройств ввода не найден (включи его и жди до 5 секунд)";
                return;
            }
            for (final EvDev d : devs) {
                Thread th = new Thread(new Runnable() {
                    @Override public void run() {
                        readDevice(d);
                    }
                }, "evdev-" + d.path);
                th.setDaemon(true);
                alive.incrementAndGet();
                th.start();
            }
        }

        private List<EvDev> parseDevices(String out) {
            List<EvDev> res = new ArrayList<EvDev>();
            EvDev cur = null;
            for (String line : out.split("\n")) {
                if (line.startsWith("add device")) {
                    if (cur != null && cur.pad) res.add(cur);
                    cur = new EvDev();
                    int i = line.indexOf("/dev/");
                    cur.path = i >= 0 ? line.substring(i).trim() : "";
                } else if (cur != null) {
                    String t = line.trim();
                    if (t.startsWith("name:")) cur.name = t.substring(5).trim().replace("\"", "");
                    Matcher m = ABS_RE.matcher(t);
                    if (m.find()) {
                        int idx = absIndex(m.group(1));
                        if (idx >= 0) {
                            cur.has[idx] = true;
                            cur.abs[idx] = Integer.parseInt(m.group(2));
                            cur.amin[idx] = Integer.parseInt(m.group(3));
                            cur.amax[idx] = Integer.parseInt(m.group(4));
                        }
                    }
                    if (PAD_RE.matcher(t).find()) cur.pad = true;
                }
            }
            if (cur != null && cur.pad) res.add(cur);
            List<EvDev> ok = new ArrayList<EvDev>();
            for (EvDev d : res) {
                if (d.path.length() > 0) ok.add(d);
            }
            return ok;
        }

        private void readDevice(EvDev d) {
            Process p = null;
            try {
                p = ShizukuBridge.startProcess("getevent " + d.path);
                if (p == null) {
                    evStatus = "не удалось запустить getevent через Shizuku";
                    return;
                }
                synchronized (procs) {
                    procs.add(p);
                }
                evStatus = "работает: " + d.name + " (" + d.path + ")";
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (!stopFlag && (line = br.readLine()) != null) handleLine(d, line);
            } catch (Throwable ignored) {
            } finally {
                alive.decrementAndGet();
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                if (!stopFlag) evStatus = "поток остановлен, переподключение…";
            }
        }

        private void handleLine(final EvDev d, String line) {
            String[] tk = line.trim().split("\\s+");
            int n = tk.length;
            if (n < 3) return;
            int type;
            int code;
            int value;
            try {
                type = Integer.parseInt(tk[n - 3], 16);
                code = Integer.parseInt(tk[n - 2], 16);
                value = (int) Long.parseLong(tk[n - 1], 16);
            } catch (NumberFormatException nfe) {
                return;
            }
            cnt[CH_EVDEV]++;
            if (type == 1) {
                if (value == 2) return;
                final int kc = evToAndroid(code);
                final boolean down = value == 1;
                h.post(new Runnable() {
                    @Override public void run() {
                        route(kc, down, CH_EVDEV);
                    }
                });
            } else if (type == 3) {
                if (code < d.abs.length) d.abs[code] = value;
            } else if (type == 0 && code == 0) {
                syn(d);
            }
        }

        private float norm(EvDev d, int c) {
            if (!d.has[c]) return 0f;
            float mid = (d.amin[c] + d.amax[c]) / 2f;
            float half = (d.amax[c] - d.amin[c]) / 2f;
            return half == 0f ? 0f : (d.abs[c] - mid) / half;
        }

        private float norm01(EvDev d, int c) {
            if (!d.has[c]) return 0f;
            float span = d.amax[c] - d.amin[c];
            if (span == 0f) return 0f;
            return Math.max(0f, Math.min(1f, (d.abs[c] - d.amin[c]) / span));
        }

        /** Конец пакета событий: пересчитываем оси и отправляем в главный поток. */
        private void syn(EvDev d) {
            float lx = norm(d, 0);
            float ly = norm(d, 1);
            float rx;
            float ry;
            float lt;
            float rt;
            if (d.has[9] || d.has[10]) {
                lt = norm01(d, 10);
                rt = norm01(d, 9);
                if (d.has[3] && d.has[4]) {
                    rx = norm(d, 3);
                    ry = norm(d, 4);
                } else {
                    rx = norm(d, 2);
                    ry = norm(d, 5);
                }
            } else if (d.has[3] && d.has[4]) {
                rx = norm(d, 3);
                ry = norm(d, 4);
                lt = norm01(d, 2);
                rt = norm01(d, 5);
            } else {
                rx = norm(d, 2);
                ry = norm(d, 5);
                lt = 0f;
                rt = 0f;
            }
            final float hx = norm(d, 16);
            final float hy = norm(d, 17);
            final float[] a = {lx, ly, rx, ry, lt, rt};
            h.post(new Runnable() {
                @Override public void run() {
                    feedAxes(CH_EVDEV, a[0], a[1], a[2], a[3], a[4], a[5], hx, hy);
                }
            });
        }
    }

    // ---------------------------------------------------------------- действия

    private void perform(String a) {
        try {
            doPerform(a);
        } catch (Throwable ignored) {
        }
    }

    private void doPerform(String a) throws Exception {
        AccessibilityService s = svc;
        if (s == null || isNone(a)) return;
        if (a.startsWith("app:")) {
            Intent i = s.getPackageManager().getLaunchIntentForPackage(a.substring(4));
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                s.startActivity(i);
            }
            return;
        }
        if (viaController(s, a)) return;
        if (a.equals("play_pause")) media(s, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        else if (a.equals("play")) media(s, KeyEvent.KEYCODE_MEDIA_PLAY);
        else if (a.equals("pause")) media(s, KeyEvent.KEYCODE_MEDIA_PAUSE);
        else if (a.equals("next")) media(s, KeyEvent.KEYCODE_MEDIA_NEXT);
        else if (a.equals("prev")) media(s, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        else if (a.equals("seek_fwd")) media(s, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        else if (a.equals("seek_back")) media(s, KeyEvent.KEYCODE_MEDIA_REWIND);
        else if (a.equals("stop")) media(s, KeyEvent.KEYCODE_MEDIA_STOP);
        else if (a.equals("vol_up")) volume(s, AudioManager.ADJUST_RAISE);
        else if (a.equals("vol_down")) volume(s, AudioManager.ADJUST_LOWER);
        else if (a.equals("mute")) volume(s, AudioManager.ADJUST_TOGGLE_MUTE);
        else if (a.equals("back")) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        else if (a.equals("home")) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        else if (a.equals("recents")) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        else if (a.equals("notifications")) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        else if (a.equals("quick_settings")) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
        else if (a.equals("power")) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        else if (a.equals("lock")) {
            if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        } else if (a.equals("screenshot")) {
            if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
        } else if (a.equals("flashlight")) torch(s);
        else if (a.equals("swipe_up")) swipe(s, true);
        else if (a.equals("swipe_down")) swipe(s, false);
    }

    private static boolean isMediaAction(String a) {
        return "play_pause".equals(a) || "play".equals(a) || "pause".equals(a) || "next".equals(a)
                || "prev".equals(a) || "stop".equals(a) || "seek_fwd".equals(a) || "seek_back".equals(a);
    }

    /** Сессия, которая играет сейчас, иначе самая приоритетная. Нужен доступ к уведомлениям для хоста. */
    private static MediaController activeController(Context c) {
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(
                    new ComponentName(c, "im.manus.universalhost.CoreNotificationListener"));
            MediaController first = null;
            for (MediaController mc : list) {
                PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (first == null) first = mc;
            }
            return first;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Команда напрямую активной медиасессии: не зависит от того, какое приложение на переднем плане
     * и кому система отдаёт медиаклавиши. false: нет доступа к уведомлениям, нет сессии или
     * плеер не умеет перемотку; тогда работает обычная медиаклавиша.
     */
    private boolean viaController(Context c, String a) {
        if (!isMediaAction(a)) return false;
        SharedPreferences p = prefs();
        if (p != null && !p.getBoolean("direct_control", true)) return false;
        MediaController mc = activeController(c);
        if (mc == null) return false;
        MediaController.TransportControls tc = mc.getTransportControls();
        PlaybackState ps = mc.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        if (a.equals("play_pause")) {
            if (playing) tc.pause();
            else tc.play();
        } else if (a.equals("play")) tc.play();
        else if (a.equals("pause")) tc.pause();
        else if (a.equals("next")) tc.skipToNext();
        else if (a.equals("prev")) tc.skipToPrevious();
        else if (a.equals("stop")) tc.stop();
        else {
            if (ps == null || (ps.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return false;
            long now = SystemClock.elapsedRealtime();
            long pos;
            if (now - seekAt < 600) {
                pos = seekTarget; // при удержании плеер не успевает обновить позицию, считаем от прошлой цели
            } else {
                pos = ps.getPosition();
                if (playing) pos += (long) ((now - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
            }
            long step = (p == null ? 10 : p.getInt("seek_s", 10)) * 1000L;
            seekTarget = Math.max(0, pos + (a.equals("seek_fwd") ? step : -step));
            seekAt = now;
            tc.seekTo(seekTarget);
        }
        return true;
    }

    private static void media(Context c, int code) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static void volume(Context c, int direction) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    private void torch(Context c) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        if (torchId == null) {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (flash != null && flash.booleanValue() && facing != null
                        && facing.intValue() == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
        }
        if (torchId == null) return;
        torchOn = !torchOn;
        cm.setTorchMode(torchId, torchOn);
    }

    private static void swipe(AccessibilityService s, boolean up) {
        android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f;
        float y1 = dm.heightPixels * (up ? 0.7f : 0.3f);
        float y2 = dm.heightPixels * (up ? 0.3f : 0.7f);
        Path p = new Path();
        p.moveTo(x, y1);
        p.lineTo(x, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 220));
        s.dispatchGesture(b.build(), null, null);
    }

    // ---------------------------------------------------------------- ISettingsProvider

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Раскладка геймпада"));
        l.add(SettingItem.toggle("enabled", "Перехват кнопок геймпада",
                "Выключи, если кнопки нужны другому приложению", true));
        l.add(SettingItem.custom("pad", "Геймпад",
                "Коснись кнопки на картинке или нажми её на геймпаде, стик подвигай"));
        l.add(SettingItem.section("Источники ввода"));
        l.add(SettingItem.toggle("evdev", "Читать геймпад напрямую (Shizuku)",
                "Стики, курки, крестовина и все кнопки при любом экране и любой версии Android. Нужен запущенный Shizuku",
                true));
        l.add(SettingItem.toggle("axes_service", "Стики и курки через службу (Android 14+)",
                "Пока включено, другие приложения не получают движения стиков", true));
        l.add(SettingItem.section("Плеер"));
        l.add(SettingItem.toggle("direct_control", "Управлять плеером напрямую",
                "Команды идут активной медиасессии (нужен доступ к уведомлениям), иначе медиаклавишами", true));
        l.add(SettingItem.action("grant_notifications", "Доступ к уведомлениям",
                "Откроет системные настройки: включи Universal Host"));
        l.add(SettingItem.slider("seek_s", "Шаг перемотки, с", "", 5, 60, 5, 10));
        l.add(SettingItem.section("Второй профиль"));
        l.add(SettingItem.appPicker("profile_apps", "Приложения второго профиля",
                "Не выбрано: работает только основной профиль", ""));
        l.add(SettingItem.section("Тайминги"));
        l.add(SettingItem.slider("long_ms", "Долгое нажатие, мс", "", 300, 1000, 50, 500));
        l.add(SettingItem.slider("double_ms", "Окно двойного нажатия, мс", "", 150, 600, 50, 300));
        l.add(SettingItem.section("Сброс"));
        l.add(SettingItem.action("reset", "Вернуть раскладку по умолчанию", "Сбросит оба профиля и дополнительные кнопки"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if ("grant_notifications".equals(key)) {
            Context c = appCtx;
            if (c != null) {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            }
            return;
        }
        if ("evdev".equals(key) || "axes_service".equals(key)) {
            applyChannels();
            return;
        }
        if ("reset".equals(key)) {
            SharedPreferences p = prefs();
            if (p != null) {
                SharedPreferences.Editor ed = p.edit();
                for (String k : new ArrayList<String>(p.getAll().keySet())) {
                    if (k.startsWith("m0_") || k.startsWith("m1_")) ed.remove(k);
                }
                ed.remove("extra_codes");
                ed.apply();
            }
            PadView v = sPad;
            if (v != null) v.invalidate();
        }
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = sizePx / 100f;
        Paint k = new Paint(Paint.ANTI_ALIAS_FLAG);
        k.setColor(Color.WHITE);
        k.setStyle(Paint.Style.STROKE);
        k.setStrokeWidth(5 * s);
        c.drawRoundRect(new RectF(10 * s, 28 * s, 90 * s, 78 * s), 24 * s, 24 * s, k);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(Color.WHITE);
        c.drawCircle(32 * s, 48 * s, 7 * s, f);
        c.drawCircle(66 * s, 60 * s, 7 * s, f);
        c.drawCircle(72 * s, 42 * s, 3.5f * s, f);
        c.drawCircle(82 * s, 50 * s, 3.5f * s, f);
        c.drawRect(24 * s, 62 * s, 40 * s, 66 * s, f);
        c.drawRect(30 * s, 56 * s, 34 * s, 72 * s, f);
        return b;
    }

    // ---------------------------------------------------------------- ISettingsViewProvider

    @Override
    public View createSettingsView(Context context, String key) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        final TextView status = new TextView(context);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(13f);
        final PadView pad = new PadView(context, this, prefs, status);
        pad.note("Нажми кнопку на геймпаде или подвигай стик");

        final TextView[] tabs = new TextView[2];
        String[] names = {"Основной", "Для выбранных приложений"};
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView tv = new TextView(context);
            tv.setText(names[i]);
            tv.setTextSize(14f);
            tv.setGravity(Gravity.CENTER);
            int pad8 = dp(context, 8);
            tv.setPadding(pad8, pad8, pad8, pad8);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pad.setProfile(idx);
                    styleTabs(tabs, idx);
                }
            });
            tabs[i] = tv;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(context, 6);
            row.addView(tv, lp);
        }
        styleTabs(tabs, 0);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private static void styleTabs(TextView[] tabs, int selected) {
        for (int i = 0; i < tabs.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(24f);
            if (i == selected) {
                bg.setColor(Color.WHITE);
                tabs[i].setTextColor(Color.BLACK);
            } else {
                bg.setColor(Color.TRANSPARENT);
                bg.setStroke(2, Color.WHITE);
                tabs[i].setTextColor(Color.WHITE);
            }
            tabs[i].setBackground(bg);
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Сводка по каналам для строки состояния в настройках. */
    String diagText() {
        String axes34 = Build.VERSION.SDK_INT >= 34 ? "доступны" : "нужен Android 14+ (сейчас API " + Build.VERSION.SDK_INT + ")";
        return "Событий: служба-кнопки " + cnt[CH_SERVICE] + ", окно " + cnt[CH_WINDOW] + ", служба-оси " + cnt[CH_MOTION]
                + ", evdev " + cnt[CH_EVDEV] + "\nevdev: " + evStatus + "\nОси через службу: " + axes34;
    }

    // ---------------------------------------------------------------- названия и значки действий

    static String slotName(int code) {
        switch (code) {
            case V_LS_UP: return "Левый стик вверх";
            case V_LS_DOWN: return "Левый стик вниз";
            case V_LS_LEFT: return "Левый стик влево";
            case V_LS_RIGHT: return "Левый стик вправо";
            case V_RS_UP: return "Правый стик вверх";
            case V_RS_DOWN: return "Правый стик вниз";
            case V_RS_LEFT: return "Правый стик влево";
            case V_RS_RIGHT: return "Правый стик вправо";
            default: break;
        }
        if (code >= EXTRA_BASE) return "Кнопка 0x" + Integer.toHexString(code - EXTRA_BASE) + " (evdev)";
        String n = KeyEvent.keyCodeToString(code);
        return n.startsWith("KEYCODE_") ? n.substring(8) : n;
    }

    static String actionLabel(Context c, String a) {
        if (isNone(a)) return "Не назначено";
        if (a.startsWith("app:")) {
            try {
                PackageManager pm = c.getPackageManager();
                return pm.getApplicationInfo(a.substring(4), 0).loadLabel(pm).toString();
            } catch (Throwable t) {
                return a.substring(4);
            }
        }
        for (String[] x : ACTIONS) {
            if (x[0].equals(a)) return x[1];
        }
        return a;
    }

    static Drawable appIconOf(Context c, String a) {
        if (a == null || !a.startsWith("app:")) return null;
        try {
            return c.getPackageManager().getApplicationIcon(a.substring(4));
        } catch (Throwable t) {
            return null;
        }
    }

    static Bitmap iconBitmap(Context c, String a, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        drawIcon(new Canvas(b), a, px / 2f, px / 2f, px * 0.42f, 255, appIconOf(c, a));
        return b;
    }

    private static void poly(Canvas c, Paint p, float cx, float cy, float s, float... v) {
        Path path = new Path();
        for (int i = 0; i + 1 < v.length; i += 2) {
            float x = cx + v[i] * s;
            float y = cy + v[i + 1] * s;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, p);
    }

    private static void box(Canvas c, Paint p, float cx, float cy, float s, float l, float t, float r, float b) {
        c.drawRect(cx + l * s, cy + t * s, cx + r * s, cy + b * s, p);
    }

    private static void seg(Canvas c, Paint p, float cx, float cy, float s, float x1, float y1, float x2, float y2) {
        c.drawLine(cx + x1 * s, cy + y1 * s, cx + x2 * s, cy + y2 * s, p);
    }

    /** Значок действия без текста; [s] — половина размера. */
    static void drawIcon(Canvas c, String a, float cx, float cy, float s, int alpha, Drawable appIcon) {
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(Color.WHITE);
        f.setAlpha(alpha);
        f.setStyle(Paint.Style.FILL);
        Paint k = new Paint(f);
        k.setStyle(Paint.Style.STROKE);
        k.setStrokeWidth(s * 0.16f);
        k.setStrokeCap(Paint.Cap.ROUND);
        k.setStrokeJoin(Paint.Join.ROUND);

        if (isNone(a)) {
            k.setAlpha(alpha / 3);
            c.drawCircle(cx, cy, s * 0.25f, k);
            return;
        }
        if (a.startsWith("app:")) {
            if (appIcon != null) {
                appIcon.setBounds(Math.round(cx - s), Math.round(cy - s), Math.round(cx + s), Math.round(cy + s));
                appIcon.setAlpha(alpha);
                appIcon.draw(c);
            } else {
                c.drawRoundRect(new RectF(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f), s * 0.3f, s * 0.3f, k);
            }
            return;
        }
        if (a.equals("play")) {
            poly(c, f, cx, cy, s, -0.45f, -0.7f, -0.45f, 0.7f, 0.75f, 0f);
        } else if (a.equals("pause")) {
            box(c, f, cx, cy, s, -0.6f, -0.7f, -0.15f, 0.7f);
            box(c, f, cx, cy, s, 0.15f, -0.7f, 0.6f, 0.7f);
        } else if (a.equals("play_pause")) {
            poly(c, f, cx, cy, s, -0.85f, -0.65f, -0.85f, 0.65f, 0.05f, 0f);
            box(c, f, cx, cy, s, 0.3f, -0.65f, 0.55f, 0.65f);
            box(c, f, cx, cy, s, 0.7f, -0.65f, 0.95f, 0.65f);
        } else if (a.equals("next")) {
            poly(c, f, cx, cy, s, -0.75f, -0.65f, -0.75f, 0.65f, 0.25f, 0f);
            box(c, f, cx, cy, s, 0.4f, -0.65f, 0.75f, 0.65f);
        } else if (a.equals("prev")) {
            poly(c, f, cx, cy, s, 0.75f, -0.65f, 0.75f, 0.65f, -0.25f, 0f);
            box(c, f, cx, cy, s, -0.75f, -0.65f, -0.4f, 0.65f);
        } else if (a.equals("seek_fwd")) {
            poly(c, f, cx, cy, s, -0.9f, -0.65f, -0.9f, 0.65f, -0.05f, 0f);
            poly(c, f, cx, cy, s, -0.05f, -0.65f, -0.05f, 0.65f, 0.8f, 0f);
        } else if (a.equals("seek_back")) {
            poly(c, f, cx, cy, s, 0.9f, -0.65f, 0.9f, 0.65f, 0.05f, 0f);
            poly(c, f, cx, cy, s, 0.05f, -0.65f, 0.05f, 0.65f, -0.8f, 0f);
        } else if (a.equals("stop")) {
            box(c, f, cx, cy, s, -0.6f, -0.6f, 0.6f, 0.6f);
        } else if (a.equals("vol_up") || a.equals("vol_down") || a.equals("mute")) {
            box(c, f, cx, cy, s, -0.95f, -0.3f, -0.6f, 0.3f);
            poly(c, f, cx, cy, s, -0.6f, -0.3f, -0.05f, -0.75f, -0.05f, 0.75f, -0.6f, 0.3f);
            if (a.equals("vol_up")) {
                seg(c, k, cx, cy, s, 0.3f, 0f, 0.95f, 0f);
                seg(c, k, cx, cy, s, 0.625f, -0.325f, 0.625f, 0.325f);
            } else if (a.equals("vol_down")) {
                seg(c, k, cx, cy, s, 0.3f, 0f, 0.95f, 0f);
            } else {
                seg(c, k, cx, cy, s, 0.3f, -0.35f, 0.95f, 0.35f);
                seg(c, k, cx, cy, s, 0.3f, 0.35f, 0.95f, -0.35f);
            }
        } else if (a.equals("back")) {
            poly(c, f, cx, cy, s, 0.6f, -0.8f, -0.6f, 0f, 0.6f, 0.8f);
        } else if (a.equals("home")) {
            c.drawCircle(cx, cy, s * 0.7f, k);
        } else if (a.equals("recents")) {
            c.drawRoundRect(new RectF(cx - s * 0.65f, cy - s * 0.65f, cx + s * 0.65f, cy + s * 0.65f), s * 0.15f, s * 0.15f, k);
        } else if (a.equals("notifications")) {
            Path bell = new Path();
            bell.moveTo(cx - 0.7f * s, cy + 0.45f * s);
            bell.lineTo(cx - 0.5f * s, cy - 0.2f * s);
            bell.quadTo(cx, cy - 1.0f * s, cx + 0.5f * s, cy - 0.2f * s);
            bell.lineTo(cx + 0.7f * s, cy + 0.45f * s);
            bell.close();
            c.drawPath(bell, f);
            c.drawCircle(cx, cy + 0.72f * s, 0.2f * s, f);
        } else if (a.equals("quick_settings")) {
            seg(c, k, cx, cy, s, -0.8f, -0.5f, 0.8f, -0.5f);
            seg(c, k, cx, cy, s, -0.8f, 0f, 0.8f, 0f);
            seg(c, k, cx, cy, s, -0.8f, 0.5f, 0.8f, 0.5f);
            c.drawCircle(cx - 0.3f * s, cy - 0.5f * s, 0.2f * s, f);
            c.drawCircle(cx + 0.3f * s, cy, 0.2f * s, f);
            c.drawCircle(cx - 0.1f * s, cy + 0.5f * s, 0.2f * s, f);
        } else if (a.equals("lock")) {
            box(c, f, cx, cy, s, -0.6f, 0f, 0.6f, 0.8f);
            c.drawArc(new RectF(cx - 0.4f * s, cy - 0.8f * s, cx + 0.4f * s, cy + 0.2f * s), 180f, 180f, false, k);
        } else if (a.equals("screenshot")) {
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (float[] q : corners) {
                Path p = new Path();
                p.moveTo(cx + q[0] * 0.3f * s, cy + q[1] * 0.8f * s);
                p.lineTo(cx + q[0] * 0.8f * s, cy + q[1] * 0.8f * s);
                p.lineTo(cx + q[0] * 0.8f * s, cy + q[1] * 0.3f * s);
                c.drawPath(p, k);
            }
            c.drawCircle(cx, cy, 0.18f * s, f);
        } else if (a.equals("power")) {
            c.drawArc(new RectF(cx - 0.65f * s, cy - 0.55f * s, cx + 0.65f * s, cy + 0.75f * s), -60f, 300f, false, k);
            seg(c, k, cx, cy, s, 0f, -0.85f, 0f, -0.05f);
        } else if (a.equals("flashlight")) {
            poly(c, f, cx, cy, s, 0.1f, -0.9f, -0.5f, 0.1f, -0.05f, 0.1f, -0.2f, 0.9f, 0.5f, -0.2f, 0.05f, -0.2f);
        } else if (a.equals("swipe_up")) {
            poly(c, f, cx, cy, s, 0f, -0.85f, -0.7f, -0.05f, 0.7f, -0.05f);
            box(c, f, cx, cy, s, -0.2f, -0.05f, 0.2f, 0.85f);
        } else if (a.equals("swipe_down")) {
            poly(c, f, cx, cy, s, 0f, 0.85f, -0.7f, 0.05f, 0.7f, 0.05f);
            box(c, f, cx, cy, s, -0.2f, -0.85f, 0.2f, 0.05f);
        } else {
            c.drawCircle(cx, cy, s * 0.4f, k);
        }
    }

    // ---------------------------------------------------------------- картинка геймпада

    static final class PadView extends View {
        private final GamepadModule owner;
        private final SharedPreferences prefs;
        private final TextView status;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<String, Drawable> appIcons = new HashMap<String, Drawable>();
        private int profile;
        private int hiCode = -1;
        private long hiUntil;
        private AlertDialog editor;
        private String lastNote = "";
        private String lastAxes = "";
        private int lastCode = -1;
        private long lastCodeAt;

        PadView(Context context, GamepadModule owner, SharedPreferences prefs, TextView status) {
            super(context);
            this.owner = owner;
            this.prefs = prefs;
            this.status = status;
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        void setProfile(int p) {
            profile = p;
            invalidate();
        }

        /** Экран настроек на виду: кнопки геймпада выбирают слот, а не выполняют действия. */
        boolean isLearning() {
            return isAttachedToWindow() && getWindowVisibility() == View.VISIBLE;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            sPad = this;
            requestFocus();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (sPad == this) sPad = null;
            if (editor != null && editor.isShowing()) editor.dismiss();
            super.onDetachedFromWindow();
        }

        private void refreshStatus() {
            status.setText(lastNote + (lastAxes.length() > 0 ? "\n" + lastAxes : "") + "\n" + owner.diagText());
        }

        void note(final String s) {
            post(new Runnable() {
                @Override public void run() {
                    lastNote = s;
                    refreshStatus();
                }
            });
        }

        void noteAxes(final String s) {
            post(new Runnable() {
                @Override public void run() {
                    lastAxes = s;
                    refreshStatus();
                }
            });
        }

        /** Кнопки и направления из любого канала при открытом экране настроек. */
        void onPhysicalKey(final int code, final int ch) {
            post(new Runnable() {
                @Override public void run() {
                    long now = SystemClock.uptimeMillis();
                    if (code == lastCode && now - lastCodeAt < 500) return; // тот же нажим пришёл по другому каналу
                    lastCode = code;
                    lastCodeAt = now;
                    lastNote = "Нажато: " + slotName(code) + " (" + code + "), канал: " + CHN[ch];
                    refreshStatus();
                    if (editor != null && editor.isShowing()) return;
                    if (slotFor(code) == null) addExtra(code);
                    hiCode = code;
                    hiUntil = SystemClock.uptimeMillis() + 1200;
                    invalidate();
                    openEditor(code);
                }
            });
        }

        // дополнительные кнопки: коды, которых нет на картинке, запоминаются и рисуются внизу
        private List<Integer> extras() {
            List<Integer> res = new ArrayList<Integer>();
            String csv = prefs.getString("extra_codes", "");
            if (csv == null) return res;
            for (String s : csv.split(",")) {
                if (s.length() == 0) continue;
                try {
                    res.add(Integer.valueOf(s));
                } catch (NumberFormatException ignored) {
                }
            }
            return res;
        }

        private void addExtra(int code) {
            List<Integer> ex = extras();
            if (ex.contains(Integer.valueOf(code)) || ex.size() >= 10) return;
            StringBuilder sb = new StringBuilder();
            for (Integer i : ex) sb.append(i).append(',');
            sb.append(code);
            prefs.edit().putString("extra_codes", sb.toString()).apply();
        }

        private List<int[]> slots() {
            List<int[]> all = new ArrayList<int[]>();
            for (int[] sl : SLOTS) all.add(sl);
            List<Integer> ex = extras();
            for (int i = 0; i < ex.size(); i++) {
                all.add(new int[]{ex.get(i).intValue(), 70 + i * 90, 665, 32, 0, 0});
            }
            return all;
        }

        private int[] slotFor(int code) {
            for (int[] sl : slots()) {
                if (sl[0] == code) return sl;
            }
            return null;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (isPadEvent(event) && event.getRepeatCount() == 0) {
                owner.cnt[CH_WINDOW]++;
                owner.route(keyCode, true, CH_WINDOW);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (isPadEvent(event)) {
                owner.route(keyCode, false, CH_WINDOW);
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                owner.feedMotion(e, CH_WINDOW);
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * 0.70f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float sc = getWidth() / 1000f;
            c.save();
            c.scale(sc, sc);

            body.setStyle(Paint.Style.FILL);
            body.setColor(Color.rgb(18, 18, 18));
            c.drawRoundRect(new RectF(70, 150, 930, 615), 210, 210, body);
            body.setStyle(Paint.Style.STROKE);
            body.setStrokeWidth(4);
            body.setColor(Color.rgb(110, 110, 110));
            c.drawRoundRect(new RectF(70, 150, 930, 615), 210, 210, body);

            boolean hi = SystemClock.uptimeMillis() < hiUntil;
            for (int[] sl : slots()) {
                int code = sl[0];
                float x = sl[1];
                float y = sl[2];
                float r = sl[3];

                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.rgb(30, 30, 30));
                c.drawCircle(x, y, r, fill);

                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(5);
                ring.setColor(colorOf(sl[4]));
                c.drawCircle(x, y, r, ring);
                if (hi && code == hiCode) {
                    ring.setColor(Color.WHITE);
                    ring.setStrokeWidth(12);
                    c.drawCircle(x, y, r + 6, ring);
                }

                String t = action(prefs, profile, code, "t");
                String d = action(prefs, profile, code, "d");
                String l = action(prefs, profile, code, "l");
                boolean inherited = profile == 1 && !prefs.contains(key(1, code, "t"));
                if (isNone(t) && sl[5] != 0) {
                    drawDirection(c, x, y, r, sl[5]);
                } else {
                    drawIcon(c, t, x, y, r * 0.62f, inherited ? 90 : 255, iconFor(t));
                }

                fill.setColor(Color.WHITE);
                if (!isNone(d)) c.drawCircle(x - r * 0.45f, y + r * 0.82f, 4, fill);
                if (!isNone(l)) c.drawCircle(x + r * 0.45f, y + r * 0.82f, 4, fill);
            }
            c.restore();
            if (hi) postInvalidateDelayed(Math.max(50, hiUntil - SystemClock.uptimeMillis() + 20));
        }

        /** Бледная стрелка на слоте направления без действия. */
        private void drawDirection(Canvas c, float x, float y, float r, int dir) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(90, 255, 255, 255));
            float s = r * 0.5f;
            Path p = new Path();
            if (dir == 1) {
                p.moveTo(x, y - s);
                p.lineTo(x - s, y + s * 0.6f);
                p.lineTo(x + s, y + s * 0.6f);
            } else if (dir == 2) {
                p.moveTo(x, y + s);
                p.lineTo(x - s, y - s * 0.6f);
                p.lineTo(x + s, y - s * 0.6f);
            } else if (dir == 3) {
                p.moveTo(x - s, y);
                p.lineTo(x + s * 0.6f, y - s);
                p.lineTo(x + s * 0.6f, y + s);
            } else {
                p.moveTo(x + s, y);
                p.lineTo(x - s * 0.6f, y - s);
                p.lineTo(x - s * 0.6f, y + s);
            }
            p.close();
            c.drawPath(p, fill);
        }

        private Drawable iconFor(String a) {
            if (a == null || !a.startsWith("app:")) return null;
            Drawable d = appIcons.get(a);
            if (d == null) {
                d = appIconOf(getContext(), a);
                if (d != null) appIcons.put(a, d);
            }
            return d;
        }

        private static int colorOf(int kind) {
            switch (kind) {
                case 1: return Color.rgb(90, 200, 90);
                case 2: return Color.rgb(230, 70, 70);
                case 3: return Color.rgb(80, 150, 255);
                case 4: return Color.rgb(240, 200, 60);
                default: return Color.rgb(190, 190, 190);
            }
        }

        private int[] slotAt(float px, float py) {
            float sc = getWidth() / 1000f;
            float x = px / sc;
            float y = py / sc;
            int[] best = null;
            float bestD = Float.MAX_VALUE;
            for (int[] sl : slots()) {
                float dx = x - sl[1];
                float dy = y - sl[2];
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist <= sl[3] * 1.25f && dist < bestD) {
                    best = sl;
                    bestD = dist;
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int[] sl = slotAt(e.getX(), e.getY());
                if (sl != null) {
                    performClick();
                    openEditor(sl[0]);
                }
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        // ------------------------------------------------------------ выбор действий

        private void openEditor(final int code) {
            final Context ctx = getContext();
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, 16);
            col.setPadding(pad, dp(ctx, 8), pad, 0);
            for (int g = 0; g < 3; g++) {
                final int gi = g;
                String a = action(prefs, profile, code, GK[g]);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
                ImageView iv = new ImageView(ctx);
                iv.setImageBitmap(iconBitmap(ctx, a, dp(ctx, 40)));
                row.addView(iv, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
                LinearLayout txt = new LinearLayout(ctx);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.setPadding(dp(ctx, 12), 0, 0, 0);
                TextView title = new TextView(ctx);
                title.setText(GNAME[g]);
                title.setTextSize(13f);
                title.setTextColor(Color.LTGRAY);
                TextView value = new TextView(ctx);
                value.setText(actionLabel(ctx, a));
                value.setTextSize(17f);
                value.setTextColor(Color.WHITE);
                txt.addView(title);
                txt.addView(value);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        chooseAction(code, gi);
                    }
                });
                col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (editor != null && editor.isShowing()) editor.dismiss();
            editor = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(slotName(code) + (profile == 1 ? "  ·  для приложений" : ""))
                    .setView(col)
                    .setPositiveButton("Готово", null)
                    .create();
            editor.show();
        }

        private void chooseAction(final int code, final int g) {
            final Context ctx = getContext();
            final List<String> ids = new ArrayList<String>();
            List<String> labels = new ArrayList<String>();
            if (profile == 1) {
                ids.add("@inherit");
                labels.add("Как в основном профиле");
            }
            ids.add("none");
            labels.add("Не назначено");
            for (String[] a : ACTIONS) {
                ids.add(a[0]);
                labels.add(a[1]);
            }
            ids.add("@app");
            labels.add("Приложение…");
            new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(GNAME[g])
                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            String id = ids.get(which);
                            if (id.equals("@app")) chooseApp(code, g);
                            else apply(code, g, id);
                        }
                    })
                    .show();
        }

        private void chooseApp(final int code, final int g) {
            final Context ctx = getContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    PackageManager pm = ctx.getPackageManager();
                    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    final List<String[]> apps = new ArrayList<String[]>();
                    java.util.HashSet<String> seen = new java.util.HashSet<String>();
                    for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                        String pkg = ri.activityInfo.packageName;
                        if (seen.add(pkg)) apps.add(new String[]{pkg, ri.loadLabel(pm).toString()});
                    }
                    java.util.Collections.sort(apps, new java.util.Comparator<String[]>() {
                        @Override public int compare(String[] x, String[] y) {
                            return x[1].compareToIgnoreCase(y[1]);
                        }
                    });
                    post(new Runnable() {
                        @Override public void run() {
                            if (!isAttachedToWindow()) return;
                            String[] labels = new String[apps.size()];
                            for (int i = 0; i < labels.length; i++) labels[i] = apps.get(i)[1];
                            new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Приложение")
                                    .setItems(labels, new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d, int which) {
                                            apply(code, g, "app:" + apps.get(which)[0]);
                                        }
                                    })
                                    .show();
                        }
                    });
                }
            }).start();
        }

        private void apply(int code, int g, String id) {
            String k = key(profile, code, GK[g]);
            if (id.equals("@inherit")) prefs.edit().remove(k).apply();
            else prefs.edit().putString(k, id).apply();
            invalidate();
            openEditor(code);
        }
    }
}
