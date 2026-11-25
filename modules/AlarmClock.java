package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import im.manus.universalhost.IKeyHandler;
import im.manus.universalhost.IMainViewProvider;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.SettingItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AlarmClock — будильник, которому не нужен экран.
 *
 * При срабатывании экран НЕ включается: нет ни окна поверх блокировки, ни полноэкранного уведомления.
 * Звучит мелодия (поток будильника), идёт вибрация, в шторке висит тихое уведомление с кнопками.
 * Остановить или отложить можно, не глядя на телефон, настраиваемыми жестами:
 *  - кнопки: громкость, кнопка гарнитуры, кнопки геймпада и любая другая кнопка, которую модуль «услышит»
 *    (одно, двойное, тройное нажатие или удержание); кнопка записывается нажатием прямо в настройках;
 *  - движения телефона: встряхнуть, перевернуть экраном вниз, взмах рукой над датчиком приближения,
 *    закрыть датчик на секунду.
 * Те же команды принимает execute({"command": "snooze" | "dismiss"}) и broadcast-ы
 * im.manus.universalhost.ALARM_SNOOZE / im.manus.universalhost.ALARM_DISMISS (для Micro Gesture, Tasker и т. п.).
 *
 * Время срабатывания ставится через AlarmManager.setAlarmClock (значок будильника в строке состояния,
 * срабатывает и в Doze). Приёмник регистрируется самим модулем внутри службы специальных возможностей,
 * поэтому манифест хоста менять не нужно. Если служба успела перезапуститься и будильник сработал,
 * пока модуль не работал, он прозвонит при запуске, но не позже чем через 10 минут после назначенного времени.
 */
public class AlarmClock implements IPlugin, ISettingsProvider, ISettingsViewProvider, IMainViewProvider, IKeyHandler {

    private static final String PREFS = "module_AlarmClock";

    private static final String ACT_FIRE = "im.manus.universalhost.ALARMCLOCK_FIRE";
    private static final String ACT_SNOOZE = "im.manus.universalhost.ALARM_SNOOZE";
    private static final String ACT_DISMISS = "im.manus.universalhost.ALARM_DISMISS";

    /** Метка «будильник звонит» в System properties (как у плеера): по ней модуль пульта включает свой режим «будильник звонит» (отдельные назначения кнопок, по умолчанию «отложить»). */
    private static final String LIVE_KEY = "manus.alarmclock.ringing";

    private static final String CHANNEL = "alarmclock";
    private static final int NOTIF_ID = 7311;
    private static final int REQ_FIRE = 7301;
    private static final int REQ_SHOW = 7302;
    private static final int REQ_SNOOZE = 7303;
    private static final int REQ_DISMISS = 7304;

    private static final long MISSED_MS = 10 * 60000L;   // опоздавший звонок ещё прозвенит
    private static final long LONG_MS = 700;             // удержание кнопки
    private static final long TAP_MS = 330;              // окно для двойного и тройного нажатия
    private static final long LEARN_MS = 10000;          // сколько ждём кнопку при записи жеста

    private static final String[] DAY_NAMES = { "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс" };
    private static final String[] MOTION = { "M:shake", "M:flip", "M:wave", "M:cover" };

    private static final class Alarm {
        int id;
        int h;
        int m;
        int days;      // маска дней: бит 0 — понедельник … бит 6 — воскресенье; 0 — один раз
        boolean on;
    }

    private static final class KeyState {
        boolean down;
        boolean longFired;
        int taps;
        Runnable longTask;
        Runnable tapTask;
    }

    private Context ctx;
    private SharedPreferences cfg;
    private Handler main;
    private AccessibilityService svc;
    private AlarmManager am;
    private boolean active;
    private boolean receiversOn;

    // звонок
    private volatile boolean ringing;
    private MediaPlayer player;
    private PowerManager.WakeLock wl;
    private AudioManager audio;
    private AudioFocusRequest focusReq;
    private int oldAlarmVol = -1;
    private boolean volChanged;
    private boolean fgOk;
    private MediaSession session;
    private long soundStart;
    private Vibrator vib;

    // датчики
    private SensorManager sm;
    private boolean accelOn;
    private boolean proxOn;
    private long motionArmedAt;
    private long lastMotionAt;
    private int jerks;
    private long firstJerk;
    private long lastJerk;
    private boolean faceUpSeen;
    private long faceDownSince;
    private boolean proxFirst;
    private boolean proxNear;
    private long proxNearSince;
    private float proxMax = 5f;

    // кнопки
    private final Map<Integer, KeyState> keys = new java.util.HashMap<Integer, KeyState>();
    private long lastEvTime;
    private int lastEvKey;
    private int lastEvAct = -1;

    // запись жеста
    private int learnTarget;      // 0 — не пишем, 1 — «отложить», 2 — «выключить»

    // озвучка
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;

    private Runnable uiRefresh;
    private Runnable gestRefresh;
    private int expandedId = -1;

