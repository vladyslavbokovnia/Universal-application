package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import android.graphics.Bitmap;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Clock Wallpaper adapted as a Universal Host DEX module.
 * It draws its clock directly as an overlay and monitors accelerometer/proximity sensors
 * while the host accessibility service is running.
 */
public class ClockWallpaperModule implements IPlugin, ISettingsProvider, SensorEventListener {
    private static final String PREFS = "module_ClockWallpaper";
    private static final float FLIP_ON = -8.6f;
    private static final float FLIP_OFF = -7.0f;
    private static final long FLIP_HOLD_MS = 500L;
    private static final long FLIP_COOLDOWN_MS = 8000L;

    private Context app;
    private AccessibilityService accessibility;
    private android.content.SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor accelerometer;
    private Sensor proximity;
    private WindowManager windowManager;
    private ClockView clockView;
    private WindowManager.LayoutParams overlayParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean near;
    private boolean faceDown;
    private boolean flipFired;
    private long faceDownSince;
    private long lastFlipWake;
    private long lastTimerMinute = -1;
    private int battery = 100;
    private boolean charging;
    private boolean plugged;
    private boolean overlayVisible;
    private boolean lockedByModule;
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver systemReceiver;

    @Override public String getName() { return "ClockWallpaper"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Часы поверх экрана, переворот, датчики, таймер, зарядка и настройки";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        if (started) stop();
        app = context.getApplicationContext();
        accessibility = context instanceof AccessibilityService ? (AccessibilityService) context : null;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        sensors = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        proximity = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        started = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        systemReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    battery = Math.max(0, Math.min(100, i.getIntExtra("level", 100)));
                    int status = i.getIntExtra("status", 0);
                    charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                            || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                    plugged = i.getIntExtra("plugged", 0) != 0;
                    invalidateClock();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    lockedByModule = false;
                    hideOverlay();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    lockedByModule = false;
                } else if (Intent.ACTION_POWER_CONNECTED.equals(action)
                        || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                    plugged = Intent.ACTION_POWER_CONNECTED.equals(action);
                }
            }
        };
        try { app.registerReceiver(systemReceiver, filter); } catch (Throwable ignored) { }

        if (accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        if (proximity != null) sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
        handler.removeCallbacks(tick);
        handler.post(tick);

        // The first launch shows the clock only if the user has already granted overlay access.
        if (prefs.getBoolean("showOnStart", false)) showOverlay(false);
    }

    @Override public void stop() {
        started = false;
        handler.removeCallbacksAndMessages(null);
        if (sensors != null) {
            try { sensors.unregisterListener(this); } catch (Throwable ignored) { }
        }
        if (systemReceiver != null && app != null) {
            try { app.unregisterReceiver(systemReceiver); } catch (Throwable ignored) { }
        }
        systemReceiver = null;
        hideOverlay();
        releaseWakeLock();
        accessibility = null;
        app = null;
        prefs = null;
    }

    @Override public Object execute(Map<String, ?> data) {
        if (data == null) return status();
        Object action = data.get("action");
        String command = action == null ? "" : String.valueOf(action);
        if ("show".equals(command) || "test".equals(command)) {
            showOverlay(true);
            return status();
        }
        if ("hide".equals(command)) {
            hideOverlay();
            return status();
        }
        if ("status".equals(command)) return status();
        if ("permission".equals(command)) {
            openOverlaySettings();
            return "Откройте разрешение «Поверх других приложений», затем вернитесь в Universal-application.";
        }
        if ("start".equals(command)) {
            if (!started && app != null) init(app);
            showOverlay(true);
            return status();
        }
        return status();
    }

    private String status() {
        return "Clock Wallpaper: " + (started ? "активен" : "остановлен")
                + "; датчик наклона=" + (accelerometer != null)
                + "; датчик приближения=" + (proximity != null)
                + "; overlay=" + overlayVisible + "; заряд=" + battery + "%";
    }

    @Override public List<SettingItem> getSettingsSchema() {
        List<SettingItem> s = new ArrayList<>();
        s.add(SettingItem.section("Часы и заставка"));
        s.add(SettingItem.toggle("showOnStart", "Показывать часы при запуске модуля",
                "Автоматически показывать циферблат после запуска службы специальных возможностей", false));
        s.add(SettingItem.toggle("flip", "Показывать при перевороте экраном вниз",
                "Удерживайте положение экраном вниз около половины секунды", true));
        s.add(SettingItem.toggle("proximity", "Не срабатывать рядом с объектом",
                "Использовать датчик приближения как защиту от случайного включения", true));
        s.add(SettingItem.toggle("faceDown", "Не включать, если телефон уже лежит экраном вниз",
                "Не запускать часы, если положение было таким до выключения экрана", true));
        s.add(SettingItem.toggle("wallpaperUpsideDown", "Перевёрнутый циферблат", "Развернуть часы на 180°", false));
        s.add(SettingItem.toggle("normalOrientation", "Всегда нормальная ориентация",
                "Отключает переворот циферблата", false));
        s.add(SettingItem.section("Пробуждение и питание"));
        s.add(SettingItem.toggle("enabled", "Разрешить пробуждение экрана", "Общий переключатель датчиков и таймера", true));
        s.add(SettingItem.toggle("timer", "Показывать часы каждые пять минут",
                "Проверка работает, пока Universal-application и служба специальных возможностей активны", true));
        s.add(SettingItem.toggle("plugProximity", "Игнорировать события зарядки",
                "Не запускать часы из-за подключения или отключения питания", true));
        s.add(SettingItem.toggle("vibrateOnWake", "Виброотклик при перевороте", "Короткая вибрация при срабатывании", true));
        s.add(SettingItem.toggle("musicAlarm", "Будильник: возобновлять воспроизведение",
                "При совпадении времени отправить системную команду Play", false));
        s.add(SettingItem.text("musicAlarmTime", "Время будильника", "Формат 08:00, локальное время", "08:00"));
        s.add(SettingItem.section("Управление"));
        s.add(SettingItem.action("showNow", "Показать часы сейчас", "Открыть циферблат поверх приложений"));
        s.add(SettingItem.action("overlayPermission", "Разрешение поверх приложений", "Открыть системные настройки разрешения"));
        s.add(SettingItem.action("hideNow", "Скрыть часы", "Убрать наложение с экрана"));
        return s;
    }

    @Override public void onSettingChanged(String key) {
        if ("showNow".equals(key)) showOverlay(true);
        else if ("hideNow".equals(key)) hideOverlay();
        else if ("overlayPermission".equals(key)) openOverlaySettings();
        else if ("showOnStart".equals(key) && prefs != null && prefs.getBoolean(key, false)) showOverlay(false);
        invalidateClock();
    }

    @Override public Bitmap createIcon(int sizePx) { return null; }

    private void openOverlaySettings() {
        if (app == null) return;
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 23) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + app.getPackageName()));
            } else i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Throwable ignored) { }
    }

    private boolean canDrawOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
    }

    private void showOverlay(boolean wake) {
        if (!started || app == null || windowManager == null) return;
        if (!canDrawOverlay()) {
            openOverlaySettings();
            return;
        }
        if (overlayVisible) {
            invalidateClock();
            if (wake) acquireWakeLock();
            return;
        }
        clockView = new ClockView(app);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(clockView, overlayParams);
            overlayVisible = true;
            if (wake) acquireWakeLock();
        } catch (Throwable t) {
            clockView = null;
            overlayVisible = false;
        }
    }

    private void hideOverlay() {
        if (overlayVisible && clockView != null && windowManager != null) {
            try { windowManager.removeView(clockView); } catch (Throwable ignored) { }
        }
        clockView = null;
        overlayVisible = false;
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (app == null) return;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "UniversalApplication:ClockWallpaper");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(2500L);
        } catch (Throwable ignored) { }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) { }
        wakeLock = null;
    }

    private void invalidateClock() {
        if (clockView != null) clockView.postInvalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started || app == null) return;
            invalidateClock();
            Calendar c = Calendar.getInstance();
            int minute = c.get(Calendar.MINUTE);
            long minuteStamp = System.currentTimeMillis() / 60000L;
            boolean interactive = true;
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                interactive = pm == null || pm.isInteractive();
            } catch (Throwable ignored) { }
            if (prefs.getBoolean("enabled", true) && !interactive && prefs.getBoolean("timer", true)
                    && minute % 5 == 0 && minuteStamp != lastTimerMinute) {
                lastTimerMinute = minuteStamp;
                if (!(plugged && prefs.getBoolean("plugProximity", true))) showOverlay(true);
            }
            if (prefs.getBoolean("musicAlarm", false)) {
                String alarm = prefs.getString("musicAlarmTime", "08:00");
                String now = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                if (now.equals(alarm) && minuteStamp != lastAlarmMinute) {
                    lastAlarmMinute = minuteStamp;
                    sendPlayKey();
                }
            }
            handler.postDelayed(this, 15000L);
        }
    };
    private long lastAlarmMinute = -1;

    private void sendPlayKey() {
        try {
            Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
            down.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
            app.sendBroadcast(down);
            Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
            up.putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
            app.sendBroadcast(up);
        } catch (Throwable ignored) { }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (!started || e == null) return;
        if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            near = e.values[0] < e.sensor.getMaximumRange();
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = e.values[0], y = e.values[1], z = e.values[2];
        float magnitude = (float) Math.sqrt(x*x + y*y + z*z);
        boolean steady = magnitude > 8.3f && magnitude < 11.3f;
        faceDown = faceDown ? z < FLIP_OFF : z < FLIP_ON;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean interactive = true;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            interactive = pm == null || pm.isInteractive();
        } catch (Throwable ignored) { }
        if (!faceDown) {
            flipFired = false;
            faceDownSince = 0;
        } else if (!interactive) {
            flipFired = true;
            faceDownSince = 0;
        } else if (steady) {
            if (faceDownSince == 0) faceDownSince = now;
            if (!flipFired && now - faceDownSince >= FLIP_HOLD_MS
                    && prefs.getBoolean("flip", true)
                    && prefs.getBoolean("enabled", true)
                    && now - lastFlipWake >= FLIP_COOLDOWN_MS
                    && !(near && prefs.getBoolean("proximity", true))
                    && !(plugged && prefs.getBoolean("plugProximity", true))) {
                flipFired = true;
                lastFlipWake = now;
                if (prefs.getBoolean("vibrateOnWake", true)) {
                    try {
                        android.os.Vibrator v = (android.os.Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                        if (v != null && v.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(120,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                            else v.vibrate(120);
                        }
                    } catch (Throwable ignored) { }
                }
                showOverlay(true);
            }
        } else faceDownSince = 0;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        accessibility = service;
    }

    private final class ClockView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        ClockView(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE, null); setClickable(true); }
        private void hand(Canvas c, float cx, float cy, float length, float angle, float width, int color) {
            double a = Math.toRadians(angle - 90);
            float x = cx + (float)Math.cos(a) * length;
            float y = cy + (float)Math.sin(a) * length;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(width + 8); p.setColor(Color.argb(180, 20, 5, 0));
            c.drawLine(cx, cy, x, y, p);
            p.setStrokeWidth(width); p.setColor(color); c.drawLine(cx, cy, x, y, p);
            p.setStyle(Paint.Style.FILL); c.drawCircle(x, y, width * 1.1f, p);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float cx = w * 0.53f, cy = h * 0.46f;
            Calendar n = Calendar.getInstance();
            int gold = Color.rgb(255,218,86), light = Color.rgb(255,240,150);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(245, 10, 8, 12));
            c.drawColor(Color.BLACK);
            p.setColor(Color.argb(220, 20, 13, 10));
            c.drawCircle(cx, cy, s * .47f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, s * .003f)); p.setColor(gold);
            c.drawCircle(cx, cy, s * .45f, p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(Math.max(18, s * .045f)); p.setColor(gold);
            for (int i=1; i<=12; i++) {
                double a = Math.toRadians(i*30 - 90);
                float x = cx + (float)Math.cos(a)*s*.39f;
                float y = cy + (float)Math.sin(a)*s*.39f;
                c.drawText(String.valueOf(i), x, y - (p.ascent()+p.descent())/2, p);
            }
            float hour = ((n.get(Calendar.HOUR_OF_DAY)%12) + n.get(Calendar.MINUTE)/60f)/12f*360f;
            float min = (n.get(Calendar.MINUTE) + n.get(Calendar.SECOND)/60f)/60f*360f;
            float sec = n.get(Calendar.SECOND)/60f*360f;
            hand(c,cx,cy,s*.24f,hour,Math.max(5,s*.012f),gold);
            hand(c,cx,cy,s*.34f,min,Math.max(3,s*.007f),light);
            hand(c,cx,cy,s*.37f,sec,Math.max(1,s*.002f),Color.rgb(210,80,45));
            p.setStyle(Paint.Style.FILL); p.setColor(gold); c.drawCircle(cx,cy,Math.max(4,s*.012f),p);

            float topX = w*.5f, topY = h*.12f;
            float battAngle = 360f - (battery/100f)*360f;
            hand(c,topX,topY,s*.10f,battAngle,Math.max(2,s*.004f),gold);
            p.setStyle(Paint.Style.FILL); p.setTextSize(Math.max(14,s*.028f)); p.setColor(gold);
            c.drawText(battery + "% " + (charging ? "⚡" : ""), topX, h*.22f, p);
            p.setTextSize(Math.max(15,s*.035f));
            String date = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date());
            c.drawText(date, w*.5f, h*.83f, p);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            p.setTextSize(Math.max(18,s*.05f)); c.drawText(time, w*.5f, h*.90f, p);
            p.setTextSize(Math.max(12,s*.025f));
            c.drawText("Неделя " + n.get(Calendar.WEEK_OF_YEAR) + " · " + (n.get(Calendar.DAY_OF_WEEK)-1), w*.5f, h*.95f, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { downX=e.getX(); downY=e.getY(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx=e.getX()-downX, dy=e.getY()-downY;
                if (Math.abs(dx)>80 || Math.abs(dy)>80) hideOverlay();
                else hideOverlay();
                return true;
            }
            return true;
        }
    }
}
