package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import im.manus.universalhost.IMainViewProvider;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flashlight — фонарик с регулировкой яркости.
 *
 * Яркость меняется штатным API Android 13+ (CameraManager.turnOnTorchWithStrengthLevel): число уровней
 * задаёт сама вспышка телефона. Если система или вспышка уровней не поддерживает (Android 12 и ниже,
 * вспышка с одним уровнем), фонарик работает как обычный: включён / выключен, ползунок неактивен.
 * Разрешение на камеру не нужно: фонарик включается через setTorchMode.
 *
 * Управление:
 *  - главное окно модуля (IMainViewProvider): большая кнопка-лампа и ползунок яркости, без текста;
 *  - экран настроек хоста: переключатель, яркость, автовыключение;
 *  - execute({"command": ...}) для других модулей и кнопок геймпада:
 *    "toggle", "on", "off", "brighter", "dimmer", "level:<1..100>", "status".
 */
public class FlashlightModule implements IPlugin, ISettingsProvider, IMainViewProvider {

    private static final String PREFS = "module_Flashlight";
    private static final int STEP_PERCENT = 10;            // шаг команд brighter / dimmer

    private Context ctx;
    private SharedPreferences cfg;
    private CameraManager cm;
    private String camId;
    private Handler main;
    private boolean callbackRegistered;

    /** -1 — ещё не знаем, 1 — яркость не регулируется, больше 1 — число уровней вспышки. */
    private int maxLevel = -1;
    private boolean on;
    private int percent = 100;
    private long lastApplyMs;
    private Runnable uiRefresh;

    @Override public String getName() { return "Flashlight"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() { return "Фонарик с регулировкой яркости"; }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        prepare(context);
    }

