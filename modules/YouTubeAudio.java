package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import im.manus.universalhost.Deps;
import im.manus.universalhost.IMainViewProvider;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.IShareHandler;
import im.manus.universalhost.SettingItem;
import im.manus.universalhost.YtAudioQueue;
import im.manus.universalhost.YtPipeline;
import im.manus.universalhost.YtQueueView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Скачивание аудиодорожки YouTube на нужном языке (по умолчанию русская).
 *
 * Кнопка 🎧 в скрипте YouTube TTS шлёт «Поделиться» с типом text/x-ytaudio (ссылка в тексте, название в теме).
 * Ссылка попадает в список (YtAudioQueue в хосте) и качается автоматически или вручную, как выбрано в настройках.
 * Ролики без русской дорожки остаются в списке со ссылкой. Файл и превью кладутся в Music/YouTube,
 * плеер показывает превью плитками. Модуль только принимает ссылки, описывает настройки и показывает список.
 * NewPipeExtractor и Uhm лежат не в хосте, а в зависимостях dep-newpipe.apk и dep-uhm.apk (скачиваются из релиза
 * при первом обращении или кнопкой в разделе «Зависимости» настроек).
 */
public class YouTubeAudio implements IPlugin, ISettingsProvider, IMainViewProvider, IShareHandler {

    static final String MIME = "text/x-ytaudio";
    /** Старый тип кнопки 🗣 и меню браузера: теперь это та же цепочка, что и у 🎧. */
    static final String MIME_VOT = "text/x-votaudio";

    private Context appCtx;

