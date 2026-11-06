package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.IShareHandler;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.YtAudioBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Скачивание аудиодорожки YouTube на нужном языке (по умолчанию русская).
 *
 * Запускается кнопкой 🎧 в скрипте YouTube TTS: скрипт шлёт «Поделиться» с типом text/x-ytaudio (ссылка в тексте,
 * название в теме), хост передаёт его сюда. Файл и превью (.jpg с тем же именем) попадают в папку Music/YouTube;
 * плеер показывает превью плитками. Саму загрузку делает хост (YtAudioBridge, библиотека NewPipeExtractor).
 * Если нужной дорожки нет, модуль об этом говорит (оригинал качается только при включённой настройке).
 */
public class YouTubeAudio implements IPlugin, ISettingsProvider, IShareHandler {

    private static final String PREFS = "module_YouTube аудио";
    static final String MIME = "text/x-ytaudio";

    private static final String KEY_PENDING = "pending";

    private Context appCtx;
    private Handler h;
    private boolean loaded;
    private ConnectivityManager.NetworkCallback wifiCb;
    /** Загрузки, ждущие Wi-Fi: {ссылка, название}. */
    private final List<String[]> pending = new ArrayList<String[]>();

    @Override public String getName() { return "YouTube аудио"; }
    @Override public int getVersion() { return 2; }
    @Override public String getDescription() {
        return "Скачивает русскую аудиодорожку YouTube по кнопке 🎧 из скрипта YouTube TTS; превью видно в плеере.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        h = new Handler(Looper.getMainLooper());
        loadPending();
    }

    @Override public void stop() { unwatchWifi(); }
    @Override public Object execute(Map<String, ?> data) { return null; }
    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

    private void ensure(Context c) {
        if (appCtx == null) appCtx = c.getApplicationContext();
        if (h == null) h = new Handler(Looper.getMainLooper());
        loadPending();
    }

