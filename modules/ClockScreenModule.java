package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ClockScreen — самостоятельная часовая заставка для телефона БЕЗ системной блокировки экрана.
 *
 * В отличие от ClockWallpaper не нужны ни окно-активити хоста, ни экран блокировки, ни картинка с
 * заранее размеченным циферблатом: часы рисуются самим модулем в окне службы специальных возможностей
 * поверх всего, что на экране.
 *
 *  - вид: аналоговые, цифровые или оба; дата, заряд, секунды (по умолчанию выключены, чтобы реже обновлять e-ink);
 *    белое на чёрном, чёрное на белом или своя картинка фона;
 *  - когда показывать: вручную (execute / «Показать сейчас»), по таймеру каждые 1–60 минут (экран включается),
 *    при перевороте экраном вниз, при подключении зарядки;
 *  - закрытие: касание экрана или автоматически через заданное время;
 *  - после автоматического закрытия экран можно выключить: администратором устройства (lockNow, без пин-кода
 *    это просто выключение), через Shizuku (input keyevent 223) или администратором, а если его нет — Shizuku.
 */
public class ClockScreenModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_ClockScreen";          // настройки, которые пишет хост
    private static final String ACTION_TIMER = "im.manus.clockscreen.TIMER";
    private static final String ADMIN_CLASS = "im.manus.universalhost.ModuleAdminReceiver";
    private static final int[] INTERVALS = {0, 1, 5, 10, 15, 30, 60};   // минут; 0 — выключено

    // Пороги переворота экраном вниз, как в ClockWallpaper
    private static final float FLIP_ON = -8.6f;
    private static final float FLIP_OFF = -7.0f;
    private static final int FLIP_HOLD = 500;
    private static final int FLIP_COOLDOWN = 8000;

    // настройки
    private int style = 2;              // 0 аналоговые, 1 цифровые, 2 оба
    private int scheme = 0;             // 0 белое на чёрном, 1 чёрное на белом, 2 картинка
    private boolean showDate = true;
    private boolean showBattery = true;
    private boolean showSeconds = false;
    private int sizePercent = 80;
    private int intervalIndex = 0;
    private boolean onFlip = false;
    private boolean onCharge = false;
    private int durationSec = 10;
    private int after = 0;              // 0 ничего, 1 админ, 2 Shizuku, 3 админ, иначе Shizuku
    private boolean vibrate = false;

    private AccessibilityService svc;
    private Handler handler;
    private SharedPreferences sp;
    private WindowManager wm;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private SensorManager sm;
    private PowerManager.WakeLock wakeLock;
    private Face face;
    private Bitmap background;
    private boolean active;
    private boolean receiversRegistered;
    private boolean sensorsRegistered;
    private boolean showing;
    private boolean autoOff;

    private float gz;
    private boolean gzInit;
    private boolean faceDown;
    private boolean flipFired = true;
    private long faceDownSince;
    private long lastFlipShow;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "ClockScreen"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Часы на весь экран без блокировки: по таймеру, при перевороте или зарядке, с выключением экрана";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        if (!(context instanceof AccessibilityService)) return;
        svc = (AccessibilityService) context;
        handler = new Handler(Looper.getMainLooper());
        sp = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        dpm = (DevicePolicyManager) svc.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(svc.getPackageName(), ADMIN_CLASS);
        loadConfig();
        active = true;
        showing = false;
        gzInit = false;
        flipFired = true;
        try {
            registerReceivers();
            if (onFlip) registerSensors();
            scheduleTimer();
        } catch (Throwable t) {
            toast("ClockScreen: ошибка запуска: " + t);
        }
    }

    @Override
    public void stop() {
        active = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (svc != null) {
            if (receiversRegistered) {
                try { svc.unregisterReceiver(timerReceiver); } catch (Throwable ignored) { }
                try { svc.unregisterReceiver(chargeReceiver); } catch (Throwable ignored) { }
                receiversRegistered = false;
            }
            if (sensorsRegistered && sm != null) {
                sm.unregisterListener(sensorListener);
                sensorsRegistered = false;
            }
            try {
                ((AlarmManager) svc.getSystemService(Context.ALARM_SERVICE)).cancel(timerIntent());
            } catch (Throwable ignored) { }
        }
        removeFace();
        releaseWakeLock();
        showing = false;
        background = null;
        svc = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    /** execute() или execute({"cmd":"show"}) — показать часы; {"cmd":"hide"} — убрать. */
    @Override
    public Object execute(Map<String, ?> data) {
        if (!active) return null;
        Object cmd = data == null ? null : data.get("cmd");
        if ("hide".equals(cmd)) hide(false); else show("manual");
        return null;
    }

    // ---------------------------------------------------------------- настройки (экран в хосте)

    private void loadConfig() {
        style = clamp(sp.getInt("style", 2), 0, 2);
        scheme = clamp(sp.getInt("scheme", 0), 0, 2);
        showDate = sp.getBoolean("show_date", true);
        showBattery = sp.getBoolean("show_battery", true);
        showSeconds = sp.getBoolean("show_seconds", false);
        sizePercent = clamp(sp.getInt("size_percent", 80), 40, 100);
        intervalIndex = clamp(sp.getInt("interval", 0), 0, INTERVALS.length - 1);
        onFlip = sp.getBoolean("on_flip", false);
        onCharge = sp.getBoolean("on_charge", false);
        durationSec = clamp(sp.getInt("duration", 10), 3, 300);
        after = clamp(sp.getInt("after", 0), 0, 3);
        vibrate = sp.getBoolean("vibrate", false);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Вид"));
        l.add(SettingItem.choice("style", "Часы", "", Arrays.asList("Аналоговые", "Цифровые", "Аналоговые и цифровые"), 2));
        l.add(SettingItem.choice("scheme", "Цвета", "", Arrays.asList("Белое на чёрном", "Чёрное на белом", "Своя картинка фона"), 0));
        l.add(SettingItem.image("bg_image", "Картинка фона", "Для цвета «Своя картинка фона»"));
        l.add(SettingItem.slider("size_percent", "Размер циферблата", "%", 40, 100, 5, 80));
        l.add(SettingItem.toggle("show_date", "Дата", "", true));
        l.add(SettingItem.toggle("show_battery", "Заряд батареи", "", true));
        l.add(SettingItem.toggle("show_seconds", "Секундная стрелка", "Чаще обновляет экран, для e-ink лучше выключить", false));
        l.add(SettingItem.section("Когда показывать"));
        l.add(SettingItem.choice("interval", "По таймеру", "",
                Arrays.asList("Выключено", "Каждую минуту", "Каждые 5 минут", "Каждые 10 минут",
                        "Каждые 15 минут", "Каждые 30 минут", "Каждый час"), 0));
        l.add(SettingItem.toggle("on_flip", "При перевороте экраном вниз", "Покажет часы и выключит экран", false));
        l.add(SettingItem.toggle("on_charge", "При подключении зарядки", "", false));
        l.add(SettingItem.toggle("vibrate", "Виброотклик при показе", "", false));
        l.add(SettingItem.section("Закрытие"));
        l.add(SettingItem.slider("duration", "Закрывать через", "с", 3, 300, 1, 10));
        l.add(SettingItem.choice("after", "После закрытия по времени", "",
                Arrays.asList("Ничего", "Выключить экран (админ устройства)", "Выключить экран (Shizuku)",
                        "Выключить экран (админ, иначе Shizuku)"), 0));
        l.add(SettingItem.section("Проверка"));
        l.add(SettingItem.action("show_now", "Показать сейчас", "Касание закрывает часы"));
        l.add(SettingItem.action("admin", "Администратор устройства", "Нужен только для выключения экрана"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (svc == null || !active) return;
        if ("show_now".equals(key)) { show("manual"); return; }
        if ("admin".equals(key)) { requestAdmin(); return; }
        Context c = svc;       // остальное меняет датчики, таймер и вид: проще перезапустить
        stop();
        init(c);
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
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(48 * s, 48 * s, 34 * s, p);
        c.drawLine(48 * s, 48 * s, 48 * s, 28 * s, p);
        c.drawLine(48 * s, 48 * s, 66 * s, 48 * s, p);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30);
            c.drawLine(48 * s + (float) Math.sin(a) * 28 * s, 48 * s - (float) Math.cos(a) * 28 * s,
                    48 * s + (float) Math.sin(a) * 33 * s, 48 * s - (float) Math.cos(a) * 33 * s, p);
        }
        return b;
    }

    // ---------------------------------------------------------------- служебное

    private void toast(final String text) {
        if (handler == null || svc == null) return;
        final Context c = svc;
        handler.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
            }
        });
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else svc.registerReceiver(r, f);
    }

    private void registerReceivers() {
        register(timerReceiver, new IntentFilter(ACTION_TIMER));
        register(chargeReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        receiversRegistered = true;
    }

    private void registerSensors() {
        sm = (SensorManager) svc.getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            sm.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_NORMAL);
            sensorsRegistered = true;
        }
    }

    private void requestAdmin() {
        if (dpm.isAdminActive(admin)) { toast("Администратор устройства уже включён"); return; }
        try {
            svc.startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Нужно только для выключения экрана после часов")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Не удалось открыть запрос администратора: " + t);
        }
    }

    private void buzz() {
        Vibrator v = (Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(120);
        }
    }

    // ---------------------------------------------------------------- таймер, зарядка, переворот

    private PendingIntent timerIntent() {
        return PendingIntent.getBroadcast(svc, 91, new Intent(ACTION_TIMER).setPackage(svc.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Следующее срабатывание — ближайшая граница, кратная интервалу (минуты от начала часа). */
    private void scheduleTimer() {
        int minutes = INTERVALS[intervalIndex];
        AlarmManager am = (AlarmManager) svc.getSystemService(Context.ALARM_SERVICE);
        if (minutes <= 0) { am.cancel(timerIntent()); return; }
        Calendar n = Calendar.getInstance();
        n.set(Calendar.SECOND, 0);
        n.set(Calendar.MILLISECOND, 0);
        if (minutes >= 60) {
            n.add(Calendar.HOUR_OF_DAY, 1);
            n.set(Calendar.MINUTE, 0);
        } else {
            int next = ((n.get(Calendar.MINUTE) / minutes) + 1) * minutes;
            if (next >= 60) {
                n.add(Calendar.HOUR_OF_DAY, 1);
                next = 0;
            }
            n.set(Calendar.MINUTE, next);
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.getTimeInMillis(), timerIntent());
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.getTimeInMillis(), timerIntent());
        }
    }

    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!active) return;
            show("timer");
            scheduleTimer();
        }
    };

    private final BroadcastReceiver chargeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (active && onCharge) show("charge");
        }
    };

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (!active || !onFlip || e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
            float x = e.values[0], y = e.values[1], z = e.values[2];
            if (!gzInit) { gz = z; gzInit = true; } else gz = gz * 0.7f + z * 0.3f;
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            boolean steady = mag > 8.3f && mag < 11.3f;
            faceDown = faceDown ? gz < FLIP_OFF : gz < FLIP_ON;
            long now = SystemClock.elapsedRealtime();
            boolean interactive = ((PowerManager) svc.getSystemService(Context.POWER_SERVICE)).isInteractive();
            if (!faceDown) {
                flipFired = false;
                faceDownSince = 0;
            } else if (!interactive) {
                flipFired = true;
                faceDownSince = 0;
            } else if (steady) {
                if (faceDownSince == 0) faceDownSince = now;
                if (!flipFired && now - faceDownSince >= FLIP_HOLD && now - lastFlipShow >= FLIP_COOLDOWN) {
                    flipFired = true;
                    lastFlipShow = now;
                    show("flip");
                }
            } else {
                faceDownSince = 0;
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
    };

    // ---------------------------------------------------------------- показ и закрытие

    private final Runnable hideTask = new Runnable() {
        @Override public void run() { hide(true); }
    };

    private final Runnable tickTask = new Runnable() {
        @Override public void run() {
            if (!showing || face == null) return;
            face.invalidate();
            scheduleTick();
        }
    };

    private void scheduleTick() {
        long now = System.currentTimeMillis();
        long step = showSeconds ? 1000L : 60000L;
        long delay = step - (now % step) + 20L;      // ровно на границу секунды/минуты
        handler.postDelayed(tickTask, delay);
    }

    private void show(String why) {
        if (!active || svc == null) return;
        handler.removeCallbacks(hideTask);
        if (showing) {                       // уже на экране: продлеваем показ
            handler.postDelayed(hideTask, durationSec * 1000L);
            return;
        }
        PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
        if (!pm.isInteractive()) {
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | PowerManager.ON_AFTER_RELEASE, "ClockScreen:Show");
            wakeLock.acquire((durationSec + 3) * 1000L);
        }
        if (vibrate) buzz();
        autoOff = !"manual".equals(why);
        if (scheme == 2) loadBackground();
        face = new Face(svc);
        face.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hide(false); }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(face, lp);
        } catch (Throwable t) {
            face = null;
            releaseWakeLock();
            toast("ClockScreen: не удалось показать часы: " + t);
            return;
        }
        showing = true;
        handler.postDelayed(hideTask, durationSec * 1000L);
        scheduleTick();
    }

    /** byTimeout — закрытие по времени (тогда выключаем экран, если так настроено); касание экрана экран не выключает. */
    private void hide(boolean byTimeout) {
        if (handler != null) {
            handler.removeCallbacks(hideTask);
            handler.removeCallbacks(tickTask);
        }
        boolean wasShowing = showing;
        removeFace();
        showing = false;
        releaseWakeLock();
        if (wasShowing && byTimeout && autoOff) screenOff();
    }

    private void removeFace() {
        if (face != null && wm != null) {
            try { wm.removeView(face); } catch (Throwable ignored) { }
        }
        face = null;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) { }
        }
        wakeLock = null;
    }

    private void screenOff() {
        if (after == 0 || svc == null) return;
        boolean done = false;
        if ((after == 1 || after == 3) && dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow();
                done = true;
            } catch (Throwable ignored) { }
        }
        if (!done && (after == 2 || after == 3)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    ShizukuResult_ignore(ShizukuBridge.exec("input keyevent 223"));     // KEYCODE_SLEEP
                }
            }).start();
            done = true;
        }
        if (!done) toast("ClockScreen: экран не выключен — включите администратора устройства или Shizuku");
    }

    private static void ShizukuResult_ignore(Object result) { }

    // ---------------------------------------------------------------- картинка фона

    private void loadBackground() {
        background = null;
        String path = sp.getString("bg_image", null);
        if (path == null) return;
        try {
            android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            int target = Math.max(dm.widthPixels, dm.heightPixels);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            background = BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            background = null;
        }
    }

    // ---------------------------------------------------------------- часы

    private final class Face extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private final int fg;
        private final int bg;
        private int level = 100;
        private boolean charging;
        private BroadcastReceiver batteryReceiver;

        Face(Context c) {
            super(c);
            fg = scheme == 1 ? Color.BLACK : Color.WHITE;
            bg = scheme == 1 ? Color.WHITE : Color.BLACK;
            p.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!showBattery) return;
            batteryReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    level = i.getIntExtra("level", 100);
                    int status = i.getIntExtra("status", 0);
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                    invalidate();
                }
            };
            Intent sticky;
            if (Build.VERSION.SDK_INT >= 33) {
                sticky = svc.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            } else {
                sticky = svc.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            if (sticky != null) batteryReceiver.onReceive(svc, sticky);
        }

        @Override protected void onDetachedFromWindow() {
            if (batteryReceiver != null) {
                try { svc.unregisterReceiver(batteryReceiver); } catch (Throwable ignored) { }
                batteryReceiver = null;
            }
            super.onDetachedFromWindow();
        }

        private void line(Canvas c, float cx, float cy, float angleDeg, float from, float to, float width) {
            double a = Math.toRadians(angleDeg);
            float sx = (float) Math.sin(a), sy = -(float) Math.cos(a);
            p.setStrokeWidth(width);
            c.drawLine(cx + sx * from, cy + sy * from, cx + sx * to, cy + sy * to, p);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawColor(bg);
            if (scheme == 2 && background != null) {
                // «центр с обрезкой»: картинка закрывает весь экран
                float k = Math.max(w / background.getWidth(), h / background.getHeight());
                int sw = (int) (w / k), sh = (int) (h / k);
                int sx = (background.getWidth() - sw) / 2, sy = (background.getHeight() - sh) / 2;
                src.set(sx, sy, sx + sw, sy + sh);
                dst.set(0, 0, (int) w, (int) h);
                c.drawBitmap(background, src, dst, p);
            }
            p.setColor(fg);
            if (scheme == 2) p.setShadowLayer(6, 1, 1, Color.BLACK); else p.clearShadowLayer();

            Calendar now = Calendar.getInstance();
            boolean analog = style != 1;
            boolean digital = style != 0;
            float side = Math.min(w, h);
            float cx = w / 2f;
            float cy = (analog && digital) ? h * 0.38f : h * 0.5f;
            float r = side * sizePercent / 100f / 2f;

            if (analog) {
                p.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 60; i++) {
                    boolean big = i % 5 == 0;
                    line(c, cx, cy, i * 6f, r * (big ? 0.86f : 0.94f), r, big ? r * 0.03f : r * 0.012f);
                }
                float hour = ((now.get(Calendar.HOUR_OF_DAY) % 12) + now.get(Calendar.MINUTE) / 60f) / 12f * 360f;
                float minute = (now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f) / 60f * 360f;
                line(c, cx, cy, hour, 0, r * 0.5f, r * 0.05f);
                line(c, cx, cy, minute, 0, r * 0.78f, r * 0.032f);
                if (showSeconds) line(c, cx, cy, now.get(Calendar.SECOND) * 6f, -r * 0.1f, r * 0.88f, r * 0.012f);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r * 0.05f, p);
            }

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            float timeSize = side * (analog ? 0.13f : 0.24f);
            float textY;
            if (digital) {
                boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
                String pattern = showSeconds ? (h24 ? "HH:mm:ss" : "h:mm:ss") : (h24 ? "HH:mm" : "h:mm");
                p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                p.setTextSize(timeSize);
                textY = analog ? cy + r + timeSize * 1.15f : cy + timeSize * 0.35f;
                c.drawText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()), cx, textY, p);
            } else {
                textY = cy + r;
            }
            if (showDate) {
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                p.setTextSize(side * 0.045f);
                float dateY = digital ? textY + side * 0.075f : cy + r + side * 0.08f;
                c.drawText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()), cx, dateY, p);
            }
            if (showBattery) {
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                p.setTextSize(side * 0.04f);
                c.drawText(level + "%" + (charging ? " \u26A1" : ""), cx, h * 0.05f + side * 0.04f, p);
            }
            p.clearShadowLayer();
        }
    }
}
