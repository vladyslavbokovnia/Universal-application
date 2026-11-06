package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.IShareHandler;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.YtAudioBridge;

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

    private Context appCtx;
    private Handler h;

    @Override public String getName() { return "YouTube аудио"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Скачивает русскую аудиодорожку YouTube по кнопке 🎧 из скрипта YouTube TTS; превью видно в плеере.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        h = new Handler(Looper.getMainLooper());
    }

    @Override public void stop() { }
    @Override public Object execute(Map<String, ?> data) { return null; }
    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

    private void ensure(Context c) {
        if (appCtx == null) appCtx = c.getApplicationContext();
        if (h == null) h = new Handler(Looper.getMainLooper());
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
            startDownload(url, title);
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
        r.add(SettingItem.text("out_dir", "Папка", "Внутри памяти телефона; рядом кладётся превью .jpg", "Music/YouTube"));
        r.add(SettingItem.section("🧪 Проверка"));
        r.add(SettingItem.action("from_clip", "📋 Скачать по ссылке из буфера", "Скопируй ссылку на видео YouTube и нажми"));
        return r;
    }

    @Override public void onSettingChanged(String key) {
        if (!"from_clip".equals(key)) return;
        try {
            Context c = appCtx;
            if (c == null) return;
            ensure(c);
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            CharSequence t = (d == null || d.getItemCount() == 0) ? null : d.getItemAt(0).getText();
            String url = t == null ? null : t.toString().trim();
            if (!isYoutube(url)) {
                toast("В буфере нет ссылки на YouTube");
                return;
            }
            startDownload(url, null);
        } catch (Throwable e) {
            toast("Не вышло: " + e);
        }
    }
}
