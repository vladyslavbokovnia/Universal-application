package im.manus.universalhost;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Общая схема назначения кнопок геймпада для модулей без Shizuku (Пульт, Аудиоплеер).
 *
 * Кнопки приходят от службы специальных возможностей (IKeyHandler), стики, курки и крестовина:
 * от неё же на Android 14+ (IMotionHandler + CoreAccessibilityService.setMotionCapture). Пока открыта
 * картинка геймпада, нажатия выбирают кнопку на картинке, а не выполняют действия.
 *
 * У каждой кнопки три действия: нажатие, двойное, долгое. Настройка идёт блоками на странице
 * (без всплывающих окон), белый текст на тёмном фоне. Значения лежат в prefs модуля под ключами
 * "pm_&lt;код&gt;_&lt;t|d|l&gt;".
 */
public final class PadMapper {

    /** Выполнить действие модуля (главный поток). */
    public interface Performer {
        void perform(String action);
    }

    public static final int V_LS_UP = 1001;
    public static final int V_LS_DOWN = 1002;
    public static final int V_LS_LEFT = 1003;
    public static final int V_LS_RIGHT = 1004;
    public static final int V_RS_UP = 1005;
    public static final int V_RS_DOWN = 1006;
    public static final int V_RS_LEFT = 1007;
    public static final int V_RS_RIGHT = 1008;

    private static final int CH_SERVICE = 0;
    private static final int CH_WINDOW = 1;
    private static final int CH_MOTION = 2;
    private static final String[] CHN = {"служба", "окно", "служба-оси"};

    private static final String[] GK = {"t", "d", "l"};
    private static final String[] GNAME = {"Нажатие", "Двойное нажатие", "Долгое нажатие"};

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

    private final SharedPreferences prefs;
    private final String[][] actions;
    private final Performer performer;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, String> defs = new HashMap<String, String>();
    private final List<String[]> defList = new ArrayList<String[]>();
    private final SparseArray<KeyState> states = new SparseArray<KeyState>();
    private final SparseBooleanArray vstate = new SparseBooleanArray();
    private final int[] cnt = new int[3];
    private volatile PadView view;

    /**
     * @param actions список действий модуля: {id, подпись}; порядок = порядок в списке выбора.
     *                Значки есть у media-действий: play_pause, play, pause, prev, next, seek_back,
     *                seek_fwd, stop, vol_up, vol_down, mute (остальные получают кружок).
     */
    public PadMapper(SharedPreferences prefs, String[][] actions, Performer performer) {
        this.prefs = prefs;
        this.actions = actions;
        this.performer = performer;
    }

    // ------------------------------------------------------------ назначения

    static String key(int code, String g) {
        return "pm_" + code + "_" + g;
    }

    private static boolean isNone(String a) {
        return a == null || a.length() == 0 || a.equals("none");
    }

    /** Назначение по умолчанию; вызывать до первого использования. */
    public PadMapper addDefault(int code, String gesture, String action) {
        defs.put(key(code, gesture), action);
        defList.add(new String[]{String.valueOf(code), gesture, action});
        return this;
    }

    private String action(int code, String g) {
        String k = key(code, g);
        String d = defs.get(k);
        return prefs.getString(k, d == null ? "none" : d);
    }

