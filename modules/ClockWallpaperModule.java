package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
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
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Заставка ClockWallpaper как DEX-модуль (порт приложения github.com/vladyslavbokovnia/clock для электронной бумаги).
 *
 * Те же возможности, что у ClockService/WakeActivity/ClockAccessibilityService/MusicAlarmReceiver:
 *  - точный будильник каждые 5 минут: экран включается, показывается заставка, через секунду экран гасится
 *    (DevicePolicyManager.lockNow — нужен администратор устройства хоста), картинка остаётся на e-ink;
 *  - переворот экраном вниз (пороги -8.6 / -7.0 м/с², удержание 500 мс, защита 8 с), виброотклик;
 *  - датчик приближения и «экран вниз» подавляют таймерные пробуждения;
 *  - экран, включившийся от подключения/отключения зарядки, сразу гасится; виброотклик только на включение экрана пользователем;
 *  - когда после lockNow экран включает пользователь, заставка закрывается и показывается предыдущее приложение;
 *  - прозрачное наложение поверх системных панелей, перехватывающее касания, пока заставка на экране;
 *  - переход на рабочий стол после загрузки (в модуле — при первом запуске модуля в первые минуты после загрузки);
 *  - ежедневный «музыкальный будильник»: кнопка Play активному плееру;
 *  - стрелки (часы, минуты, день недели, заряд, месяц, число) рисуются поверх картинки циферблата.
 *
 * Различия с приложением clock: модуль живёт внутри службы специальных возможностей хоста (отдельного
 * foreground-сервиса и BOOT_COMPLETED у него нет), картинку циферблата нужно выбрать в настройках
 * (clock_wallpaper.png из репозитория clock), окно заставки — ModuleWakeActivity хоста.
 */
