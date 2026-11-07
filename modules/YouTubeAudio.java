package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.ISettingsViewProvider;
import im.manus.universalhost.IShareHandler;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.YtAudioQueue;
import im.manus.universalhost.YtQueueView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Скачивание аудиодорожки YouTube на нужном языке (по умолчанию русская).
 *
 * Кнопка 🎧 в скрипте YouTube TTS шлёт «Поделиться» с типом text/x-ytaudio (ссылка в тексте, название в теме).
 * Ссылка попадает в список (YtAudioQueue в хосте) и качается автоматически или вручную, как выбрано в настройках.
 * Ролики без русской дорожки остаются в списке со ссылкой. Файл и превью кладутся в Music/YouTube,
 * плеер показывает превью плитками. Модуль только принимает ссылки, описывает настройки и показывает список.
 */
public class YouTubeAudio implements IPlugin, ISettingsProvider, ISettingsViewProvider, IShareHandler {

    static final String MIME = "text/x-ytaudio";

    private Context appCtx;

    @Override public String getName() { return "YouTube аудио"; }
    @Override public int getVersion() { return 4; }
    @Override public String getDescription() {
        return "Скачивает русскую аудиодорожку YouTube по кнопке 🎧 из скрипта YouTube TTS; список ссылок с превью.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        YtAudioQueue.get(appCtx).tryDrain();      // ссылки, дожидавшиеся сети до перезапуска
    }

    @Override public void stop() {
        if (appCtx != null) YtAudioQueue.get(appCtx).unwatchNet();
    }

    @Override public Object execute(Map<String, ?> data) { return null; }
    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event, AccessibilityService service) { }
    @Override public Bitmap createIcon(int sizePx) { return null; }

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
            YtAudioQueue q = YtAudioQueue.get(ctx);
            String url = intent.getStringExtra(Intent.EXTRA_TEXT);
            String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (url != null) url = url.trim();
            if (!isYoutube(url)) {
                q.toast("Это не ссылка на YouTube");
                return false;
            }
            q.submit(url, title);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------- настройки

    @Override public List<SettingItem> getSettingsSchema() {
        List<SettingItem> r = new ArrayList<SettingItem>();
        r.add(SettingItem.section("🎧 Загрузка"));
        r.add(SettingItem.toggle("auto_mode", "🤖 Загружать автоматически",
                "Включено: ссылки качаются сами, как только есть подходящая сеть. Выключено: ссылки ждут в списке, пока не нажмёшь на плитку.", true));
        r.add(SettingItem.toggle("wifi_only", "📶 Только по Wi-Fi",
                "Мобильная сеть не используется: ссылки ждут Wi-Fi.", false));
        r.add(SettingItem.toggle("fallback_original", "🎙 Оригинал, если русской нет",
                "Выключено: ролик без русской дорожки остаётся в списке со ссылкой.", false));
        r.add(SettingItem.text("lang", "🌐 Язык дорожки", "Код языка: ru, en, uk.", "ru"));
        r.add(SettingItem.text("out_dir", "📁 Папка", "Внутри памяти телефона. Рядом с аудио кладутся превью и ссылка.", "Music/YouTube"));
        r.add(SettingItem.section("📋 Список"));
        r.add(SettingItem.custom("queue", "📋 Список ссылок",
                "Тап по плитке качает, двойной тап читает название. Значки: ссылка в браузере, удалить."));
        return r;
    }

    @Override public void onSettingChanged(String key) {
        if (appCtx == null) return;
        if ("auto_mode".equals(key) || "wifi_only".equals(key)) YtAudioQueue.get(appCtx).tryDrain();
    }

    @Override public View createSettingsView(Context context, String key) {
        return YtQueueView.create(context);
    }
}
