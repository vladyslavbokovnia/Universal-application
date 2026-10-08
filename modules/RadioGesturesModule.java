package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.ShizukuBridge;
import im.manus.universalhost.ShizukuResult;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Radio Gestures: жест кнопкой play/pause на Bluetooth-наушнике.
 * Кнопка перехватывается через MediaSession, наушник на короткое время отключается
 * (A2DP + Headset) и подключается обратно. После отключения запускается мониторинг RSSI
 * (BLE-реклама, Classic discovery и dumpsys через Shizuku), график виден в настройках модуля.
 *
 * Требования к хосту: разрешения Bluetooth в манифесте и SettingItem.monitor.
 * Разрешения выдаются действием «Выдать Bluetooth-разрешения».
 *
 * execute({"command": ...}): "gesture" | "rssi" | "log" | "status" | "start_monitor" | "stop_monitor"
 * | "monitor:<ключ>" (снимок для графика). "rssi" вызывать только из фонового потока.
 */
public class RadioGesturesModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_RadioGestures";
    private static final int MAX_LOG = 30;
    private static final int MAX_SAMPLES = 120;
    private static final long PULSE_WINDOW_MS = 4000L;
    private static final long SHIZUKU_POLL_MS = 1500L;

    private Context ctx;
    private Handler main;
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dpProxy;
    private BluetoothProfile headsetProxy;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean receiverRegistered;

    private boolean busy;
    private BluetoothDevice pendingDevice;
    private long lastDisconnectAt;
    private final ArrayDeque<Long> recentDisconnects = new ArrayDeque<Long>();
    private final ArrayDeque<String> logLines = new ArrayDeque<String>();

    // мониторинг RSSI
    private final Object sampleLock = new Object();
    private final ArrayDeque<Integer> samples = new ArrayDeque<Integer>();
    private boolean hasBaseline;
    private int baseline;
    private String lastSource = "";
    private long lastSampleAt;
    private volatile boolean monitoring;
    private volatile int monitorGen;
    private volatile long monitorEndsAt;        // 0 — без ограничения
    private volatile String monitorAddress;
    private volatile String monitorNote = "";
    private BluetoothLeScanner bleScanner;

    @Override public String getName() { return "RadioGestures"; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() {
        return "Жест кнопкой play/pause на наушнике: отключение и возврат, лог подключений, график RSSI";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    public void init(Context context) {
        Context app = context.getApplicationContext();
        ctx = app != null ? app : context;
        main = new Handler(Looper.getMainLooper());
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        try { registerReceivers(); } catch (Throwable ignored) { }
        try { openProfiles(); } catch (Throwable ignored) { }
        try { setupSession(); } catch (Throwable ignored) { }
    }

    @Override
    public void stop() {
        // если остановили посреди жеста, вернуть наушник сразу
        if (busy && pendingDevice != null) {
            try { connect(pendingDevice); } catch (Throwable ignored) { }
        }
        busy = false;
        pendingDevice = null;
        stopMonitor();
        if (main != null) main.removeCallbacksAndMessages(null);
        Context c = ctx;
        if (receiverRegistered && c != null) {
            try { c.unregisterReceiver(btReceiver); } catch (Throwable ignored) { }
        }
        receiverRegistered = false;
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
            }
        } catch (Throwable ignored) { }
        session = null;
        try {
            if (audio != null && focusRequest != null && Build.VERSION.SDK_INT >= 26) {
                audio.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Throwable ignored) { }
        focusRequest = null;
        try {
            if (adapter != null) {
                if (a2dpProxy != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
                if (headsetProxy != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy);
            }
        } catch (Throwable ignored) { }
        a2dpProxy = null;
        headsetProxy = null;
        ctx = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) { }

    // ------------------------------------------------------------------ команды

    @Override
    public Object execute(Map<String, ?> data) {
        if (data == null) return null;
        Object cmd = data.get("command");
        if (!(cmd instanceof String)) return null;
        String c = (String) cmd;
        if (c.startsWith("monitor")) return monitorSnapshot();
        if ("gesture".equals(c)) {
            if (main != null) main.post(new Runnable() {
                @Override public void run() { triggerGesture(); }
            });
            return Boolean.TRUE;
        }
        if ("start_monitor".equals(c)) return startMonitorManual();
        if ("stop_monitor".equals(c)) { stopMonitor(); return Boolean.TRUE; }
        if ("rssi".equals(c)) return readRssi();
        if ("log".equals(c)) return logText();
        if ("status".equals(c)) return statusText();
        return null;
    }

    // ------------------------------------------------------------------ MediaSession

    private void setupSession() {
        Context c = ctx;
        if (c == null) return;
        MediaSession s = new MediaSession(c, "RadioGesturesSession");
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        s.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { onGesture(); }
            @Override public void onPause() { onGesture(); }
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent e = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (e != null && e.getAction() == KeyEvent.ACTION_DOWN
                        && e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    onGesture();
                    return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        }, main);
        PlaybackState st = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build();
        s.setPlaybackState(st);
        s.setActive(true);
        session = s;
        requestAudioFocus(c);
    }

    private void requestAudioFocus(Context c) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        focusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override public void onAudioFocusChange(int focusChange) { }
        };
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void onGesture() {
        Context c = ctx;
        if (c == null) return;
        if (!prefs(c).getBoolean("gesture_enabled", true)) return;
        triggerGesture();
    }

    // ------------------------------------------------------------------ жест: отключить и вернуть

    private void triggerGesture() {
        final Context c = ctx;
        if (c == null || adapter == null) return;
        if (busy) return;
        BluetoothDevice device = pickDevice(c);
        if (device == null) {
            toast("Жест пойман, но устройство не выбрано и ничего не подключено");
            return;
        }
        if (!disconnect(device)) {
            toast("Не удалось отключить: профили не готовы или нет разрешения Bluetooth");
            return;
        }
        busy = true;
        pendingDevice = device;
        toast("Жест: отключаю наушник");
        SharedPreferences p = prefs(c);
        if (p.getBoolean("monitor_after_gesture", true)) {
            startMonitor(device.getAddress(), p.getInt("monitor_seconds", 30) * 1000L);
        }
        long delay = p.getInt("reconnect_delay", 2500);
        if (delay < 300) delay = 300;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                BluetoothDevice d = pendingDevice;
                boolean ok = d != null && connect(d);
                busy = false;
                pendingDevice = null;
                toast(ok ? "Подключаю обратно…" : "Не удалось переподключить, подключите вручную");
            }
        }, delay);
    }

    private BluetoothDevice pickDevice(Context c) {
        SharedPreferences p = prefs(c);
        String saved = p.getString("device_address", "");
        int idx = p.getInt("device", 0);
        try {
            if (idx > 0 && saved != null && saved.length() > 0) return adapter.getRemoteDevice(saved);
            List<BluetoothDevice> now = connectedDevices();
            if (!now.isEmpty()) return now.get(0);
            if (saved != null && saved.length() > 0) return adapter.getRemoteDevice(saved);
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ профили Bluetooth

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy;
            else if (profile == BluetoothProfile.HEADSET) headsetProxy = proxy;
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = null;
            else if (profile == BluetoothProfile.HEADSET) headsetProxy = null;
        }
    };

    private void openProfiles() {
        Context c = ctx;
        if (c == null || adapter == null) return;
        adapter.getProfileProxy(c, profileListener, BluetoothProfile.A2DP);
        adapter.getProfileProxy(c, profileListener, BluetoothProfile.HEADSET);
    }

    /** disconnect/connect скрыты в SDK, вызываем через рефлексию. На новых Android могут быть закрыты. */
    private static boolean callHidden(Object target, String method, BluetoothDevice device) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method, BluetoothDevice.class);
            m.setAccessible(true);
            Object r = m.invoke(target, device);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean disconnect(BluetoothDevice d) {
        boolean ok = false;
        if (a2dpProxy != null) ok = callHidden(a2dpProxy, "disconnect", d) || ok;
        if (headsetProxy != null) ok = callHidden(headsetProxy, "disconnect", d) || ok;
        return ok;
    }

    private boolean connect(BluetoothDevice d) {
        boolean ok = false;
        if (a2dpProxy != null) ok = callHidden(a2dpProxy, "connect", d) || ok;
        if (headsetProxy != null) ok = callHidden(headsetProxy, "connect", d) || ok;
        return ok;
    }

    private List<BluetoothDevice> connectedDevices() {
        Set<BluetoothDevice> set = new LinkedHashSet<BluetoothDevice>();
        try { if (a2dpProxy != null) set.addAll(a2dpProxy.getConnectedDevices()); } catch (Throwable ignored) { }
        try { if (headsetProxy != null) set.addAll(headsetProxy.getConnectedDevices()); } catch (Throwable ignored) { }
        return new ArrayList<BluetoothDevice>(set);
    }

    // ------------------------------------------------------------------ приёмник: ACL-события и Classic discovery

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    // пока идёт мониторинг, discovery перезапускаем
                    if (monitoring && adapter != null) {
                        try { adapter.startDiscovery(); } catch (Throwable ignored) { }
                    }
                    return;
                }
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    logAcl(d.getAddress(), false);
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    logAcl(d.getAddress(), true);
                } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    String a = monitorAddress;
                    if (monitoring && a != null && rssi != Short.MIN_VALUE && a.equalsIgnoreCase(d.getAddress())) {
                        addSample(rssi, "Classic");
                    }
                }
            } catch (Throwable ignored) { }
        }
    };

    private void registerReceivers() {
        Context c = ctx;
        if (c == null || receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            c.registerReceiver(btReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            c.registerReceiver(btReceiver, f);
        }
        receiverRegistered = true;
    }

    // ------------------------------------------------------------------ лог ACL-событий

    private synchronized void logAcl(String address, boolean connected) {
        Context c = ctx;
        if (c == null) return;
        String wanted = prefs(c).getString("device_address", "");
        if (wanted != null && wanted.length() > 0 && !address.equalsIgnoreCase(wanted)) return;
        long now = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(now));
        String line;
        if (!connected) {
            recentDisconnects.addLast(now);
            while (!recentDisconnects.isEmpty() && now - recentDisconnects.peekFirst() > PULSE_WINDOW_MS) {
                recentDisconnects.removeFirst();
            }
            lastDisconnectAt = now;
            line = time + "  ОТКЛЮЧЕНИЕ  (разрывов за " + (PULSE_WINDOW_MS / 1000) + "с: " + recentDisconnects.size() + ")";
        } else {
            long gap = lastDisconnectAt > 0 ? now - lastDisconnectAt : -1L;
            line = (gap >= 0 && gap <= 60000L)
                    ? time + "  ПОДКЛЮЧЕНИЕ  (был отключён " + gap + " мс)"
                    : time + "  ПОДКЛЮЧЕНИЕ";
        }
        logLines.addFirst(line);
        while (logLines.size() > MAX_LOG) logLines.removeLast();
    }

    private synchronized String logText() {
        return lastLines(MAX_LOG);
    }

    private synchronized String lastLines(int n) {
        if (logLines.isEmpty()) return "Событий пока нет";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : logLines) {
            if (i++ >= n) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ мониторинг RSSI

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { onBle(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) onBle(r);
        }
        @Override public void onScanFailed(int errorCode) { monitorNote = "BLE: ошибка " + errorCode; }
    };

    private void onBle(ScanResult r) {
        String a = monitorAddress;
        if (monitoring && a != null && r != null && r.getDevice() != null
                && a.equalsIgnoreCase(r.getDevice().getAddress())) {
            addSample(r.getRssi(), "BLE");
        }
    }

    private void addSample(int rssi, String source) {
        if (!monitoring) return;
        synchronized (sampleLock) {
            if (samples.size() >= MAX_SAMPLES) samples.removeFirst();
            samples.addLast(rssi);
            if (!hasBaseline) {
                baseline = rssi;
                hasBaseline = true;
            }
            lastSource = source;
            lastSampleAt = System.currentTimeMillis();
        }
    }

    /** Запуск мониторинга для адреса; durationMs = 0 — без ограничения по времени. */
    private void startMonitor(String address, long durationMs) {
        if (adapter == null || address == null) return;
        stopMonitor();
        synchronized (sampleLock) {
            samples.clear();
            hasBaseline = false;
            lastSource = "";
            lastSampleAt = 0L;
        }
        monitorAddress = address;
        monitorEndsAt = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0L;
        monitorNote = "";
        monitoring = true;
        final int gen = ++monitorGen;
        startScans();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { monitorLoop(gen); }
        }, "RadioGesturesMonitor");
        t.setDaemon(true);
        t.start();
    }

    private void stopMonitor() {
        monitoring = false;
        monitorGen++;
        stopScans();
    }

    private void monitorLoop(int gen) {
        while (monitoring && gen == monitorGen) {
            long end = monitorEndsAt;
            if (end > 0 && System.currentTimeMillis() > end) {
                if (gen == monitorGen) stopMonitor();
                break;
            }
            pollShizuku();
            try { Thread.sleep(SHIZUKU_POLL_MS); } catch (InterruptedException e) { break; }
        }
    }

    private void pollShizuku() {
        String a = monitorAddress;
        if (a == null || !ShizukuBridge.hasPermission()) return;
        ShizukuResult r = ShizukuBridge.exec("dumpsys bluetooth_manager");
        if (!r.getOk()) return;
        Integer v = parseRssi(r.getOut(), a);
        if (v != null) addSample(v, "Shizuku");
    }

    private void startScans() {
        BluetoothAdapter a = adapter;
        if (a == null) return;
        try {
            bleScanner = a.getBluetoothLeScanner();
            if (bleScanner != null) {
                ScanSettings st = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                bleScanner.startScan(null, st, bleCallback);
            }
        } catch (SecurityException e) {
            monitorNote = "нет разрешения BLUETOOTH_SCAN";
        } catch (Throwable t) {
            monitorNote = "BLE: " + t.getClass().getSimpleName();
        }
        try {
            a.cancelDiscovery();
            a.startDiscovery();
        } catch (SecurityException e) {
            monitorNote = "нет разрешения BLUETOOTH_SCAN";
        } catch (Throwable ignored) { }
    }

    private void stopScans() {
        try { if (bleScanner != null) bleScanner.stopScan(bleCallback); } catch (Throwable ignored) { }
        bleScanner = null;
        try { if (adapter != null) adapter.cancelDiscovery(); } catch (Throwable ignored) { }
    }

    private Object startMonitorManual() {
        Context c = ctx;
        if (c == null) return Boolean.FALSE;
        String address = targetAddress(c);
        if (address == null) return Boolean.FALSE;
        startMonitor(address, prefs(c).getInt("monitor_seconds", 30) * 1000L);
        return Boolean.TRUE;
    }

    /** Снимок для графика в настройках: values (int[]), value (String), status (String). */
    private Map<String, Object> monitorSnapshot() {
        int[] vals;
        int last;
        int base;
        String src;
        long at;
        synchronized (sampleLock) {
            vals = new int[samples.size()];
            int i = 0;
            for (Integer v : samples) vals[i++] = v;
            last = vals.length > 0 ? vals[vals.length - 1] : 0;
            base = baseline;
            src = lastSource;
            at = lastSampleAt;
        }
        boolean has = vals.length > 0;
        long now = System.currentTimeMillis();
        StringBuilder st = new StringBuilder();
        if (has) {
            st.append(src).append(" · изменение ")
                    .append(String.format(Locale.US, "%+d", last - base)).append(" dB");
            long age = (now - at) / 1000L;
            if (age >= 3) st.append(" · данных нет ").append(age).append(" с");
        } else if (monitoring) {
            st.append("Ждём первые данные…");
        } else {
            st.append("Мониторинг не запущен: стартует после жеста или по кнопке ниже");
        }
        if (monitoring) {
            long end = monitorEndsAt;
            if (end > 0) st.append("\nИдёт мониторинг, осталось ").append(Math.max(0L, (end - now) / 1000L)).append(" с");
            else st.append("\nИдёт мониторинг");
        } else if (has) {
            st.append("\nМониторинг остановлен");
        }
        String note = monitorNote;
        if (note != null && note.length() > 0) st.append("\n").append(note);

        Map<String, Object> m = new HashMap<String, Object>();
        m.put("values", vals);
        m.put("value", has ? last + " dBm" : "— dBm");
        m.put("status", st.toString());
        return m;
    }

    // ------------------------------------------------------------------ RSSI через Shizuku (разовое чтение)

    private static final Pattern RSSI = Pattern.compile("rssi[^0-9-]{0,10}(-?\\d{1,3})", Pattern.CASE_INSENSITIVE);

    private static Integer parseRssi(String out, String address) {
        int at = out.toLowerCase(Locale.US).indexOf(address.toLowerCase(Locale.US));
        if (at < 0) return null;
        String window = out.substring(at, Math.min(out.length(), at + 400));
        Matcher m = RSSI.matcher(window);
        if (!m.find()) return null;
        try { return Integer.valueOf(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    /** Только из фонового потока. */
    private String readRssi() {
        Context c = ctx;
        if (c == null) return "Модуль остановлен";
        String address = targetAddress(c);
        if (address == null) return "Устройство не выбрано и ничего не подключено";
        ShizukuResult r = ShizukuBridge.exec("dumpsys bluetooth_manager");
        if (!r.getOk()) return "Shizuku: " + r.getErr();
        Integer v = parseRssi(r.getOut(), address);
        return v == null ? "RSSI для " + address + " в dumpsys не найден" : v + " dBm (" + address + ")";
    }

    private String targetAddress(Context c) {
        SharedPreferences p = prefs(c);
        String saved = p.getString("device_address", "");
        if (p.getInt("device", 0) > 0 && saved != null && saved.length() > 0) return saved;
        List<BluetoothDevice> now = connectedDevices();
        if (!now.isEmpty()) return now.get(0).getAddress();
        return (saved != null && saved.length() > 0) ? saved : null;
    }

    private String statusText() {
        String profiles = (a2dpProxy != null || headsetProxy != null)
                ? "профили A2DP/Headset готовы" : "профили не готовы";
        String sess = session != null ? "MediaSession активна" : "MediaSession не создана";
        return profiles + "; " + sess + "; " + (busy ? "идёт жест" : "ожидание")
                + "; " + (monitoring ? "мониторинг идёт" : "мониторинг выключен");
    }

    // ------------------------------------------------------------------ настройки

    private static List<BluetoothDevice> bondedSorted() {
        final List<BluetoothDevice> list = new ArrayList<BluetoothDevice>();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) list.addAll(a.getBondedDevices());
            Collections.sort(list, new Comparator<BluetoothDevice>() {
                @Override public int compare(BluetoothDevice x, BluetoothDevice y) {
                    return label(x).compareToIgnoreCase(label(y));
                }
            });
        } catch (Throwable ignored) { }
        return list;
    }

    private static String label(BluetoothDevice d) {
        String name = null;
        try { name = d.getName(); } catch (Throwable ignored) { }
        return (name != null ? name : "Без имени") + " (" + d.getAddress() + ")";
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<String> options = new ArrayList<String>();
        options.add("Авто: подключённое сейчас");
        for (BluetoothDevice d : bondedSorted()) options.add(label(d));

        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Жест play/pause"));
        l.add(SettingItem.toggle("gesture_enabled", "Жест включён",
                "Кнопка play/pause на наушнике: кратковременное отключение и возврат", true));
        l.add(SettingItem.choice("device", "Устройство",
                "Если список пуст, выдайте Bluetooth-разрешения ниже", options, 0));
        l.add(SettingItem.slider("reconnect_delay", "Пауза до возврата, мс",
                "Сколько наушник остаётся отключённым", 500, 10000, 500, 2500));

        l.add(SettingItem.section("Мониторинг RSSI"));
        l.add(SettingItem.monitor("rssi_monitor", "RSSI в реальном времени",
                "BLE, Classic discovery и Shizuku (dumpsys)"));
        l.add(SettingItem.toggle("monitor_after_gesture", "Мониторить после отключения",
                "Запускать мониторинг в момент отключения наушника жестом", true));
        l.add(SettingItem.slider("monitor_seconds", "Длительность мониторинга, с",
                "Отсчёт от начала мониторинга", 5, 300, 5, 30));
        l.add(SettingItem.action("monitor_start", "Запустить мониторинг", "Для выбранного или подключённого устройства"));
        l.add(SettingItem.action("monitor_stop", "Остановить мониторинг", "Выключит сканирование"));

        l.add(SettingItem.section("Диагностика"));
        l.add(SettingItem.action("test", "Проверить жест сейчас", "Отключит и вернёт наушник"));
        l.add(SettingItem.action("rssi", "Показать RSSI", "Разовое чтение через Shizuku"));
        l.add(SettingItem.action("log", "Показать лог подключений", "Последние события ACL"));
        l.add(SettingItem.action("status", "Состояние модуля", "Профили, MediaSession, мониторинг"));

        l.add(SettingItem.section("Разрешения"));
        l.add(SettingItem.action("grant_bt", "Выдать Bluetooth-разрешения",
                "BLUETOOTH_CONNECT и BLUETOOTH_SCAN через Shizuku, один раз"));
        return l;
    }

    @Override
    public void onSettingChanged(final String key) {
        final Context c = ctx;
        if (c == null) return;
        if ("device".equals(key)) {
            int idx = prefs(c).getInt("device", 0);
            List<BluetoothDevice> bonded = bondedSorted();
            SharedPreferences.Editor e = prefs(c).edit();
            if (idx > 0 && idx - 1 < bonded.size()) e.putString("device_address", bonded.get(idx - 1).getAddress());
            else e.remove("device_address");
            e.apply();
        } else if ("test".equals(key)) {
            triggerGesture();
        } else if ("monitor_start".equals(key)) {
            toast(Boolean.TRUE.equals(startMonitorManual())
                    ? "Мониторинг запущен"
                    : "Устройство не выбрано и ничего не подключено");
        } else if ("monitor_stop".equals(key)) {
            stopMonitor();
            toast("Мониторинг остановлен");
        } else if ("rssi".equals(key)) {
            new Thread(new Runnable() {
                @Override public void run() { toast("RSSI: " + readRssi()); }
            }).start();
        } else if ("log".equals(key)) {
            toast(lastLines(5));
        } else if ("status".equals(key)) {
            toast(statusText());
        } else if ("grant_bt".equals(key)) {
            new Thread(new Runnable() {
                @Override public void run() { grantBluetooth(c); }
            }).start();
        }
    }

    private void grantBluetooth(Context c) {
        String pkg = c.getPackageName();
        String[] perms = Build.VERSION.SDK_INT >= 31
                ? new String[] { "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN" }
                : new String[] { "android.permission.ACCESS_FINE_LOCATION" };
        StringBuilder sb = new StringBuilder();
        for (String perm : perms) {
            ShizukuResult r = ShizukuBridge.exec("pm grant " + pkg + " " + perm);
            if (sb.length() > 0) sb.append('\n');
            sb.append(perm.substring(perm.lastIndexOf('.') + 1)).append(": ")
                    .append(r.getOk() ? "выдано" : r.getErr());
        }
        sb.append("\nПереоткройте экран настроек");
        toast(sb.toString());
    }

    @Override
    public Bitmap createIcon(int sizePx) {
        return null;        // хост нарисует монограмму
    }

    // ------------------------------------------------------------------ утилиты

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void toast(final String text) {
        final Context c = ctx;
        if (c == null || main == null) return;
        main.post(new Runnable() {
            @Override public void run() { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
        });
    }
}