public class ClockWallpaperModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_ClockWallpaper";          // настройки, которые пишет хост
    private static final String STATE = "clock_wallpaper_state";          // служебное состояние и диагностика
    private static final String ACTION_TIMER = "im.manus.clockwallpaper.TIMER";
    private static final String ACTION_MUSIC = "im.manus.clockwallpaper.MUSIC";
    private static final String ADMIN_CLASS = "im.manus.universalhost.ModuleAdminReceiver";
    private static final String WAKE_ACTIVITY = "im.manus.universalhost.ModuleWakeActivity";

    private static final int FLIP_COOLDOWN = 8000;
    // Пороги переворота экраном вниз (ось Z, м/с²): срабатывание при ≤ -8.6, сброс при > -7.0, удержание 500 мс
    private static final float FLIP_ON = -8.6f;
    private static final float FLIP_OFF = -7.0f;
    private static final int FLIP_HOLD = 500;

    private AccessibilityService svc;
    private Handler handler;
    private SharedPreferences sp;
    private SharedPreferences st;
    private WindowManager wm;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private SensorManager sm;
    private PowerManager.WakeLock wakeLock;
    private View blocker;
    private WakeView wakeView;
    private Bitmap wallpaper;
    private boolean active;
    private boolean receiversRegistered;
    private boolean sensorsRegistered;

    private boolean near;
    private boolean upside;
    private boolean faceDown;
    private boolean showing;
    private boolean gzInit;
    private boolean flipFired = true;
    private float gz;
    private long lastWake;
    private long faceDownSince;
    private long ownWakeUntil;
    private long lastPlug;
    private long lastScreenOn;
    private long blockedUntil;
    private boolean lastSavedNear;
    private boolean lastSavedFaceDown;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "ClockWallpaper"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Заставка-часы для e-ink: пробуждение каждые 5 минут, переворот, будильник с музыкой";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        if (!(context instanceof AccessibilityService)) return;
        svc = (AccessibilityService) context;
        handler = new Handler(Looper.getMainLooper());
        sp = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        st = svc.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        dpm = (DevicePolicyManager) svc.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(svc.getPackageName(), ADMIN_CLASS);
        active = true;
        showing = false;
        gzInit = false;
        flipFired = true;
        goHomeAfterBoot();
        if (!on("clockEnabled", true)) return;
        try {
            registerReceivers();
            registerSensors();
            scheduleExact();
            scheduleMusic();
        } catch (Throwable t) {
            note("Ошибка запуска: " + t);
        }
    }

    @Override
    public void stop() {
        active = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (svc != null) {
            if (receiversRegistered) {
                try { svc.unregisterReceiver(plugReceiver); } catch (Throwable ignored) { }
                try { svc.unregisterReceiver(timerReceiver); } catch (Throwable ignored) { }
                try { svc.unregisterReceiver(musicReceiver); } catch (Throwable ignored) { }
                receiversRegistered = false;
            }
            if (sensorsRegistered && sm != null) {
                sm.unregisterListener(sensorListener);
                sensorsRegistered = false;
            }
            try {
                AlarmManager am = (AlarmManager) svc.getSystemService(Context.ALARM_SERVICE);
                am.cancel(timerIntent());
            } catch (Throwable ignored) { }
        }
        hideBlocker();
        done();
        wakeView = null;
        wallpaper = null;
        svc = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    /** Команды от ModuleWakeActivity и ручной показ заставки: execute({"cmd": "test"}). */
    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("cmd");
        if (!(cmd instanceof String)) return null;
        String c = (String) cmd;
        if ("createView".equals(c)) {
            Object a = data.get("activity");
            Object in = data.get("intent");
            if (svc == null || !(a instanceof Activity) || !(in instanceof Intent)) return null;
            wakeView = new WakeView((Activity) a, (Intent) in);
            return wakeView;
        }
        if ("newIntent".equals(c)) {
            if (wakeView != null) wakeView.onNewIntent();
        } else if ("back".equals(c)) {
            if (wakeView != null) wakeView.cancelLock();
        } else if ("destroyView".equals(c)) {
            wakeView = null;
            wallpaper = null;           // картинку не держим в памяти между показами
        } else if ("test".equals(c)) {
            if (active) wake("test");
        }
        return null;
    }

    // ---------------------------------------------------------------- настройки (экран в хосте)

    private boolean on(String key, boolean def) { return sp.getBoolean(key, def); }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Заставка"));
        l.add(SettingItem.toggle("clockEnabled", "Работать", "Датчики, таймер и будильник", true));
        l.add(SettingItem.toggle("enabled", "Пробуждение каждые 5 минут", "Включает экран и показывает заставку", true));
        l.add(SettingItem.toggle("flip", "Показ при перевороте экраном вниз", "Заставка и погасание экрана", true));
        l.add(SettingItem.toggle("vibrateOnWake", "Виброотклик при включении экрана", "Только когда экран включил пользователь", true));
        l.add(SettingItem.toggle("bootHome", "На рабочий стол после загрузки", "Работает, когда модуль запустился в первые минуты после загрузки", true));
        l.add(SettingItem.section("Датчики"));
        l.add(SettingItem.toggle("proximity", "Не будить при закрытом датчике приближения", "В кармане или под чехлом", true));
        l.add(SettingItem.toggle("faceDown", "Не будить, когда экран смотрит вниз", "Таймерные пробуждения пропускаются", true));
        l.add(SettingItem.toggle("plugProximity", "Гасить экран, включившийся от зарядки", "Подключение и отключение питания", true));
        l.add(SettingItem.section("Фон"));
        l.add(SettingItem.image("wallpaper_image", "Картинка циферблата", "clock_wallpaper.png из репозитория clock; стрелки рассчитаны на неё"));
        l.add(SettingItem.toggle("wallpaperUpsideDown", "Перевёрнутая картинка", "Поворачивает картинку и стрелки на 180°", false));
        l.add(SettingItem.toggle("normalOrientation", "Всегда обычная ориентация", "Игнорирует «Перевёрнутая картинка»", false));
        l.add(SettingItem.section("Музыкальный будильник"));
        l.add(SettingItem.toggle("musicAlarm", "Ежедневно нажимать Play", "Запускает активный плеер в заданное время", false));
        l.add(SettingItem.slider("alarmHour", "Час", "", 0, 23, 1, 8));
        l.add(SettingItem.slider("alarmMinute", "Минута", "", 0, 59, 1, 0));
        l.add(SettingItem.section("Разрешения и проверка"));
        l.add(SettingItem.action("admin", "Администратор устройства", "Нужен только для выключения экрана после заставки"));
        l.add(SettingItem.action("overlay_permission", "Показ поверх других приложений", "Нужно, чтобы заставка открывалась из фона"));
        l.add(SettingItem.action("test", "Показать заставку сейчас", "Без погасания экрана"));
        l.add(SettingItem.action("diag", "Состояние", "Следующее пробуждение, последняя причина, датчики"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (svc == null || !active) return;
        if ("clockEnabled".equals(key)) {
            Context c = svc;
            stop();
            init(c);
        } else if ("wallpaper_image".equals(key)) {
            wallpaper = null;
        } else if ("musicAlarm".equals(key) || "alarmHour".equals(key) || "alarmMinute".equals(key)) {
            scheduleMusic();
        } else if ("admin".equals(key)) {
            requestAdmin();
        } else if ("overlay_permission".equals(key)) {
            openOverlaySettings();
        } else if ("test".equals(key)) {
            wake("test");
        } else if ("diag".equals(key)) {
            toast(diagText());
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
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(48 * s, 48 * s, 34 * s, p);
        c.drawLine(48 * s, 48 * s, 48 * s, 26 * s, p);
        c.drawLine(48 * s, 48 * s, 62 * s, 54 * s, p);
        return b;
    }

    private String diagText() {
        long next = st.getLong("nextWake", 0);
        long last = st.getLong("lastWake", 0);
        StringBuilder sb = new StringBuilder("ClockWallpaper: ");
        sb.append("администратор ").append(dpm.isAdminActive(admin) ? "включён" : "не включён");
        sb.append(", поверх приложений ").append(Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(svc) ? "разрешено" : "не разрешено");
        sb.append("\nСледующее пробуждение: ").append(next == 0 ? "—" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(next)));
        sb.append("\nПоследнее: ").append(last == 0 ? "—" : new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(last)));
        sb.append(" (").append(st.getString("lastReason", "—")).append(")");
        sb.append("\nДатчики: приближение ").append(near ? "закрыт" : "свободен").append(", экран ").append(faceDown ? "вниз" : "вверх");
        sb.append("\nКартинка циферблата: ").append(sp.getString("wallpaper_image", null) == null ? "не выбрана" : "выбрана");
        return sb.toString();
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

    private void note(String s) {
        if (st == null) return;
        st.edit().putString("lastReason", s).putLong("lastEvent", System.currentTimeMillis()).apply();
    }

    private void register(Context c, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(r, f);
    }

    private void registerReceivers() {
        IntentFilter pf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        pf.addAction(Intent.ACTION_POWER_CONNECTED);
        pf.addAction(Intent.ACTION_POWER_DISCONNECTED);
        register(svc, plugReceiver, pf);
        register(svc, timerReceiver, new IntentFilter(ACTION_TIMER));
        register(svc, musicReceiver, new IntentFilter(ACTION_MUSIC));
        receiversRegistered = true;
    }

    private void registerSensors() {
        sm = (SensorManager) svc.getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        st.edit().putBoolean("accelerometerAvailable", accel != null).putBoolean("proximityAvailable", prox != null).apply();
        if (accel != null) sm.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_NORMAL);
        if (prox != null) sm.registerListener(sensorListener, prox, SensorManager.SENSOR_DELAY_NORMAL);
        sensorsRegistered = true;
    }

    /** Переход на рабочий стол один раз за загрузку (в приложении clock это делает BootReceiver). */
    private void goHomeAfterBoot() {
        if (!on("bootHome", true)) return;
        long up = SystemClock.elapsedRealtime();
        if (up > 5L * 60L * 1000L) return;
        long bootId = (System.currentTimeMillis() - up) / 60000L;
        if (Math.abs(st.getLong("homeBoot", -100L) - bootId) <= 1L) return;
        st.edit().putLong("homeBoot", bootId).apply();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(home);
        } catch (Throwable ignored) { }
    }

    private void requestAdmin() {
        if (dpm.isAdminActive(admin)) { toast("Администратор устройства уже включён"); return; }
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Нужно только для выключения экрана после заставки")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(i);
        } catch (Throwable t) {
            toast("Не удалось открыть запрос администратора: " + t);
        }
    }

    private void openOverlaySettings() {
        try {
            svc.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + svc.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Не удалось открыть настройки: " + t);
        }
    }

    // ---------------------------------------------------------------- таймер и будильник

    private PendingIntent timerIntent() {
        return PendingIntent.getBroadcast(svc, 77, new Intent(ACTION_TIMER).setPackage(svc.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent musicIntent() {
        return PendingIntent.getBroadcast(svc, 88, new Intent(ACTION_MUSIC).setPackage(svc.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void setAlarm(long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) svc.getSystemService(Context.ALARM_SERVICE);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            note("Нет права на точные будильники, время приблизительное");
        }
    }

    /** Следующая граница кратная 5 минутам: :00, :05, :10 ... */
    private void scheduleExact() {
        Calendar n = Calendar.getInstance();
        n.set(Calendar.SECOND, 0);
        n.set(Calendar.MILLISECOND, 0);
        int next = ((n.get(Calendar.MINUTE) / 5) + 1) * 5;
        if (next >= 60) {
            n.add(Calendar.HOUR_OF_DAY, 1);
            next = 0;
        }
        n.set(Calendar.MINUTE, next);
        long at = n.getTimeInMillis();
        st.edit().putLong("nextWake", at).apply();
        setAlarm(at, timerIntent());
    }

    private void scheduleMusic() {
        if (svc == null) return;
        AlarmManager am = (AlarmManager) svc.getSystemService(Context.ALARM_SERVICE);
        if (!on("musicAlarm", false)) {
            am.cancel(musicIntent());
            return;
        }
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, sp.getInt("alarmHour", 8));
        at.set(Calendar.MINUTE, sp.getInt("alarmMinute", 0));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        setAlarm(at.getTimeInMillis(), musicIntent());
    }

    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!active) return;
            wake("timer");
            scheduleExact();
        }
    };

    private final BroadcastReceiver musicReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!active || !on("musicAlarm", false)) return;
            Intent media = new Intent(Intent.ACTION_MEDIA_BUTTON);
            media.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            svc.sendBroadcast(media);
            media.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            svc.sendBroadcast(media);
            try {      // на новых Android широковещательная кнопка не доходит, поэтому дублируем через AudioManager
                AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            } catch (Throwable ignored) { }
            Calendar at = Calendar.getInstance();
            at.add(Calendar.DAY_OF_YEAR, 1);
            at.set(Calendar.HOUR_OF_DAY, sp.getInt("alarmHour", 8));
            at.set(Calendar.MINUTE, sp.getInt("alarmMinute", 0));
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            setAlarm(at.getTimeInMillis(), musicIntent());
        }
    };

    // ---------------------------------------------------------------- включение экрана и зарядка

    private final BroadcastReceiver plugReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!active) return;
            String a = i.getAction();
            long now = SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                lastScreenOn = now;
                handler.postDelayed(new Runnable() {
                    @Override public void run() { vibrateOnUserWake(0); }
                }, 250L);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(a) || Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                lastPlug = now;
            } else {
                return;
            }
            maybeBlockPlugWake();
            handler.postDelayed(new Runnable() {
                @Override public void run() { maybeBlockPlugWake(); }
            }, 700L);
        }
    };

    private void buzz(int ms) {
        Vibrator v = (Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }
    }

    /** Виброотклик только при включении экрана пользователем: не на таймерных пробуждениях и не при экране, погашенном зарядкой. */
    private void vibrateOnUserWake(int attempt) {
        if (!active || svc == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!on("vibrateOnWake", true)) return;
        if (now < ownWakeUntil || now < blockedUntil) return;
        if (!((PowerManager) svc.getSystemService(Context.POWER_SERVICE)).isInteractive()) return;
        boolean plugRecent = lastPlug != 0 && now - lastPlug < 3000 && on("plugProximity", true);
        if (plugRecent && attempt < 1) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { vibrateOnUserWake(1); }
            }, 900L);
            return;
        }
        buzz(120);
    }

    /** Если экран включился из-за подключения/отключения зарядки — сразу гасим его. */
    private void maybeBlockPlugWake() {
        if (!active || svc == null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastPlug == 0 || lastScreenOn == 0) return;
        if (Math.abs(lastPlug - lastScreenOn) > 3000 || now - Math.max(lastPlug, lastScreenOn) > 3000) return;
        if (!on("plugProximity", true) || now < ownWakeUntil) return;
        if (!((PowerManager) svc.getSystemService(Context.POWER_SERVICE)).isInteractive()) return;
        lastPlug = 0;
        lastScreenOn = 0;
        blockedUntil = now + 3000;
        note("Пропуск: экран включился от зарядки");
        if (dpm.isAdminActive(admin)) dpm.lockNow();
    }

    private void wake(String why) {
        if (!active || svc == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean flip = why.equals("flip");
        boolean test = why.equals("test");
        if (!test && !on("enabled", true)) { note("Отключено"); return; }
        if (showing) { note("Пропуск: уже показывается"); return; }
        if (flip && now - lastWake < FLIP_COOLDOWN) { note("Пропуск: защита переворота"); return; }
        PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
        boolean wasInteractive = pm.isInteractive();
        if (why.equals("timer") && wasInteractive) { note("Пропуск: экран уже включён"); return; }
        if (!test && !flip && ((near && on("proximity", true)) || (faceDown && on("faceDown", true)))) {
            note(near ? "Пропуск: приближение" : "Пропуск: экран вниз");
            return;
        }
        if (flip) buzz(120);
        showing = true;
        lastWake = now;
        ownWakeUntil = now + 4000;
        st.edit().putLong("lastWake", System.currentTimeMillis()).putString("lastReason", why).apply();
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "ClockWallpaper:Wake");
        wakeLock.acquire(1500);
        Intent i = new Intent().setClassName(svc.getPackageName(), WAKE_ACTIVITY)
                .putExtra("module", getName())
                .putExtra("screenWasOn", wasInteractive)
                .putExtra("reason", why)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(svc)) {
            note("Нет разрешения на показ поверх приложений");
        }
        try {
            svc.startActivity(i);
        } catch (Throwable t) {
            note("Не удалось открыть заставку: " + t);
            done();
            toast("ClockWallpaper: не удалось открыть заставку (разрешите показ поверх других приложений)");
        }
    }

    private void done() {
        showing = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) { }
        }
        wakeLock = null;
    }

    // ---------------------------------------------------------------- датчики

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (!active || sp == null || !on("clockEnabled", true)) return;
            if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                near = e.values[0] < e.sensor.getMaximumRange();
                if (near != lastSavedNear) {
                    lastSavedNear = near;
                    st.edit().putBoolean("near", near).apply();
                }
            }
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                float x = e.values[0], y = e.values[1], z = e.values[2];
                if (!gzInit) { gz = z; gzInit = true; } else gz = gz * 0.7f + z * 0.3f;
                float mag = (float) Math.sqrt(x * x + y * y + z * z);
                boolean steady = mag > 8.3f && mag < 11.3f;
                upside = y < -7.5f;
                faceDown = faceDown ? gz < FLIP_OFF : gz < FLIP_ON;
                if (faceDown != lastSavedFaceDown) {
                    lastSavedFaceDown = faceDown;
                    st.edit().putBoolean("faceDownState", faceDown).apply();
                }
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
                    if (!flipFired && now - faceDownSince >= FLIP_HOLD && on("flip", true)) {
                        flipFired = true;
                        wake("flip");
                    }
                } else {
                    faceDownSince = 0;
                }
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
    };

    // ---------------------------------------------------------------- прозрачное наложение, перехватывающее касания

    /** Каждый показ пересоздаёт окно, чтобы оно было поверх наложений других модулей. */
    private void showBlocker() {
        hideBlocker();
        if (svc == null) return;
        View v = new View(svc);
        v.setClickable(true);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) { return true; }
        });
        blocker = v;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(blocker, lp);
        } catch (Throwable t) {
            blocker = null;
        }
    }

    private void hideBlocker() {
        if (blocker != null && wm != null) {
            try { wm.removeView(blocker); } catch (Throwable ignored) { }
        }
        blocker = null;
    }

    // ---------------------------------------------------------------- картинка циферблата

    /** Картинка из настроек, уменьшенная до размера экрана (чтобы не упереться в память). */
    private Bitmap loadWallpaper(Context c) {
        if (wallpaper != null) return wallpaper;
        String path = sp.getString("wallpaper_image", null);
        if (path == null) return null;
        try {
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int target = Math.max(dm.widthPixels, dm.heightPixels);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            wallpaper = BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            wallpaper = null;
        }
        return wallpaper;
    }

    // ---------------------------------------------------------------- экран заставки (вью внутри ModuleWakeActivity)

    private final class WakeView extends FrameLayout {
        private final Activity act;
        private final boolean screenWasOn;
        private final boolean forceLock;
        private final Hands hands;
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean lockedByUs;
        private Runnable lockTask;
        private BroadcastReceiver batteryReceiver;
        private BroadcastReceiver screenReceiver;

        WakeView(Activity a, Intent intent) {
            super(a);
            act = a;
            screenWasOn = intent.getBooleanExtra("screenWasOn", false);
            forceLock = "flip".equals(intent.getStringExtra("reason"));
            boolean upsideDown = on("wallpaperUpsideDown", false) && !on("normalOrientation", false);

            ImageView image = new ImageView(a);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.BLACK);
            Bitmap bmp = loadWallpaper(a);
            if (bmp != null) image.setImageBitmap(bmp);
            if (upsideDown) image.setRotation(180);
            addView(image, new FrameLayout.LayoutParams(-1, -1));

            hands = new Hands(a);
            if (upsideDown) hands.setRotation(180);
            addView(hands, new FrameLayout.LayoutParams(-1, -1));
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            batteryReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    hands.setBattery(i.getIntExtra("level", 100),
                            i.getIntExtra("status", 0) == BatteryManager.BATTERY_STATUS_CHARGING);
                }
            };
            register(act, batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            // Заставка осталась на экране после lockNow(): когда телефон включают снова, закрываем её
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (!lockedByUs) return;
                    h.postDelayed(new Runnable() {
                        @Override public void run() { userWake(0); }
                    }, 250L);
                }
            };
            register(act, screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
            showBlocker();
            scheduleLock();
        }

        @Override protected void onDetachedFromWindow() {
            h.removeCallbacksAndMessages(null);
            if (batteryReceiver != null) { try { act.unregisterReceiver(batteryReceiver); } catch (Throwable ignored) { } }
            if (screenReceiver != null) { try { act.unregisterReceiver(screenReceiver); } catch (Throwable ignored) { } }
            batteryReceiver = null;
            screenReceiver = null;
            hideBlocker();
            done();
            super.onDetachedFromWindow();
        }

        private void userWake(int attempt) {
            if (!lockedByUs) return;
            long now = SystemClock.elapsedRealtime();
            if (now < ownWakeUntil || now < blockedUntil) return;
            boolean plugRecent = lastPlug != 0 && now - lastPlug < 3000 && on("plugProximity", true);
            if (plugRecent && attempt < 1) {
                h.postDelayed(new Runnable() {
                    @Override public void run() { userWake(1); }
                }, 900L);
                return;
            }
            lockedByUs = false;
            act.finishAndRemoveTask();
        }

        void onNewIntent() {
            hands.invalidate();
            showBlocker();
            scheduleLock();
        }

        private void scheduleLock() {
            lockedByUs = false;
            if (lockTask != null) h.removeCallbacks(lockTask);
            lockTask = new Runnable() {
                @Override public void run() {
                    done();
                    if (screenWasOn && !forceLock) {
                        act.finishAndRemoveTask();
                    } else if (dpm.isAdminActive(admin)) {
                        lockedByUs = true;
                        dpm.lockNow();
                    } else {
                        note("Администратор устройства не включён: экран не погасили");
                        act.finishAndRemoveTask();
                    }
                }
            };
            h.postDelayed(lockTask, 1000L);
        }

        void cancelLock() {
            if (lockTask != null) h.removeCallbacks(lockTask);
            done();
        }
    }

    /** Стрелки поверх картинки циферблата (положения и размеры — как в ScreenHandsOverlay приложения clock). */
    private static final class Hands extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private int battery = 100;
        private boolean charging = false;

        Hands(Context c) {
            super(c);
            p.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setBattery(int value, boolean isCharging) {
            battery = Math.max(0, Math.min(100, value));
            charging = isCharging;
            invalidate();
        }

        private void hand(Canvas c, float cx, float cy, float len, float angle, float width, int color) {
            double a = Math.toRadians(angle - 90);
            float x = cx + (float) Math.cos(a) * len;
            float y = cy + (float) Math.sin(a) * len;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(width + 10);
            p.setColor(Color.argb(190, 20, 5, 0));
            c.drawLine(cx, cy, x, y, p);
            p.setStrokeWidth(width);
            p.setColor(color);
            c.drawLine(cx, cy, x, y, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(x, y, width * 1.25f, p);
        }

        private void handLabel(Canvas c, float cx, float cy, float len, float angle, float width, int color, String label) {
            hand(c, cx, cy, len, angle, width, color);
            double a = Math.toRadians(angle - 90);
            float x = cx + (float) Math.cos(a) * len;
            float y = cy + (float) Math.sin(a) * len;
            float r = Math.max(22, width * 3.01f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(70, 22, 4));
            c.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(color);
            c.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(Math.max(36, width * 3.5f));
            p.setColor(color);
            p.setShadowLayer(4, 1, 1, Color.BLACK);
            c.drawText(label, x, y - p.ascent() / 2 - p.descent() / 2, p);
            p.clearShadowLayer();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), cx = w * .53f, cy = h * .46f;
            Calendar now = Calendar.getInstance();
            int gold = Color.rgb(255, 218, 86), light = Color.rgb(255, 240, 150);
            float hour = ((now.get(Calendar.HOUR_OF_DAY) % 12) + now.get(Calendar.MINUTE) / 60f) / 12f * 360f;
            float minute = (now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f) / 60f * 360f;
            hand(c, cx, cy, w * .48f, hour, Math.max(15, w * .027f), gold);
            hand(c, cx, cy, w * .65f, minute, Math.max(11, w * .018f), light);

            // день недели
            float wx = w * .271f, wy = h * .464f;
            float week = ((now.get(Calendar.DAY_OF_WEEK) - 1) / 7f) * 360f;
            hand(c, wx, wy, Math.min(w, h) * .10725f, week, Math.max(10, w * .018f), Color.rgb(255, 225, 105));

            // заряд батареи
            float topX = w * .5f, topY = h * .218f;
            float batteryAngle = 360f - (battery / 100f) * 360f;
            hand(c, topX, topY, Math.min(w, h) * .1725f, batteryAngle, Math.max(7, w * .0135f), gold);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(Math.max(26, w * .056f));
            p.setColor(gold);
            p.setShadowLayer(4, 1, 1, Color.BLACK);
            c.drawText(battery + "%", topX, h * .285f, p);
            if (charging) {
                p.setTextSize(Math.max(39, w * .084f));
                c.drawText("\u26A1", topX + w * .13f, h * .18f, p);
            }
            p.clearShadowLayer();

            // месяц и число
            float bottomY = h * .72f;
            float month = now.get(Calendar.MONTH) / 12f * 360f;
            float day = (now.get(Calendar.DAY_OF_MONTH) - 1) / 31f * 360f;
            handLabel(c, cx - w * .03f, bottomY, Math.min(w, h) * .115f, month, Math.max(5, w * .009f), gold, String.valueOf(now.get(Calendar.MONTH) + 1));
            handLabel(c, cx - w * .03f, bottomY, Math.min(w, h) * .155f, day, Math.max(5, w * .009f), light, String.valueOf(now.get(Calendar.DAY_OF_MONTH)));

            // центральная задвижка
            p.setStyle(Paint.Style.FILL);
            p.setColor(gold);
            c.drawCircle(cx, cy, Math.max(12, w * .022f), p);
            p.setColor(Color.rgb(75, 20, 4));
            c.drawCircle(cx, cy, Math.max(5, w * .010f), p);
        }
    }
}