    @Override
    public void stop() {
        if (main != null) main.removeCallbacksAndMessages(null);
        try {
            if (cm != null && callbackRegistered) cm.unregisterTorchCallback(torchCb);
        } catch (Throwable ignored) { }
        try {
            if (cm != null && camId != null && on) cm.setTorchMode(camId, false);
        } catch (Throwable ignored) { }
        if (cfg != null) cfg.edit().putBoolean("torch_on", false).apply();
        callbackRegistered = false;
        cm = null;
        camId = null;
        on = false;
        uiRefresh = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    // ---------------------------------------------------------------- управление

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Находит вспышку, читает число уровней яркости и подписывается на состояние фонарика. */
    private synchronized boolean prepare(Context c) {
        if (cm != null && camId != null) return true;
        try {
            Context app = c.getApplicationContext();
            ctx = app != null ? app : c;
            main = new Handler(Looper.getMainLooper());
            cfg = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            percent = clamp(cfg.getInt("torch_level", 100), 1, 100);
            cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            camId = findTorchCamera();
            if (camId == null) { cm = null; return false; }
            cfg.edit().putInt("torch_max", maxLevel).apply();
            cm.registerTorchCallback(torchCb, main);   // сразу сообщит текущее состояние
            callbackRegistered = true;
            return true;
        } catch (Throwable t) {
            cm = null;
            camId = null;
            return false;
        }
    }

    private String findTorchCamera() throws Exception {
        String best = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash == null || !flash.booleanValue()) continue;
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            boolean back = facing != null && facing.intValue() == CameraCharacteristics.LENS_FACING_BACK;
            if (best == null || back) {
                best = id;
                int mx = 1;
                if (Build.VERSION.SDK_INT >= 33) {
                    Integer m = ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                    if (m != null && m.intValue() > 1) mx = m.intValue();
                }
                maxLevel = mx;
            }
            if (back) break;
        }
        return best;
    }

    private final CameraManager.TorchCallback torchCb = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (!id.equals(camId)) return;
            on = enabled;
            if (cfg != null) cfg.edit().putBoolean("torch_on", enabled).apply();
            scheduleAutoOff();
            refreshUi();
        }

        @Override public void onTorchModeUnavailable(String id) {
            if (!id.equals(camId)) return;
            on = false;
            if (cfg != null) cfg.edit().putBoolean("torch_on", false).apply();
            refreshUi();
        }
    };

    /** Уровень вспышки (1..max) для яркости в процентах. */
    private int levelFor(int pct) {
        return clamp(Math.round(pct * maxLevel / 100f), 1, maxLevel);
    }

    /** Включить (с текущей яркостью) или выключить. Повторный вызов с true применяет новую яркость. */
    private void setTorch(boolean want) {
        if (cm == null || camId == null) return;
        try {
            if (want) {
                if (Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
                    cm.turnOnTorchWithStrengthLevel(camId, levelFor(percent));
                } else {
                    cm.setTorchMode(camId, true);
                }
            } else {
                cm.setTorchMode(camId, false);
            }
            on = want;
            if (cfg != null) cfg.edit().putBoolean("torch_on", want).apply();
        } catch (Throwable t) {
            on = false;
            if (cfg != null) cfg.edit().putBoolean("torch_on", false).apply();
        }
        scheduleAutoOff();
        refreshUi();
    }

    private void setPercent(int pct) {
        percent = clamp(pct, 1, 100);
        if (cfg != null) cfg.edit().putInt("torch_level", percent).apply();
    }

    private final Runnable offTask = new Runnable() {
        @Override public void run() { setTorch(false); }
    };

    private void scheduleAutoOff() {
        if (main == null || cfg == null) return;
        main.removeCallbacks(offTask);
        int minutes = cfg.getInt("torch_timeout", 0);
        if (on && minutes > 0) main.postDelayed(offTask, minutes * 60000L);
    }

    private void refreshUi() {
        Runnable r = uiRefresh;
        if (r != null && main != null) main.post(r);
    }

    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object c = data.get("command");
        if (!(c instanceof String)) return null;
        if (ctx == null || !prepare(ctx)) return null;
        String cmd = (String) c;
        if ("toggle".equals(cmd)) { setTorch(!on); return Boolean.valueOf(on); }
        if ("on".equals(cmd)) { setTorch(true); return Boolean.valueOf(on); }
        if ("off".equals(cmd)) { setTorch(false); return Boolean.valueOf(on); }
        if ("status".equals(cmd)) return Boolean.valueOf(on);
        if ("brighter".equals(cmd) || "dimmer".equals(cmd)) {
            setPercent(percent + ("brighter".equals(cmd) ? STEP_PERCENT : -STEP_PERCENT));
            if (on) setTorch(true);
            refreshUi();
            return Integer.valueOf(percent);
        }
        if (cmd.startsWith("level:")) {
            try {
                setPercent(Integer.parseInt(cmd.substring(6).trim()));
                if (on) setTorch(true);
                refreshUi();
                return Integer.valueOf(percent);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        String levelUnit = maxLevel == 1 ? "% (на этом телефоне не регулируется)" : "%";
        l.add(SettingItem.section("🔦 Фонарик"));
        l.add(SettingItem.toggle("torch_on", "💡 Включён", "", false));
        l.add(SettingItem.slider("torch_level", "🔆 Яркость", levelUnit, 1, 100, 1, 100));
        l.add(SettingItem.slider("torch_timeout", "⏱ Автовыключение", "мин (0 — не выключать)", 0, 60, 1, 0));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (ctx == null || !prepare(ctx)) return;
        if ("torch_on".equals(key)) {
            setTorch(cfg.getBoolean("torch_on", false));
        } else if ("torch_level".equals(key)) {
            percent = clamp(cfg.getInt("torch_level", 100), 1, 100);
            if (on) setTorch(true);
            refreshUi();
        } else if ("torch_timeout".equals(key)) {
            scheduleAutoOff();
        }
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = size / 96f;
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f * s);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path head = new Path();                                   // рефлектор
        head.moveTo(32 * s, 30 * s); head.lineTo(64 * s, 30 * s);
        head.lineTo(56 * s, 48 * s); head.lineTo(40 * s, 48 * s); head.close();
        c.drawPath(head, p);
        c.drawRoundRect(new RectF(40 * s, 48 * s, 56 * s, 84 * s), 4 * s, 4 * s, p);   // корпус
        c.drawLine(48 * s, 8 * s, 48 * s, 19 * s, p);             // лучи
        c.drawLine(28 * s, 14 * s, 34 * s, 22 * s, p);
        c.drawLine(68 * s, 14 * s, 62 * s, 22 * s, p);
        return b;
    }

    // ---------------------------------------------------------------- главное окно

    @Override
    public View createMainView(Context context) {
        prepare(context);
        final float d = context.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (24 * d), (int) (32 * d), (int) (24 * d), (int) (32 * d));

        final Bulb bulb = new Bulb(context);
        bulb.setClickable(true);
        bulb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTorch(!on); }
        });
        root.addView(bulb, new LinearLayout.LayoutParams((int) (220 * d), (int) (220 * d)));

        final TextView digits = new TextView(context);
        digits.setTextColor(Color.WHITE);
        digits.setTextSize(44);
        digits.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        digits.setGravity(Gravity.CENTER);
        digits.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = (int) (16 * d);
        root.addView(digits, dlp);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new Dot(context, (int) (10 * d)));
        final SeekBar bar = new SeekBar(context);
        bar.setMax(99);
        bar.setContentDescription("Яркость");
        try {
            bar.getProgressDrawable().setTint(Color.WHITE);
            bar.getThumb().setTint(Color.WHITE);
        } catch (Throwable ignored) { }
        row.addView(bar, new LinearLayout.LayoutParams(0, (int) (48 * d), 1f));
        row.addView(new Dot(context, (int) (24 * d)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = (int) (28 * d);
        root.addView(row, rlp);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                setPercent(progress + 1);
                digits.setText(String.valueOf(percent));
                bulb.setState(on, percent);
                long now = System.currentTimeMillis();
                if (on && now - lastApplyMs > 150) {     // не чаще, чем раз в 150 мс
                    lastApplyMs = now;
                    setTorch(true);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { if (on) setTorch(true); }
        });

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                bulb.setState(on, percent);
                digits.setText(String.valueOf(percent));
                bar.setProgress(percent - 1);
                boolean adjustable = maxLevel != 1;
                bar.setEnabled(adjustable);
                bar.setAlpha(adjustable ? 1f : 0.3f);
                String desc;
                if (camId == null) desc = "Фонарик недоступен";
                else if (on) desc = "Фонарик включён, яркость " + percent + " процентов";
                else desc = "Фонарик выключен";
                bulb.setContentDescription(desc);
            }
        };
        uiRefresh = refresh;
        refresh.run();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { uiRefresh = refresh; }
            @Override public void onViewDetachedFromWindow(View v) { if (uiRefresh == refresh) uiRefresh = null; }
        });
        return root;
    }

    /** Большая кнопка-лампа: в выключенном виде тёмная, во включённом светится тем сильнее, чем выше яркость. */
    private static final class Bulb extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean lit;
        private int pct = 100;

        Bulb(Context c) { super(c); }

        void setState(boolean lit, int pct) {
            this.lit = lit;
            this.pct = pct;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float half = Math.min(w, h) / 2f;
            float r = half * 0.64f;
            if (lit) {
                int a = 50 + (int) (170 * pct / 100f);
                p.setStyle(Paint.Style.FILL);
                p.setShader(new RadialGradient(cx, cy, half,
                        new int[] { Color.argb(a, 255, 236, 160), Color.TRANSPARENT },
                        new float[] { 0.45f, 1f }, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, half, p);
                p.setShader(null);
                p.setColor(Color.rgb(255, 240, 170));
                c.drawCircle(cx, cy, r, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF262626);
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(half * 0.03f);
                p.setColor(0xFF555555);
                c.drawCircle(cx, cy, r, p);
            }
            // значок питания: дуга с разрывом сверху и черта
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(lit ? 0xFF3A2E00 : 0xFFAAAAAA);
            RectF rf = new RectF(cx - r * 0.45f, cy - r * 0.45f, cx + r * 0.45f, cy + r * 0.45f);
            c.drawArc(rf, -60f, 300f, false, p);
            c.drawLine(cx, cy - r * 0.55f, cx, cy - r * 0.05f, p);
        }
    }

    /** Кружок у края ползунка: маленький — тусклее, большой — ярче. */
    private static final class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        Dot(Context c, int size) {
            super(c);
            this.size = size;
            p.setColor(Color.WHITE);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(size, size);
        }

        @Override protected void onDraw(Canvas c) {
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        }
    }
}