    /**
     * Переносит назначения старого экрана (список «действие — кнопка»: ключи prefix + id действия,
     * значение — код кнопки) в новую схему. Один раз.
     */
    public void migrateLegacy(String prefix) {
        if (prefs.getBoolean("pm_migrated", false)) return;
        Map<String, String> put = new HashMap<String, String>();
        for (String[] a : actions) {
            String lk = prefix + a[0];
            if (!prefs.contains(lk)) continue;
            int code = prefs.getInt(lk, -1);
            for (String[] d : defList) {
                if (!d[2].equals(a[0]) || !d[1].equals("t")) continue;
                if (Integer.parseInt(d[0]) != code) put.put(key(Integer.parseInt(d[0]), "t"), "none");
            }
        }
        for (String[] a : actions) {
            String lk = prefix + a[0];
            if (!prefs.contains(lk)) continue;
            int code = prefs.getInt(lk, -1);
            if (code >= 0) put.put(key(code, "t"), a[0]);
        }
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, String> en : put.entrySet()) {
            if (!prefs.contains(en.getKey()) || !en.getValue().equals("none")) e.putString(en.getKey(), en.getValue());
        }
        e.putBoolean("pm_migrated", true);
        e.apply();
    }

    /** Возвращает кнопкам значения по умолчанию. */
    public void reset() {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
            if (k.startsWith("pm_") && !k.equals("pm_migrated")) e.remove(k);
        }
        e.remove("extra_codes");
        e.apply();
        PadView v = view;
        if (v != null) v.invalidate();
    }

    /** Включает или выключает получение стиков и курков службой (Android 14+) по настройке axes_service. */
    public void applyAxesCapture(Context serviceContext) {
        if (serviceContext instanceof CoreAccessibilityService) {
            ((CoreAccessibilityService) serviceContext).setMotionCapture(prefs.getBoolean("axes_service", true));
        }
    }

    /** Останавливает таймеры и отпускает кнопки. */
    public void stop(Context serviceContext) {
        if (serviceContext instanceof CoreAccessibilityService) {
            ((CoreAccessibilityService) serviceContext).setMotionCapture(false);
        }
        h.removeCallbacksAndMessages(null);
        states.clear();
        vstate.clear();
    }

    // ------------------------------------------------------------ вход

    public static boolean isPadEvent(KeyEvent e) {
        int s = e.getSource();
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        int c = e.getKeyCode();
        if (KeyEvent.isGamepadButton(c)) return true;
        return c >= KeyEvent.KEYCODE_DPAD_UP && c <= KeyEvent.KEYCODE_DPAD_CENTER
                && (s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private boolean isLearning() {
        PadView v = view;
        return v != null && v.isAttachedToWindow() && v.getWindowVisibility() == View.VISIBLE;
    }

    /** Из IKeyHandler.onKeyEvent: true, если кнопку поглотили. */
    public boolean onKeyEvent(KeyEvent e) {
        try {
            if (!isPadEvent(e)) return false;
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (down && e.getRepeatCount() > 0) return consumes(code);
            cnt[CH_SERVICE]++;
            return route(code, down, CH_SERVICE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Из IMotionHandler.onMotionEvent (Android 14+). */
    public void onMotionEvent(MotionEvent e) {
        try {
            feedMotion(e, CH_MOTION);
        } catch (Throwable ignored) {
        }
    }

    private boolean consumes(int code) {
        if (isLearning()) return true;
        boolean mapped = !(isNone(action(code, "t")) && isNone(action(code, "d")) && isNone(action(code, "l")));
        return mapped && prefs.getBoolean("intercept", true);
    }

    private static boolean hasAxis(InputDevice d, int axis) {
        return d != null && d.getMotionRange(axis) != null;
    }

    private void feedMotion(MotionEvent e, int ch) {
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

    private void feedAxes(int ch, float lx, float ly, float rx, float ry, float lt, float rt, float hx, float hy) {
        stick(ch, V_LS_UP, V_LS_DOWN, V_LS_LEFT, V_LS_RIGHT, lx, ly);
        stick(ch, V_RS_UP, V_RS_DOWN, V_RS_LEFT, V_RS_RIGHT, rx, ry);
        hyst(ch, KeyEvent.KEYCODE_BUTTON_L2, lt);
        hyst(ch, KeyEvent.KEYCODE_BUTTON_R2, rt);
        hyst(ch, KeyEvent.KEYCODE_DPAD_LEFT, -hx);
        hyst(ch, KeyEvent.KEYCODE_DPAD_RIGHT, hx);
        hyst(ch, KeyEvent.KEYCODE_DPAD_UP, -hy);
        hyst(ch, KeyEvent.KEYCODE_DPAD_DOWN, hy);
    }

    // ------------------------------------------------------------ жесты: нажатие, двойное, долгое

    private static boolean isRepeatable(String a) {
        return "vol_up".equals(a) || "vol_down".equals(a) || "seek_fwd".equals(a) || "seek_back".equals(a);
    }

    private void perform(String a) {
        try {
            performer.perform(a);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Единая точка входа для кнопок. Нажатия одной кнопки из разных каналов склеиваются: действие
     * стартует с первого канала, а заканчивается, когда отпустил последний. true: событие поглотить.
     */
    private boolean route(int code, boolean down, int ch) {
        PadView v = view;
        if (isLearning()) {
            if (down && v != null) v.onPhysicalKey(code, ch);
            return true;
        }
        final String t = action(code, "t");
        final String d = action(code, "d");
        final String l = action(code, "l");
        if (isNone(t) && isNone(d) && isNone(l)) return false;
        boolean intercept = prefs.getBoolean("intercept", true);

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
            if (first) pressDown(s, t, d, l);
        } else {
            if ((s.mask & (1 << ch)) == 0) return intercept;
            s.mask &= ~(1 << ch);
            if (s.mask == 0) pressUp(s, t, d, l);
        }
        return intercept;
    }

    private void pressDown(final KeyState s, final String t, final String d, final String l) {
        s.down = true;
        s.longFired = false;
        s.repeating = false;
        boolean fast = isRepeatable(t) && isNone(d) && isNone(l);
        if (fast) {
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
            h.postDelayed(s.longR, prefs.getInt("long_ms", 500));
        }
    }

    private void pressUp(final KeyState s, final String t, final String d, final String l) {
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
        int windowMs = prefs.getInt("double_ms", 300);
        if (!isNone(d)) {
            if (s.tapR != null) {
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

    // ------------------------------------------------------------ названия и значки

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
        String n = KeyEvent.keyCodeToString(code);
        return n.startsWith("KEYCODE_") ? n.substring(8) : n;
    }

    private String actionLabel(String a) {
        if (isNone(a)) return "Не назначено";
        for (String[] x : actions) {
            if (x[0].equals(a)) return x[1];
        }
        return a;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
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
    static void drawIcon(Canvas c, String a, float cx, float cy, float s, int alpha) {
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
        } else {
            c.drawCircle(cx, cy, s * 0.4f, k);
        }
    }

    private static Bitmap iconBitmap(String a, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        drawIcon(new Canvas(b), a, px / 2f, px / 2f, px * 0.42f, 255);
        return b;
    }

    // ------------------------------------------------------------ интерфейс

    /**
     * Картинка геймпада с кнопками-значками, ниже блок выбора действий и строка состояния.
     * [hint] — необязательная строка в состоянии (например, «включи службу…»).
     */
    public View createView(Context context, String hint) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView status = new TextView(context);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(13f);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(26, 26, 26));
        panel.setVisibility(View.GONE);
        PadView pad = new PadView(context, panel, status, hint == null ? "" : hint);
        root.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(context, 8);
        root.addView(panel, pl);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private final class PadView extends View {
        private final LinearLayout panel;
        private final TextView status;
        private final String hint;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int hiCode = -1;
        private long hiUntil;
        private String lastNote = "Нажми кнопку на геймпаде или коснись её на картинке";
        private int lastCode = -1;
        private long lastCodeAt;

        PadView(Context context, LinearLayout panel, TextView status, String hint) {
            super(context);
            this.panel = panel;
            this.status = status;
            this.hint = hint;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setContentDescription("Картинка геймпада: коснись кнопки, чтобы выбрать для неё действия");
            refreshStatus();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            view = this;
            requestFocus();
        }

        @Override protected void onDetachedFromWindow() {
            if (view == this) view = null;
            super.onDetachedFromWindow();
        }

        private void refreshStatus() {
            String axes = android.os.Build.VERSION.SDK_INT >= 34 ? "доступны"
                    : "нужен Android 14+ (сейчас API " + android.os.Build.VERSION.SDK_INT + ")";
            status.setText((hint.length() > 0 ? hint + "\n" : "") + lastNote
                    + "\nСобытий: кнопки службы " + cnt[CH_SERVICE] + ", окно " + cnt[CH_WINDOW]
                    + ", оси службы " + cnt[CH_MOTION] + "\nСтики и курки через службу: " + axes);
        }

        void onPhysicalKey(final int code, final int ch) {
            post(new Runnable() {
                @Override public void run() {
                    long now = SystemClock.uptimeMillis();
                    if (code == lastCode && now - lastCodeAt < 500) return; // тот же нажим пришёл по другому каналу
                    lastCode = code;
                    lastCodeAt = now;
                    lastNote = "Нажато: " + slotName(code) + " (" + code + "), канал: " + CHN[ch];
                    refreshStatus();
                    if (slotFor(code) == null) addExtra(code);
                    hiCode = code;
                    hiUntil = SystemClock.uptimeMillis() + 1200;
                    invalidate();
                    showEditor(code);
                }
            });
        }

        // дополнительные кнопки (запасные, тыльные и т. п.): коды, которых нет на картинке, рисуются внизу
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

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (isPadEvent(event) && event.getRepeatCount() == 0) {
                cnt[CH_WINDOW]++;
                route(keyCode, true, CH_WINDOW);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (isPadEvent(event)) return true;
            return super.onKeyUp(keyCode, event);
        }

        @Override public boolean onGenericMotionEvent(MotionEvent e) {
            if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                feedMotion(e, CH_WINDOW);
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * 0.70f));
        }

        @Override protected void onDraw(Canvas c) {
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

                String t = action(code, "t");
                String d = action(code, "d");
                String l = action(code, "l");
                if (isNone(t) && sl[5] != 0) drawDirection(c, x, y, r, sl[5]);
                else drawIcon(c, t, x, y, r * 0.62f, 255);

                // метки: двойное нажатие слева (круг), долгое справа (квадрат); тёмная окантовка, чтобы читались на любом фоне
                if (!isNone(d)) drawMark(c, x - r * 0.6f, y + r * 0.95f, false);
                if (!isNone(l)) drawMark(c, x + r * 0.6f, y + r * 0.95f, true);
            }
            c.restore();
            if (hi) postInvalidateDelayed(Math.max(50, hiUntil - SystemClock.uptimeMillis() + 20));
        }

        private void drawMark(Canvas c, float x, float y, boolean square) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.BLACK);
            if (square) c.drawRect(x - 15, y - 15, x + 15, y + 15, fill);
            else c.drawCircle(x, y, 16, fill);
            fill.setColor(Color.WHITE);
            if (square) c.drawRect(x - 11, y - 11, x + 11, y + 11, fill);
            else c.drawCircle(x, y, 12, fill);
        }

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

        private int colorOf(int kind) {
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

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int[] sl = slotAt(e.getX(), e.getY());
                if (sl != null) {
                    performClick();
                    showEditor(sl[0]);
                }
            }
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        // ---------------------------------------------------------- блоки вместо всплывающих окон

        private TextView text(String s, float sp, int color, boolean bold) {
            TextView t = new TextView(getContext());
            t.setText(s);
            t.setTextSize(sp);
            t.setTextColor(color);
            if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            return t;
        }

        private void addSeparator(LinearLayout parent) {
            View sep = new View(getContext());
            sep.setBackgroundColor(Color.rgb(70, 70, 70));
            parent.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        private void addRow(LinearLayout parent, Bitmap bmp, String title, int titleColor, View.OnClickListener click) {
            Context ctx = getContext();
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            int pd = dp(ctx, 12);
            r.setPadding(pd, pd, pd, pd);
            r.setMinimumHeight(dp(ctx, 56));
            if (bmp != null) {
                ImageView iv = new ImageView(ctx);
                iv.setImageBitmap(bmp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36));
                lp.rightMargin = dp(ctx, 12);
                r.addView(iv, lp);
            }
            r.addView(text(title, 18f, titleColor, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (click != null) {
                r.setClickable(true);
                r.setFocusable(true);
                r.setOnClickListener(click);
            }
            parent.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addSeparator(parent);
        }

        private void panelStart(String title) {
            Context ctx = getContext();
            panel.removeAllViews();
            panel.setVisibility(View.VISIBLE);
            TextView t = text(title, 20f, Color.WHITE, true);
            t.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
            panel.addView(t);
            addSeparator(panel);
        }

        private void panelEnd() {
            panel.post(new Runnable() {
                @Override public void run() {
                    panel.requestRectangleOnScreen(new Rect(0, 0, panel.getWidth(),
                            Math.min(panel.getHeight(), dp(getContext(), 300))), false);
                }
            });
        }

        private void hidePanel() {
            panel.removeAllViews();
            panel.setVisibility(View.GONE);
        }

        private void showEditor(final int code) {
            final Context ctx = getContext();
            panelStart(slotName(code));
            for (int g = 0; g < 3; g++) {
                final int gi = g;
                String a = action(code, GK[g]);
                addRow(panel, iconBitmap(a, dp(ctx, 36)), GNAME[g] + ": " + actionLabel(a), Color.WHITE,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                showChooser(code, gi);
                            }
                        });
            }
            addRow(panel, null, "Закрыть", Color.LTGRAY, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hidePanel();
                }
            });
            panelEnd();
        }

        private void showChooser(final int code, final int g) {
            final Context ctx = getContext();
            panelStart(slotName(code) + ": " + GNAME[g]);
            String cur = action(code, GK[g]);
            addRow(panel, null, (isNone(cur) ? "✓ " : "") + "Не назначено", Color.WHITE, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    apply(code, g, "none");
                }
            });
            for (final String[] a : actions) {
                addRow(panel, iconBitmap(a[0], dp(ctx, 36)), (a[0].equals(cur) ? "✓ " : "") + a[1], Color.WHITE,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                apply(code, g, a[0]);
                            }
                        });
            }
            addRow(panel, null, "← Назад", Color.LTGRAY, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showEditor(code);
                }
            });
            panelEnd();
        }

        private void apply(int code, int g, String id) {
            prefs.edit().putString(key(code, GK[g]), id).apply();
            invalidate();
            showEditor(code);
        }
    }
}