    @Override public String getName() { return "AlarmClock"; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() { return "Будильник без экрана: жесты, кнопки, гарнитура"; }
    @Override public String getIconName() { return "ic_launcher"; }

    // ================================================================ жизненный цикл

    private void ensureBase(Context c) {
        if (cfg != null && main != null && ctx != null) return;
        Context app = c.getApplicationContext();
        ctx = app != null ? app : c;
        cfg = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        main = new Handler(Looper.getMainLooper());
    }

    @Override
    public void init(Context context) {
        stop();
        ensureBase(context);
        svc = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
        am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        active = true;
        registerReceivers();

        long now = System.currentTimeMillis();
        long at = cfg.getLong("armed_at", 0);
        long tok = cfg.getLong("armed_tok", 0);
        if (at > 0 && tok != 0 && at <= now) {
            if (now - at <= MISSED_MS) {
                onFire(tok);                         // служба перезапускалась: звоним с опозданием
                return;
            }
            int id = cfg.getInt("armed_id", -1);     // слишком поздно: разовый будильник гасим
            if (id >= 0) {
                List<Alarm> l = loadAlarms();
                for (Alarm a : l) if (a.id == id && a.days == 0) a.on = false;
                saveAlarms(l);
            } else {
                cfg.edit().putLong("snooze_at", 0).apply();
            }
        }
        reschedule();
    }

    @Override
    public void stop() {
        active = false;
        learnTarget = 0;
        if (main != null) main.removeCallbacksAndMessages(null);
        stopRinging();
        unregisterReceivers();
        try {
            if (am != null && ctx != null) am.cancel(firePending(0));
        } catch (Throwable ignored) { }
        shutdownTts();
        keys.clear();
        svc = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    private final BroadcastReceiver rxInt = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || i.getAction() == null) return;
            if (ACT_FIRE.equals(i.getAction())) onFire(i.getLongExtra("tok", 0));
            else reschedule();      // сменилось время, часовой пояс или дата
        }
    };

    private final BroadcastReceiver rxExt = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || i.getAction() == null || !ringing) return;
            if (ACT_SNOOZE.equals(i.getAction())) doSnooze();
            else if (ACT_DISMISS.equals(i.getAction())) doDismiss();
        }
    };

    private void registerReceivers() {
        if (receiversOn) return;
        try {
            IntentFilter fi = new IntentFilter();
            fi.addAction(ACT_FIRE);
            fi.addAction(Intent.ACTION_TIME_CHANGED);
            fi.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            fi.addAction(Intent.ACTION_DATE_CHANGED);
            IntentFilter fe = new IntentFilter();
            fe.addAction(ACT_SNOOZE);
            fe.addAction(ACT_DISMISS);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(rxInt, fi, 4);      // RECEIVER_NOT_EXPORTED
                ctx.registerReceiver(rxExt, fe, 2);      // RECEIVER_EXPORTED: команды из Micro Gesture, Tasker
            } else {
                ctx.registerReceiver(rxInt, fi);
                ctx.registerReceiver(rxExt, fe);
            }
            receiversOn = true;
        } catch (Throwable ignored) { }
    }

    private void unregisterReceivers() {
        if (!receiversOn || ctx == null) return;
        try { ctx.unregisterReceiver(rxInt); } catch (Throwable ignored) { }
        try { ctx.unregisterReceiver(rxExt); } catch (Throwable ignored) { }
        receiversOn = false;
    }

    // ================================================================ будильники и планирование

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private List<Alarm> loadAlarms() {
        List<Alarm> l = new ArrayList<Alarm>();
        try {
            JSONArray a = new JSONArray(cfg.getString("alarms", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Alarm x = new Alarm();
                x.id = o.optInt("id");
                x.h = clamp(o.optInt("h", 7), 0, 23);
                x.m = clamp(o.optInt("m", 0), 0, 59);
                x.days = o.optInt("d", 0) & 127;
                x.on = o.optBoolean("on", true);
                l.add(x);
            }
        } catch (Throwable ignored) { }
        return l;
    }

    private void saveAlarms(List<Alarm> l) {
        try {
            JSONArray a = new JSONArray();
            for (Alarm x : l) {
                JSONObject o = new JSONObject();
                o.put("id", x.id);
                o.put("h", x.h);
                o.put("m", x.m);
                o.put("d", x.days);
                o.put("on", x.on);
                a.put(o);
            }
            cfg.edit().putString("alarms", a.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static int dayIndex(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);       // воскресенье = 1 … суббота = 7
        return (dow + 5) % 7;                        // понедельник = 0 … воскресенье = 6
    }

    private static long nextTrigger(Alarm a, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, a.h);
        c.set(Calendar.MINUTE, a.m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 9; i++) {
            boolean dayOk = a.days == 0 || (a.days & (1 << dayIndex(c))) != 0;
            if (dayOk && c.getTimeInMillis() > now) return c.getTimeInMillis();
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return Long.MAX_VALUE;
    }

    private PendingIntent firePending(long tok) {
        Intent i = new Intent(ACT_FIRE).setPackage(ctx.getPackageName());
        i.putExtra("tok", tok);
        return PendingIntent.getBroadcast(ctx, REQ_FIRE, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent showPending() {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (i == null) i = new Intent();
        return PendingIntent.getActivity(ctx, REQ_SHOW, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Ставит в систему ближайшее из: включённые будильники и отложенный звонок. */
    private synchronized void reschedule() {
        if (cfg == null || ctx == null) return;
        if (am == null) am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        int bestId = -1;
        for (Alarm a : loadAlarms()) {
            if (!a.on) continue;
            long t = nextTrigger(a, now);
            if (t < best) { best = t; bestId = a.id; }
        }
        long sn = cfg.getLong("snooze_at", 0);
        if (sn > now && sn < best) { best = sn; bestId = -1; }
        try {
            if (!active || best == Long.MAX_VALUE) {
                am.cancel(firePending(0));
                cfg.edit().putLong("armed_at", 0).putLong("armed_tok", 0).apply();
            } else {
                long tok = (System.nanoTime() & 0x7FFFFFFFFFFFL) | 1L;
                cfg.edit().putLong("armed_at", best).putInt("armed_id", bestId).putLong("armed_tok", tok).apply();
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(best, showPending()), firePending(tok));
            }
        } catch (Throwable ignored) { }
        refreshUi();
    }

    private void onFire(long tok) {
        if (!active || cfg == null) return;
        long saved = cfg.getLong("armed_tok", 0);
        if (saved == 0 || saved != tok) return;      // устаревший или чужой сигнал
        int id = cfg.getInt("armed_id", -1);
        SharedPreferences.Editor e = cfg.edit().putLong("armed_at", 0).putLong("armed_tok", 0);
        if (id >= 0) {
            e.putInt("snooze_n", 0);
            List<Alarm> l = loadAlarms();
            for (Alarm a : l) if (a.id == id && a.days == 0) a.on = false;     // разовый отработал
            saveAlarms(l);
        } else {
            e.putLong("snooze_at", 0);
        }
        e.apply();
        startRing();
        reschedule();
    }

    // ================================================================ звонок

    private void startRing() {
        if (ringing) return;
        ringing = true;
        try { System.getProperties().put(LIVE_KEY, Boolean.TRUE); } catch (Throwable ignored) { }
        int tmo = clamp(cfg.getInt("ring_timeout", 10), 1, 30);
        try {
            if (wl == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClock:ring");
                wl.setReferenceCounted(false);
            }
            wl.acquire((tmo + 2) * 60000L);       // процессор бодрствует, экран остаётся выключенным
        } catch (Throwable ignored) { }
        showNotification();
        startSound();
        startVibration();
        startSession();
        startSensors();
        main.postDelayed(timeoutTask, tmo * 60000L);
        refreshUi();
        refreshGestUi();
    }

    private final Runnable timeoutTask = new Runnable() {
        @Override public void run() {
            if (!ringing) return;
            stopRinging();                         // не дождались: пропущенный будильник
            cfg.edit().putInt("snooze_n", 0).putLong("snooze_at", 0).apply();
            reschedule();
        }
    };

    private void stopRinging() {
        boolean was = ringing;
        ringing = false;
        try { System.getProperties().remove(LIVE_KEY); } catch (Throwable ignored) { }
        if (main != null) {
            main.removeCallbacks(timeoutTask);
            main.removeCallbacks(rampTask);
        }
        stopSensors();
        try {
            if (session != null) { session.setActive(false); session.release(); }
        } catch (Throwable ignored) { }
        session = null;
        try {
            if (player != null) { player.stop(); }
        } catch (Throwable ignored) { }
        try {
            if (player != null) player.release();
        } catch (Throwable ignored) { }
        player = null;
        try {
            if (vib != null) vib.cancel();
        } catch (Throwable ignored) { }
        abandonFocus();
        restoreVolume();
        hideNotification();
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable ignored) { }
        for (KeyState s : keys.values()) {
            if (main != null) {
                if (s.longTask != null) main.removeCallbacks(s.longTask);
                if (s.tapTask != null) main.removeCallbacks(s.tapTask);
            }
        }
        keys.clear();
        if (was) { refreshUi(); refreshGestUi(); }
    }

    private void startSound() {
        try {
            audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            oldAlarmVol = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int pct = cfg.getInt("ring_volume", 0);
            int want;
            if (pct > 0) want = Math.max(1, Math.round(max * pct / 100f));
            else want = oldAlarmVol == 0 ? Math.max(1, max / 2) : oldAlarmVol;   // нулевая громкость будильника — страховка
            if (want != oldAlarmVol) {
                try { audio.setStreamVolume(AudioManager.STREAM_ALARM, want, 0); volChanged = true; } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        requestFocus();

        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        List<Object> sources = new ArrayList<Object>();
        int choice = cfg.getInt("ring_sound", 0);
        String file = cfg.getString("ring_file", "");
        if (choice == 2 && file != null && file.trim().length() > 0) sources.add(file.trim());
        if (choice == 1) sources.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        sources.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        sources.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        sources.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        for (Object src : sources) {
            MediaPlayer mp = null;
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(aa);
                if (src instanceof Uri) mp.setDataSource(ctx, (Uri) src);
                else mp.setDataSource((String) src);
                mp.setLooping(true);
                mp.prepare();
                int ramp = clamp(cfg.getInt("ring_ramp", 10), 0, 60);
                float v0 = ramp > 0 ? 0.1f : 1f;
                mp.setVolume(v0, v0);
                mp.start();
                player = mp;
                soundStart = SystemClock.elapsedRealtime();
                if (ramp > 0) main.postDelayed(rampTask, 300);
                return;
            } catch (Throwable t) {
                try { if (mp != null) mp.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private final Runnable rampTask = new Runnable() {
        @Override public void run() {
            MediaPlayer p = player;
            if (p == null || !ringing) return;
            int ramp = clamp(cfg.getInt("ring_ramp", 10), 0, 60) * 1000;
            float v = ramp <= 0 ? 1f : Math.min(1f, 0.1f + 0.9f * (SystemClock.elapsedRealtime() - soundStart) / ramp);
            try { p.setVolume(v, v); } catch (Throwable ignored) { }
            if (v < 1f) main.postDelayed(this, 300);
        }
    };

    private void requestFocus() {
        try {
            if (audio == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(aa)
                        .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                            @Override public void onAudioFocusChange(int change) { }
                        }).build();
                audio.requestAudioFocus(focusReq);
            }
        } catch (Throwable ignored) { }
    }

    private void abandonFocus() {
        try {
            if (audio != null && focusReq != null && Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(focusReq);
        } catch (Throwable ignored) { }
        focusReq = null;
    }

    private void restoreVolume() {
        try {
            if (audio != null && volChanged && oldAlarmVol >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarmVol, 0);
        } catch (Throwable ignored) { }
        volChanged = false;
        oldAlarmVol = -1;
    }

    private void startVibration() {
        if (!cfg.getBoolean("ring_vibrate", true)) return;
        try {
            vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            long[] pattern = { 0, 700, 500 };
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, 0), aa);
            else vib.vibrate(pattern, 0, aa);
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- уведомление

    private PendingIntent actionPending(String action, int req) {
        Intent i = new Intent(action).setPackage(ctx.getPackageName());
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Тихое уведомление (важность LOW, без звука и без полноэкранного окна): экран оно не включает. */
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Будильник", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ Будильник")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(showPending())
                .addAction(0, "💤 Отложить", actionPending(ACT_SNOOZE, REQ_SNOOZE))
                .addAction(0, "⏹ Выключить", actionPending(ACT_DISMISS, REQ_DISMISS));
        return b.build();
    }

    /**
     * Foreground-статус службы нужен, чтобы Android 9+ не глушил акселерометр у «фонового» процесса при выключенном экране.
     * Если службе его выдать нельзя, уведомление показывается обычным способом, а датчики могут молчать.
     */
    private void showNotification() {
        try {
            Notification n = buildNotification();
            fgOk = false;
            if (svc instanceof Service) {
                try {
                    ((Service) svc).startForeground(NOTIF_ID, n);
                    fgOk = true;
                } catch (Throwable ignored) { }
            }
            if (!fgOk) {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, n);
            }
        } catch (Throwable ignored) { }
    }

    private void hideNotification() {
        try {
            if (fgOk && svc instanceof Service) ((Service) svc).stopForeground(true);
        } catch (Throwable ignored) { }
        try {
            if (ctx != null) ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        } catch (Throwable ignored) { }
        fgOk = false;
    }

    // ---------------------------------------------------------------- кнопка гарнитуры

    /** Активная MediaSession получает кнопку проводной и Bluetooth-гарнитуры, пока будильник звонит. */
    private void startSession() {
        try {
            MediaSession s = new MediaSession(ctx, "AlarmClockSession");
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            s.setCallback(new MediaSession.Callback() {
                @Override public boolean onMediaButtonEvent(Intent intent) {
                    try {
                        KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                        if (ke != null) handleKey(ke);
                    } catch (Throwable ignored) { }
                    return true;
                }
            }, main);
            s.setPlaybackState(new PlaybackState.Builder()
                    .setActions(0)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());
            s.setActive(true);
            session = s;
        } catch (Throwable ignored) { }
    }

    // ================================================================ кнопки: одно, двойное, тройное нажатие, удержание

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        return handleKey(event);
    }

    private static int normKey(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : code;
    }

    private boolean isBoundKey(int code) {
        String p = "K:" + code + ":";
        for (int a = 1; a <= 2; a++) for (String id : binds(a)) if (id.startsWith(p)) return true;
        return false;
    }

    private int maxTaps(int code) {
        String p = "K:" + code + ":";
        int max = 1;
        for (int a = 1; a <= 2; a++) {
            for (String id : binds(a)) {
                if (!id.startsWith(p)) continue;
                String pat = id.substring(p.length());
                if (pat.length() == 1 && Character.isDigit(pat.charAt(0))) max = Math.max(max, pat.charAt(0) - '0');
            }
        }
        return max;
    }

    private boolean hasLong(int code) {
        return binds(1).contains("K:" + code + ":L") || binds(2).contains("K:" + code + ":L");
    }

    private boolean handleKey(KeyEvent e) {
        if (!active || (!ringing && learnTarget == 0)) return false;
        final boolean learning = learnTarget != 0;
        final int code = normKey(e.getKeyCode());
        if (learning) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME
                    || code == KeyEvent.KEYCODE_APP_SWITCH || code == KeyEvent.KEYCODE_POWER) return false;
        } else if (!isBoundKey(code)) {
            return false;                          // громкость и остальные кнопки работают как обычно
        }
        int act = e.getAction();
        if (act != KeyEvent.ACTION_DOWN && act != KeyEvent.ACTION_UP) return true;
        if (e.getEventTime() == lastEvTime && code == lastEvKey && act == lastEvAct) return true;   // то же событие дошло двумя путями
        lastEvTime = e.getEventTime();
        lastEvKey = code;
        lastEvAct = act;

        KeyState s = keys.get(Integer.valueOf(code));
        if (s == null) { s = new KeyState(); keys.put(Integer.valueOf(code), s); }
        final KeyState st = s;

        if (act == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0 || st.down) return true;
            st.down = true;
            st.longFired = false;
            if (st.tapTask != null) main.removeCallbacks(st.tapTask);
            if (learning || hasLong(code)) {
                st.longTask = new Runnable() {
                    @Override public void run() {
                        if (!st.down) return;
                        st.longFired = true;
                        st.taps = 0;
                        if (st.tapTask != null) main.removeCallbacks(st.tapTask);
                        gesture("K:" + code + ":L");
                    }
                };
                main.postDelayed(st.longTask, LONG_MS);
            }
            return true;
        }

        // ACTION_UP
        st.down = false;
        if (st.longTask != null) main.removeCallbacks(st.longTask);
        if (st.longFired) { st.longFired = false; st.taps = 0; return true; }
        st.taps++;
        int max = learning ? 3 : maxTaps(code);
        if (st.taps >= max) {
            finishTaps(code, st);
        } else {
            st.tapTask = new Runnable() {
                @Override public void run() { finishTaps(code, st); }
            };
            main.postDelayed(st.tapTask, TAP_MS);
        }
        return true;
    }

    private void finishTaps(int code, KeyState st) {
        int n = Math.min(3, st.taps);
        st.taps = 0;
        if (n > 0) gesture("K:" + code + ":" + n);
    }

    // ================================================================ датчики: встряхнуть, перевернуть, взмах, закрыть датчик

    private boolean usesMotion(String... ids) {
        for (int a = 1; a <= 2; a++) {
            List<String> l = binds(a);
            for (String id : ids) if (l.contains(id)) return true;
        }
        return false;
    }

    private void startSensors() {
        try {
            sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            long now = SystemClock.elapsedRealtime();
            motionArmedAt = now + 800;           // первые движения самого телефона не считаем
            lastMotionAt = 0;
            jerks = 0;
            faceUpSeen = false;
            faceDownSince = 0;
            proxFirst = true;
            if (usesMotion("M:shake", "M:flip")) {
                Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (a != null) accelOn = sm.registerListener(sensorListener, a, SensorManager.SENSOR_DELAY_GAME, main);
            }
            if (usesMotion("M:wave", "M:cover")) {
                Sensor p = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                if (p != null) {
                    proxMax = p.getMaximumRange();
                    proxOn = sm.registerListener(sensorListener, p, SensorManager.SENSOR_DELAY_NORMAL, main);
                }
            }
        } catch (Throwable ignored) { }
    }

    private void stopSensors() {
        try {
            if (sm != null && (accelOn || proxOn)) sm.unregisterListener(sensorListener);
        } catch (Throwable ignored) { }
        accelOn = false;
        proxOn = false;
        if (main != null) main.removeCallbacks(coverTask);
    }

    private void motion(String id) {
        long now = SystemClock.elapsedRealtime();
        if (now < motionArmedAt || now - lastMotionAt < 1500) return;
        lastMotionAt = now;
        gesture(id);
    }

    private final Runnable coverTask = new Runnable() {
        @Override public void run() {
            if (ringing && proxNear) motion("M:cover");
        }
    };

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        @Override public void onSensorChanged(SensorEvent ev) {
            if (!ringing) return;
            long now = SystemClock.elapsedRealtime();
            if (ev.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                float x = ev.values[0], y = ev.values[1], z = ev.values[2];
                // встряхивание: три резких рывка за секунду
                float thr = clamp(cfg.getInt("shake_thr", 22), 15, 40) / 10f;
                float g = (float) Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
                if (g > thr && now - lastJerk > 180) {
                    if (now - firstJerk > 1200) { jerks = 0; firstJerk = now; }
                    jerks++;
                    lastJerk = now;
                    if (jerks >= 3) { jerks = 0; motion("M:shake"); }
                }
                // переворот: лежал экраном вверх, затем экраном вниз дольше 0,45 с
                if (z > 6f) faceUpSeen = true;
                if (faceUpSeen && z < -7f) {
                    if (faceDownSince == 0) faceDownSince = now;
                    else if (now - faceDownSince > 450) {
                        faceUpSeen = false;
                        faceDownSince = 0;
                        motion("M:flip");
                    }
                } else if (z >= -7f) {
                    faceDownSince = 0;
                }
            } else if (ev.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                boolean near = ev.values[0] < Math.min(5f, proxMax);
                if (proxFirst) {                           // первое показание — просто исходное состояние
                    proxFirst = false;
                    proxNear = near;
                    return;
                }
                if (near == proxNear) return;
                proxNear = near;
                if (near) {
                    proxNearSince = now;
                    main.removeCallbacks(coverTask);
                    main.postDelayed(coverTask, 1000);
                } else {
                    main.removeCallbacks(coverTask);
                    if (now - proxNearSince < 700) motion("M:wave");
                }
            }
        }
    };

    // ================================================================ жесты: что запускает «отложить» и «выключить»

    private List<String> binds(int action) {
        String key = action == 1 ? "g_snooze" : "g_dismiss";
        String def = action == 1 ? "K:25:1,M:wave" : "K:24:L,M:flip";
        String s = cfg.getString(key, def);
        List<String> l = new ArrayList<String>();
        if (s != null) {
            for (String p : s.split(",")) {
                String t = p.trim();
                if (t.length() > 0 && !l.contains(t)) l.add(t);
            }
        }
        return l;
    }

    private void saveBinds(int action, List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        cfg.edit().putString(action == 1 ? "g_snooze" : "g_dismiss", sb.toString()).apply();
    }

    /** Жест принадлежит только одному действию: назначение на «отложить» снимает его с «выключить» и наоборот; 0 — снять совсем. */
    private void assign(String id, int action) {
        for (int a = 1; a <= 2; a++) {
            List<String> l = binds(a);
            l.remove(id);
            if (a == action) l.add(id);
            saveBinds(a, l);
        }
    }

    private int actionFor(String id) {
        if (binds(1).contains(id)) return 1;
        if (binds(2).contains(id)) return 2;
        return 0;
    }

    private void gesture(String id) {
        if (learnTarget != 0) {
            int t = learnTarget;
            learnTarget = 0;
            main.removeCallbacks(learnTimeout);
            assign(id, t);
            speak("Записано");
            refreshGestUi();
            return;
        }
        if (!ringing) return;
        int a = actionFor(id);
        if (a == 1) doSnooze();
        else if (a == 2) doDismiss();
    }

    private void startLearn(int target) {
        main.removeCallbacks(learnTimeout);
        learnTarget = target;
        main.postDelayed(learnTimeout, LEARN_MS);
        speak("Нажмите кнопку");
        refreshGestUi();
    }

    private final Runnable learnTimeout = new Runnable() {
        @Override public void run() {
            if (learnTarget == 0) return;
            learnTarget = 0;
            speak("Время вышло");
            refreshGestUi();
        }
    };

    private static String keyLabel(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: return "🔊 Громкость +";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "🔉 Громкость −";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "🎧 Гарнитура ▶❚";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "⏭ Далее";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "⏮ Назад";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "▶ Play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "⏸ Pause";
            case KeyEvent.KEYCODE_MEDIA_STOP: return "⏹ Stop";
            default: break;
        }
        String n = KeyEvent.keyCodeToString(code);
        if (n.startsWith("KEYCODE_")) n = n.substring(8);
        boolean pad = n.startsWith("BUTTON_") || n.startsWith("DPAD");
        if (n.startsWith("BUTTON_")) n = n.substring(7);
        return (pad ? "🎮 " : "⌨ ") + n.replace('_', ' ');
    }

    private static String gestureLabel(String id) {
        if ("M:shake".equals(id)) return "🫨 Встряхнуть";
        if ("M:flip".equals(id)) return "🙃 Экраном вниз";
        if ("M:wave".equals(id)) return "👋 Взмах над датчиком";
        if ("M:cover".equals(id)) return "✋ Закрыть датчик";
        if (id.startsWith("K:")) {
            String[] p = id.split(":");
            if (p.length == 3) {
                try {
                    String pat = "L".equals(p[2]) ? "держать" : p[2] + "×";
                    return keyLabel(Integer.parseInt(p[1])) + " · " + pat;
                } catch (NumberFormatException ignored) { }
            }
        }
        return id;
    }

    // ================================================================ действия

    private static String minWord(int n) {
        int a = n % 100, b = n % 10;
        if (a >= 11 && a <= 14) return "минут";
        if (b == 1) return "минуту";
        if (b >= 2 && b <= 4) return "минуты";
        return "минут";
    }

    private void doSnooze() {
        if (!ringing) return;
        int max = cfg.getInt("snooze_max", 0);
        int n = cfg.getInt("snooze_n", 0);
        if (max > 0 && n >= max) {
            speak("Откладывать больше нельзя");
            return;
        }
        int min = clamp(cfg.getInt("snooze_min", 10), 1, 30);
        stopRinging();
        cfg.edit().putInt("snooze_n", n + 1).putLong("snooze_at", System.currentTimeMillis() + min * 60000L).apply();
        reschedule();
        speak("Отложено на " + min + " " + minWord(min));
    }

    private void doDismiss() {
        if (!ringing) return;
        stopRinging();
        cfg.edit().putInt("snooze_n", 0).putLong("snooze_at", 0).apply();
        reschedule();
        speak("Будильник выключен");
    }

    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object c = data.get("command");
        if (!(c instanceof String)) return null;
        String cmd = (String) c;
        if (cfg == null) return null;
        if ("snooze".equals(cmd)) { doSnooze(); return Boolean.valueOf(ringing); }
        if ("dismiss".equals(cmd)) { doDismiss(); return Boolean.valueOf(ringing); }
        if ("status".equals(cmd)) return ringing ? "ringing" : "idle";
        if ("ring".equals(cmd) && active) { startRing(); return Boolean.TRUE; }
        return null;
    }

    // ================================================================ озвучка действий (вместо звуковых сигналов)

    private void speak(final String text) {
        if (cfg == null || ctx == null || main == null || !cfg.getBoolean("speak", true)) return;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (tts != null && ttsReady) { sayNow(text); return; }
                    pendingSpeech = text;
                    if (tts == null) {
                        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                            @Override public void onInit(final int status) {
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        ttsReady = status == TextToSpeech.SUCCESS;
                                        if (!ttsReady) { shutdownTts(); return; }
                                        try {
                                            tts.setLanguage(new Locale("ru"));
                                            tts.setAudioAttributes(new AudioAttributes.Builder()
                                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                                            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                                                @Override public void onStart(String id) { }
                                                @Override public void onError(String id) { idleShutdown(); }
                                                @Override public void onDone(String id) { idleShutdown(); }
                                            });
                                        } catch (Throwable ignored) { }
                                        String p = pendingSpeech;
                                        pendingSpeech = null;
                                        if (p != null) sayNow(p);
                                    }
                                });
                            }
                        });
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private void sayNow(String text) {
        try {
            main.removeCallbacks(ttsIdleTask);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "alarmclock");
        } catch (Throwable ignored) { }
    }

    private final Runnable ttsIdleTask = new Runnable() {
        @Override public void run() { shutdownTts(); }
    };

    private void idleShutdown() {
        if (main != null) {
            main.removeCallbacks(ttsIdleTask);
            main.postDelayed(ttsIdleTask, 2500);
        }
    }

    private void shutdownTts() {
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        tts = null;
        ttsReady = false;
        pendingSpeech = null;
    }

    // ================================================================ настройки

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("⏰ Будильник"));
        l.add(SettingItem.slider("snooze_min", "💤 Отложить на", "мин", 1, 30, 1, 10));
        l.add(SettingItem.slider("snooze_max", "🔁 Сколько раз можно откладывать", "(0 — без ограничений)", 0, 10, 1, 0));
        l.add(SettingItem.slider("ring_timeout", "⏱ Звонить не дольше", "мин", 1, 30, 1, 10));
        l.add(SettingItem.section("🔊 Звук"));
        List<String> sounds = new ArrayList<String>();
        sounds.add("Стандартная будильника");
        sounds.add("Стандартный звонок");
        sounds.add("Свой файл");
        l.add(SettingItem.choice("ring_sound", "🎵 Мелодия", "", sounds, 0));
        l.add(SettingItem.text("ring_file", "📂 Путь к файлу мелодии", "Например /storage/emulated/0/Music/alarm.mp3", ""));
        l.add(SettingItem.slider("ring_volume", "🔊 Громкость будильника", "% (0 — как в системе)", 0, 100, 5, 0));
        l.add(SettingItem.slider("ring_ramp", "📈 Нарастание громкости", "сек (0 — сразу)", 0, 60, 1, 10));
        l.add(SettingItem.toggle("ring_vibrate", "📳 Вибрация", "", true));
        l.add(SettingItem.toggle("speak", "🗣 Озвучивать действия", "«Отложено на 10 минут», «Будильник выключен»", true));
        l.add(SettingItem.section("👋 Жесты без экрана"));
        l.add(SettingItem.slider("shake_thr", "🫨 Сила встряхивания", "×0,1 g (меньше — чувствительнее)", 15, 40, 1, 22));
        l.add(SettingItem.custom("gestures", "Жесты", "Что откладывает и что выключает будильник"));
        l.add(SettingItem.action("test_ring", "▶ Проверить звонок", "Экран при этом не включается"));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (cfg == null) return;
        if ("test_ring".equals(key)) {
            if (active) startRing();
        } else if ("ring_vibrate".equals(key) || "ring_sound".equals(key)) {
            // применяется при следующем звонке
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
        p.setStrokeWidth(5f * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(48 * s, 52 * s, 28 * s, p);                               // циферблат
        c.drawLine(48 * s, 52 * s, 48 * s, 36 * s, p);                         // стрелки
        c.drawLine(48 * s, 52 * s, 60 * s, 58 * s, p);
        c.drawArc(new RectF(10 * s, 8 * s, 38 * s, 36 * s), 180f, 90f, false, p);   // «уши» звонков
        c.drawArc(new RectF(58 * s, 8 * s, 86 * s, 36 * s), 270f, 90f, false, p);
        c.drawLine(30 * s, 82 * s, 24 * s, 90 * s, p);                         // ножки
        c.drawLine(66 * s, 82 * s, 72 * s, 90 * s, p);
        return b;
    }

    // ================================================================ общие элементы вида

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static TextView tv(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Кнопка-«таблетка»: белая рамка на чёрном, выбранная — белая заливка с чёрным текстом (читается и на e-ink). */
    private static TextView chip(Context c, String text, boolean filled, boolean dim, View.OnClickListener l) {
        TextView t = tv(c, text, 15f, filled ? Color.BLACK : Color.WHITE, false);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 18));
        g.setColor(filled ? Color.WHITE : Color.BLACK);
        g.setStroke(dp(c, 1.5f), Color.WHITE);
        t.setBackground(g);
        t.setPadding(dp(c, 14), dp(c, 9), dp(c, 14), dp(c, 9));
        t.setGravity(Gravity.CENTER);
        t.setAlpha(dim ? 0.45f : 1f);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** Строки с переносом: дети раскладываются слева направо, не помещаются — на следующую строку. */
    private static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec);
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += cw + gap;
                rowH = Math.max(rowH, ch);
            }
            setMeasuredDimension(maxW, y + rowH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l;
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                v.layout(x, y, x + cw, y + ch);
                x += cw + gap;
                rowH = Math.max(rowH, ch);
            }
        }
    }

    /** Выключатель: контур, внутри кружок слева (выкл.) или справа на белой заливке (вкл.). */
    private static final class Pill extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        Pill(Context c) { super(c); }

        void set(boolean on) { this.on = on; invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 56), dp(getContext(), 30));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), sw = dp(getContext(), 1.5f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Color.WHITE : Color.BLACK);
            c.drawRoundRect(new RectF(sw, sw, w - sw, h - sw), h / 2f, h / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(sw, sw, w - sw, h - sw), h / 2f, h / 2f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Color.BLACK : Color.WHITE);
            float r = h / 2f - sw * 3f;
            c.drawCircle(on ? w - h / 2f : h / 2f, h / 2f, r, p);
        }
    }

    private void refreshUi() {
        Runnable r = uiRefresh;
        if (r != null && main != null) main.post(r);
    }

    private void refreshGestUi() {
        Runnable r = gestRefresh;
        if (r != null && main != null) main.post(r);
    }

    // ================================================================ главное окно: список будильников

    private static String hhmm(int h, int m) {
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private static String daysLabel(int d) {
        if (d == 0) return "Один раз";
        if (d == 127) return "Каждый день";
        if (d == 31) return "Пн–Пт";
        if (d == 96) return "Сб, Вс";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((d & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(DAY_NAMES[i]);
            }
        }
        return sb.toString();
    }

    private String nextSummary() {
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        for (Alarm a : loadAlarms()) {
            if (a.on) best = Math.min(best, nextTrigger(a, now));
        }
        long sn = cfg.getLong("snooze_at", 0);
        boolean snoozed = sn > now && sn < best;
        if (snoozed) best = sn;
        if (best == Long.MAX_VALUE) return "Будильники выключены";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(best);
        long mins = Math.max(0, (best - now + 59999) / 60000);
        String left = (mins / 60 > 0 ? (mins / 60) + " ч " : "") + (mins % 60) + " мин";
        return (snoozed ? "💤 " : "⏰ ") + hhmm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + " · через " + left;
    }

    @Override
    public View createMainView(final Context context) {
        ensureBase(context);
        final ScrollView sv = new ScrollView(context);
        sv.setBackgroundColor(Color.BLACK);
        final LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(context, 20), dp(context, 24), dp(context, 20), dp(context, 40));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        final Runnable build = new Runnable() {
            @Override public void run() { buildMain(context, col); }
        };
        uiRefresh = build;
        build.run();
        sv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { uiRefresh = build; }
            @Override public void onViewDetachedFromWindow(View v) { if (uiRefresh == build) uiRefresh = null; }
        });
        return sv;
    }

    private void changed(List<Alarm> l) {
        saveAlarms(l);
        reschedule();       // перерисует и окно
    }

    private void buildMain(final Context c, LinearLayout col) {
        col.removeAllViews();

        if (ringing) {
            TextView t = tv(c, "⏰ Звонит", 30f, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            col.addView(t, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(c, 14);
            rl.bottomMargin = dp(c, 22);
            TextView sn = chip(c, "💤 Отложить", false, false, new View.OnClickListener() {
                @Override public void onClick(View v) { doSnooze(); }
            });
            TextView di = chip(c, "⏹ Выключить", true, false, new View.OnClickListener() {
                @Override public void onClick(View v) { doDismiss(); }
            });
            sn.setTextSize(22f);
            di.setTextSize(22f);
            sn.setPadding(dp(c, 14), dp(c, 22), dp(c, 14), dp(c, 22));
            di.setPadding(dp(c, 14), dp(c, 22), dp(c, 14), dp(c, 22));
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
            a.rightMargin = dp(c, 8);
            row.addView(sn, a);
            row.addView(di, new LinearLayout.LayoutParams(0, -2, 1f));
            col.addView(row, rl);
        }

        TextView next = tv(c, nextSummary(), 20f, 0xFFCCCCCC, false);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.bottomMargin = dp(c, 18);
        col.addView(next, nl);

        final List<Alarm> all = loadAlarms();
        for (final Alarm a : all) {
            col.addView(alarmRow(c, a, all), new LinearLayout.LayoutParams(-1, -2));
            if (a.id == expandedId) col.addView(alarmEditor(c, a, all), new LinearLayout.LayoutParams(-1, -2));
            View line = new View(c);
            line.setBackgroundColor(0xFF444444);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(c, 1));
            ll.topMargin = dp(c, 10);
            ll.bottomMargin = dp(c, 10);
            col.addView(line, ll);
        }

        TextView add = chip(c, "➕", false, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<Alarm> l = loadAlarms();
                int id = 1;
                for (Alarm x : l) id = Math.max(id, x.id + 1);
                Alarm n = new Alarm();
                n.id = id; n.h = 7; n.m = 0; n.days = 0; n.on = true;
                l.add(n);
                expandedId = id;
                changed(l);
            }
        });
        add.setTextSize(24f);
        add.setContentDescription("Добавить будильник");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.topMargin = dp(c, 6);
        col.addView(add, al);
    }

    private View alarmRow(final Context c, final Alarm a, final List<Alarm> all) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClickable(true);
        TextView time = tv(c, hhmm(a.h, a.m), 40f, a.on ? Color.WHITE : 0xFF777777, false);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(time);
        left.addView(tv(c, daysLabel(a.days), 15f, 0xFFAAAAAA, false));
        left.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expandedId = expandedId == a.id ? -1 : a.id;
                refreshUi();
            }
        });
        left.setContentDescription("Будильник " + hhmm(a.h, a.m) + ", " + daysLabel(a.days) + (a.on ? ", включён" : ", выключен"));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        final Pill pill = new Pill(c);
        pill.set(a.on);
        pill.setClickable(true);
        pill.setContentDescription(a.on ? "Выключить будильник" : "Включить будильник");
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.on = !a.on;
                changed(all);
            }
        });
        row.addView(pill);
        return row;
    }

    private View alarmEditor(final Context c, final Alarm a, final List<Alarm> all) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 10), 0, 0);

        // часы
        LinearLayout hr = new LinearLayout(c);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        hr.addView(stepChip(c, "−1 ч", a, all, 60 * -1));
        TextView hv = tv(c, String.format(Locale.US, "%02d", a.h), 26f, Color.WHITE, true);
        hv.setGravity(Gravity.CENTER);
        hr.addView(hv, new LinearLayout.LayoutParams(dp(c, 56), -2));
        hr.addView(stepChip(c, "+1 ч", a, all, 60));
        box.addView(hr);

        // минуты
        LinearLayout mr = new LinearLayout(c);
        mr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(c, 8);
        mr.addView(stepChip(c, "−5", a, all, -5));
        mr.addView(stepChip(c, "−1", a, all, -1));
        TextView mv = tv(c, String.format(Locale.US, "%02d", a.m), 26f, Color.WHITE, true);
        mv.setGravity(Gravity.CENTER);
        mr.addView(mv, new LinearLayout.LayoutParams(dp(c, 56), -2));
        mr.addView(stepChip(c, "+1", a, all, 1));
        mr.addView(stepChip(c, "+5", a, all, 5));
        box.addView(mr, ml);

        // дни недели
        Flow days = new Flow(c, dp(c, 6));
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            days.addView(chip(c, DAY_NAMES[i], (a.days & bit) != 0, false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    a.days ^= bit;
                    changed(all);
                }
            }));
        }
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = dp(c, 10);
        box.addView(days, dl);

        TextView del = chip(c, "🗑 Удалить", false, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                all.remove(a);
                expandedId = -1;
                changed(all);
            }
        });
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(-2, -2);
        xl.topMargin = dp(c, 12);
        box.addView(del, xl);
        return box;
    }

    /** Кнопка «сдвинуть время на delta минут» (час прокручивается по кругу, минуты тоже, не затрагивая час). */
    private TextView stepChip(Context c, String label, final Alarm a, final List<Alarm> all, final int deltaMin) {
        TextView t = chip(c, label, false, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Math.abs(deltaMin) == 60) a.h = (a.h + (deltaMin > 0 ? 1 : 23)) % 24;
                else a.m = ((a.m + deltaMin) % 60 + 60) % 60;
                changed(all);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    // ================================================================ экран настроек: жесты

    @Override
    public View createSettingsView(final Context context, String key) {
        ensureBase(context);
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6));
        final Runnable build = new Runnable() {
            @Override public void run() { buildGestures(context, root); }
        };
        gestRefresh = build;
        build.run();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { gestRefresh = build; }
            @Override public void onViewDetachedFromWindow(View v) { if (gestRefresh == build) gestRefresh = null; }
        });
        return root;
    }

    private void buildGestures(Context c, LinearLayout root) {
        root.removeAllViews();
        TextView hint = tv(c, "Экран при звонке не включается. Жесты работают при выключенном экране. "
                + "Нажатие на выбранный жест снимает его; жест принадлежит только одному действию.", 13f, 0xFFAAAAAA, false);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        for (int action = 1; action <= 2; action++) {
            gestureBlock(c, root, action);
        }
    }

    private void gestureBlock(final Context c, LinearLayout root, final int action) {
        TextView title = tv(c, action == 1 ? "💤 Отложить" : "⏹ Выключить", 20f, Color.WHITE, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(c, 18);
        tl.bottomMargin = dp(c, 8);
        root.addView(title, tl);

        Flow flow = new Flow(c, dp(c, 8));
        // кнопки, записанные для этого действия
        for (final String id : binds(action)) {
            if (id.startsWith("M:")) continue;
            flow.addView(chip(c, "✕ " + gestureLabel(id), true, false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    assign(id, 0);
                    refreshGestUi();
                }
            }));
        }
        boolean learning = learnTarget == action;
        flow.addView(chip(c, learning ? "🔴 Нажмите кнопку…" : "➕ Нажать кнопку", learning, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (learnTarget == action) {
                    learnTarget = 0;
                    main.removeCallbacks(learnTimeout);
                    refreshGestUi();
                } else {
                    startLearn(action);
                }
            }
        }));
        // движения
        for (final String id : MOTION) {
            int owner = actionFor(id);
            boolean mine = owner == action;
            flow.addView(chip(c, gestureLabel(id), mine, owner != 0 && !mine, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    assign(id, actionFor(id) == action ? 0 : action);
                    refreshGestUi();
                }
            }));
        }
        root.addView(flow, new LinearLayout.LayoutParams(-1, -2));
    }
}