    @Override public String getName() { return "YouTube аудио"; }
    @Override public int getVersion() { return 8; }
    @Override public String getDescription() {
        return "Один модуль: кнопка 🎧 из скрипта YouTube TTS, список загрузок, перевод Яндекса (живые голоса) и очистка «эээ». "
                + "Русский оригинал, иначе перевод Яндекса, иначе дубляж YouTube; что не нашлось, остаётся в списке.";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override public void init(Context context) {
        appCtx = context.getApplicationContext();
        YtPipeline.prepare(appCtx);               // перенос настроек бывшего модуля «VOT перевод»
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
        r.add(MIME_VOT);
        return r;
    }

    @Override public boolean onShare(Context ctx, Intent intent) {
        try {
            YtAudioQueue q = YtAudioQueue.get(ctx);
            String url = intent.getStringExtra(Intent.EXTRA_TEXT);
            String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (url != null) url = url.trim();
            if (!isYoutube(url)) {
                q.toast("⚠️🔗");
                return false;
            }
            // кнопки скрипта YouTube TTS: 🧹 шлёт флаг clean («эээ» вырезаются независимо от настройки);
            // 🗣 шлёт тип text/x-votaudio (или флаг video): mp4 с видео YouTube и переводом Яндекса вместо звука
            boolean video = intent.getBooleanExtra("video", false) || MIME_VOT.equals(intent.getType());
            // extra engine (кнопки 🧹U, 🧹S, 🧹G в скрипте): движок очистки «эээ» для этого ролика, он же включает очистку
            int engine = intent.getIntExtra("engine", -1);
            q.submit(url, title, intent.getBooleanExtra("clean", false) || engine >= 0, video, engine);
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
        r.add(SettingItem.text("max_dl", "⬇ Одновременных загрузок",
                "Сколько роликов качается с YouTube сразу (1–10). Перевод Яндекса идёт без ограничения, очистка «эээ» всегда по одной.", "3"));
        r.add(SettingItem.text("video_height", "🎞 Высота видео для кнопки 🗣",
                "Видео с переводом качается не выше этой высоты, пикселей (144–2160, обычно 720). Нужен формат H.264: другие в mp4 без перекодирования не кладутся.", "720"));
        r.add(SettingItem.text("lang", "🌐 Язык дорожки", "Код языка: ru, en, uk.", "ru"));
        r.add(SettingItem.choice("storage", "💾 Куда сохранять",
                "Память телефона или SD-карта (карта должна быть подключена, приложению нужен доступ ко всем файлам). Если выбрана карта, а её нет, ссылки остаются в списке.",
                Arrays.asList("📱 Память телефона", "💾 SD-карта"), 0));
        r.add(SettingItem.text("out_dir", "📁 Папка", "Одна папка для оригинала, дубляжа и перевода, внутри выбранного места. Рядом с аудио кладутся превью и ссылка.", "Music/YouTube"));
        r.add(SettingItem.section("🧩 Зависимости"));
        r.add(SettingItem.action("dep_newpipe", "🧩 NewPipe (YouTube): " + depState(Deps.NEWPIPE),
                "Скачать или обновить библиотеку из релиза. Нужна для загрузки дорожек: без неё ссылки ждут в списке. Новая версия вступает сразу."));
        addDelete(r, "del_newpipe", "NewPipe", Deps.NEWPIPE);
        r.add(SettingItem.toggle("clean_fillers", "🧹 Вырезать «эээ» из русского оригинала",
                "Включено: после загрузки русского оригинала «эээ» вырезаются выбранным ниже движком. Кнопка 🧹 в меню скрипта вырезает «эээ» в любом случае. Если движок не скачался или нашёл слишком много, файл остаётся как есть.", true));
        r.add(SettingItem.choice("clean_engine", "🧪 Движок очистки «эээ»",
                "Работает только выбранный: остальные в память не загружаются, их можно удалить кнопками ниже. Uhm: модель на английском, для русской речи перенос. Steady: без модели, ищет долгие ровные гласные, от языка не зависит; ошибается на протянутых словах. GigaAM: русское распознавание речи (модель около 230 МБ, нужна память и время), ищет провалы между словами, где есть голос.",
                Arrays.asList("Uhm (модель)", "Steady (ровные гласные)", "GigaAM (русская речь)"), 0));
        r.add(SettingItem.action("dep_uhm", "🧩 Uhm («эээ»): " + depState(Deps.UHM),
                "Скачать или обновить библиотеку из релиза (большой файл, лучше по Wi-Fi). Обновление вступает после перезапуска приложения."));
        addDelete(r, "del_uhm", "Uhm", Deps.UHM);
        r.add(SettingItem.action("dep_steady", "🧩 Steady («эээ»): " + depState(Deps.STEADY),
                "Скачать или обновить лёгкую библиотеку из релиза (без модели, меньше мегабайта)."));
        addDelete(r, "del_steady", "Steady", Deps.STEADY);
        r.add(SettingItem.action("dep_gigaam", "🧩 GigaAM («эээ»): " + depState(Deps.GIGAAM),
                "Скачать или обновить библиотеку из релиза. Саму модель русской речи (около 230 МБ) программа скачает при первой очистке, лучше по Wi-Fi. Обновление вступает после перезапуска приложения."));
        addDelete(r, "del_gigaam", "GigaAM и модель", Deps.GIGAAM);
        r.add(SettingItem.section("🗣 Перевод Яндекса"));
        r.addAll(YtPipeline.votSettings(appCtx));
        return r;
    }

    private String depState(String name) {
        return appCtx == null ? "" : Deps.state(appCtx, name);
    }

    /** Кнопка «удалить зависимость» показывается, только пока она скачана. */
    private void addDelete(List<SettingItem> r, String key, String title, String dep) {
        if (appCtx == null || !Deps.installed(appCtx, dep)) return;
        r.add(SettingItem.action(key, "🗑 Удалить " + title + " (" + depState(dep) + ")",
                "Стирает файлы с телефона. Скачать снова можно кнопкой выше. Нативный код Uhm, уже загруженный в этом запуске, освободится после перезапуска приложения."));
    }

    @Override public void onSettingChanged(String key) {
        if (appCtx == null) return;
        if ("dep_newpipe".equals(key)) { Deps.updateAsync(appCtx, Deps.NEWPIPE); return; }
        if ("dep_uhm".equals(key)) { Deps.updateAsync(appCtx, Deps.UHM); return; }
        if ("dep_steady".equals(key)) { Deps.updateAsync(appCtx, Deps.STEADY); return; }
        if ("dep_gigaam".equals(key)) { Deps.updateAsync(appCtx, Deps.GIGAAM); return; }
        if ("del_gigaam".equals(key)) { Deps.deleteAsync(appCtx, Deps.GIGAAM); return; }
        if ("del_newpipe".equals(key)) { Deps.deleteAsync(appCtx, Deps.NEWPIPE); return; }
        if ("del_uhm".equals(key)) { Deps.deleteAsync(appCtx, Deps.UHM); return; }
        if ("del_steady".equals(key)) { Deps.deleteAsync(appCtx, Deps.STEADY); return; }
        if (key != null && key.startsWith("vot_")) { YtPipeline.votSettingChanged(appCtx, key); return; }
        if ("auto_mode".equals(key) || "wifi_only".equals(key)) YtAudioQueue.get(appCtx).tryDrain();
    }

    /** Главное окно (отдельная карточка в недавних): сетка списка и кнопки обработки списка. */
    @Override public View createMainView(Context context) {
        return YtQueueView.create(context);
    }
}