    private SharedPreferences prefs() {
        return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void toast(final String s) {
        if (h == null || appCtx == null) return;
        h.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(appCtx, s, Toast.LENGTH_LONG).show(); } catch (Throwable t) { }
            }
        });
    }

    private static boolean isYoutube(String u) {
        if (u == null) return false;
        String s = u.toLowerCase();
        return s.startsWith("http") && (s.contains("youtu.be/") || s.contains("youtube.com/"));
    }

    // ---------------------------------------------------------- приём «Поделиться»

    @Override public List<String> shareMimeTypes() {
        List<String> r = new ArrayList<String>();
        r.add(MIME);
        return r;
    }

    @Override public boolean onShare(Context ctx, Intent intent) {
        try {
            ensure(ctx);
            String url = intent.getStringExtra(Intent.EXTRA_TEXT);
            String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (url != null) url = url.trim();
            if (!isYoutube(url)) {
                toast("Это не ссылка на YouTube");
                return false;
            }
            submit(url, title);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String outDir() {
        String rel = prefs().getString("out_dir", "Music/YouTube");
        if (rel == null || rel.trim().length() == 0) rel = "Music/YouTube";
        File root = Environment.getExternalStorageDirectory();
        return new File(root, rel.trim()).getAbsolutePath();
    }

    // ---------------------------------------------------------- ожидание Wi-Fi

    private boolean isWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Качает сразу или, если включено «только по Wi-Fi» и его нет, ставит в очередь. */
    private void submit(String url, String title) {
        if (prefs().getBoolean("wifi_only", false) && !isWifi()) {
            int n = addPending(url, title);
            toast("📶 Нет Wi-Fi: скачаю, когда появится (в очереди: " + n + ")");
            watchWifi();
            return;
        }
        startDownload(url, title);
    }

    private synchronized int addPending(String url, String title) {
        for (String[] p : pending) if (p[0].equals(url)) return pending.size();
        pending.add(new String[]{url, title == null ? "" : title});
        savePending();
        return pending.size();
    }

    private synchronized void savePending() {
        try {
            JSONArray a = new JSONArray();
            for (String[] p : pending) a.put(new JSONObject().put("u", p[0]).put("t", p[1]));
            prefs().edit().putString(KEY_PENDING, a.toString()).apply();
        } catch (Throwable t) { }
    }

    /** После перезапуска очередь читается из настроек; если Wi-Fi уже есть, она сразу уходит в загрузку. */
    private synchronized void loadPending() {
        if (loaded || appCtx == null) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(prefs().getString(KEY_PENDING, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                pending.add(new String[]{o.getString("u"), o.optString("t", "")});
            }
        } catch (Throwable t) { }
        if (pending.isEmpty()) return;
        if (isWifi()) drainPending(); else watchWifi();
    }

    /** Запускает всё, что ждало, и выключает наблюдение за сетью. */
    private synchronized void drainPending() {
        unwatchWifi();
        List<String[]> copy = new ArrayList<String[]>(pending);
        pending.clear();
        savePending();
        for (String[] p : copy) startDownload(p[0], p[1].length() == 0 ? null : p[1]);
    }

    private synchronized void watchWifi() {
        if (wifiCb != null || appCtx == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest rq = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            wifiCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    h.postDelayed(new Runnable() {
                        @Override public void run() { drainPending(); }
                    }, 1500);   // даём сети стать основной
                }
            };
            cm.registerNetworkCallback(rq, wifiCb);
        } catch (Throwable t) {
            wifiCb = null;
        }
    }

    private synchronized void unwatchWifi() {
        if (wifiCb == null || appCtx == null) return;
        try {
            ((ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(wifiCb);
        } catch (Throwable t) { }
        wifiCb = null;
    }

    private void startDownload(String url, String title) {
        SharedPreferences p = prefs();
        String lang = p.getString("lang", "ru");
        boolean fb = p.getBoolean("fallback_original", false);
        toast("🎧 Начинаю: " + (title == null || title.length() == 0 ? url : title));
        YtAudioBridge.start(appCtx, url, title, lang, outDir(), fb, new YtAudioBridge.Listener() {
            @Override public void onStatus(String text) { toast(text); }
            @Override public void onDone(String audioPath, String error) {
                if (error != null) toast("🎧 " + error);
                else toast("✅ Готово: " + new File(audioPath).getName());
            }
        });
    }

    // ---------------------------------------------------------- настройки

    @Override public List<SettingItem> getSettingsSchema() {
        List<SettingItem> r = new ArrayList<SettingItem>();
        r.add(SettingItem.section("🎧 Дорожка"));
        r.add(SettingItem.text("lang", "Язык дорожки", "Код языка: ru, en, uk…", "ru"));
        r.add(SettingItem.toggle("fallback_original", "Оригинал, если нужной нет",
                "Выключено: нет русской дорожки — ничего не качается, модуль скажет об этом", false));
        r.add(SettingItem.toggle("wifi_only", "Только по Wi-Fi",
                "Нет Wi-Fi: ссылка ждёт в очереди и скачается, когда он появится", false));
        r.add(SettingItem.text("out_dir", "Папка", "Внутри памяти телефона; рядом кладётся превью .jpg", "Music/YouTube"));
        r.add(SettingItem.section("🧪 Проверка"));
        r.add(SettingItem.action("force_pending", "▶ Качать очередь сейчас", "Запустить ждущие Wi-Fi загрузки без него"));
        r.add(SettingItem.action("from_clip", "📋 Скачать по ссылке из буфера", "Скопируй ссылку на видео YouTube и нажми"));
        return r;
    }

    @Override public void onSettingChanged(String key) {
        if (!"from_clip".equals(key) && !"force_pending".equals(key)) return;
        try {
            Context c = appCtx;
            if (c == null) return;
            ensure(c);
            if ("force_pending".equals(key)) {
                int n = pending.size();
                toast(n == 0 ? "Очередь пуста" : "Качаю очередь: " + n);
                drainPending();
                return;
            }
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            CharSequence t = (d == null || d.getItemCount() == 0) ? null : d.getItemAt(0).getText();
            String url = t == null ? null : t.toString().trim();
            if (!isYoutube(url)) {
                toast("В буфере нет ссылки на YouTube");
                return;
            }
            submit(url, null);
        } catch (Throwable e) {
            toast("Не вышло: " + e);
        }
    }
}
