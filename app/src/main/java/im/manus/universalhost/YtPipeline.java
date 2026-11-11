package im.manus.universalhost;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Связь очереди «YouTube аудио» с движком перевода Яндекса (живые голоса), который теперь часть хоста: отдать ему ролик,
 * узнать готовность, убрать запись и показать его настройки внутри настроек модуля. Сама цепочка шагов живёт в
 * {@link YtAudioQueue}.
 */
public final class YtPipeline {

    private static final Pattern ID = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})");
    private static VoiceOverTranslation engine;

    private YtPipeline() { }

    private static String videoId(String url) {
        Matcher m = ID.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : null;
    }

    /** Переносит настройки бывшего модуля перевода в настройки «YouTube аудио» (один раз). */
    public static void prepare(Context app) {
        try { VotPrefs.of(app); } catch (Throwable ignored) { }
    }

    private static synchronized VoiceOverTranslation vot(Context app) {
        if (engine == null) {
            engine = new VoiceOverTranslation();
            engine.init(app.getApplicationContext());
        }
        return engine;
    }

    /** Отдаёт ролик переводчику. null: принят (или перевод уже готов), иначе причина отказа. Не ждёт результата. */
    public static String votSubmit(Context app, String url, String title) {
        try {
            String id = videoId(url);
            if (id == null) return "не ссылка на ролик";
            if ("done".equals(votState(app, url)[0])) return null;         // перевод уже лежит готовый: заново не просим
            Intent i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + id);
            if (title != null && title.trim().length() > 0) i.putExtra(Intent.EXTRA_SUBJECT, title.trim());
            return vot(app).onShare(app, i) ? null : "перевод не принял ссылку";
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /**
     * Как дела у ролика: {"wait", ""} идёт, {"done", путь} готов, {"err", причина} не вышло,
     * {"gone", ""} записи нет (убрали).
     */
    public static String[] votState(Context app, String url) {
        try {
            String id = videoId(url);
            vot(app);
            VoiceOverTranslation.ensureLoaded(app);
            VoiceOverTranslation.Item it = id == null ? null : VoiceOverTranslation.findItem(id);
            if (it == null) return new String[]{"gone", ""};
            if ("done".equals(it.status)) {
                File dir = YtAudioQueue.mediaDir(app);
                File f = dir == null ? null : new File(dir, it.file);
                return f != null && f.isFile() ? new String[]{"done", f.getAbsolutePath()} : new String[]{"err", "файл не найден"};
            }
            if ("err".equals(it.status)) {
                String m = it.msg == null ? "" : it.msg;
                return new String[]{"err", m.length() > 80 ? m.substring(0, 80) : m};
            }
            return new String[]{"wait", ""};
        } catch (Throwable ignored) { }
        return new String[]{"gone", ""};
    }

    /** Убирает запись о готовом переводе (файл остаётся: он уже в плеере). */
    public static void votRemove(Context app, String url) {
        try {
            String id = videoId(url);
            vot(app);
            if (id != null) VoiceOverTranslation.drop(id);
        } catch (Throwable ignored) { }
    }

    /** Настройки перевода для экрана настроек «YouTube аудио»: ключи с префиксом vot_, без папки и Wi-Fi (они общие). */
    public static List<SettingItem> votSettings(Context app) {
        List<SettingItem> out = new ArrayList<SettingItem>();
        if (app != null) prepare(app);
        VoiceOverTranslation e = app == null ? new VoiceOverTranslation() : vot(app);
        for (SettingItem s : e.getSettingsSchema()) {
            String k = s.getKey();
            if ("out_dir".equals(k) || "wifi_only".equals(k)) continue;
            String nk = VotPrefs.PREFIX + k;
            switch (s.getType()) {
                case SettingItem.SECTION:
                    out.add(SettingItem.section(s.getTitle()));
                    break;
                case SettingItem.SWITCH:
                    out.add(SettingItem.toggle(nk, s.getTitle(), s.getSummary(), s.getDefInt() == 1));
                    break;
                case SettingItem.SLIDER:
                    out.add(SettingItem.slider(nk, s.getTitle(), s.getSummary(), s.getMin(), s.getMax(), s.getStep(), s.getDefInt()));
                    break;
                case SettingItem.CHOICE:
                    out.add(SettingItem.choice(nk, s.getTitle(), s.getSummary(), s.getOptions(), s.getDefInt()));
                    break;
                case SettingItem.TEXT:
                    out.add(SettingItem.text(nk, s.getTitle(), s.getSummary(), s.getDefStr()));
                    break;
                case SettingItem.ACTION:
                    out.add(SettingItem.action(nk, s.getTitle(), s.getSummary()));
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    /** Хост сообщает об изменении настройки перевода (ключ с префиксом vot_): передаём движку его собственный ключ. */
    public static void votSettingChanged(Context app, String key) {
        if (app == null || key == null || !key.startsWith(VotPrefs.PREFIX)) return;
        vot(app).onSettingChanged(key.substring(VotPrefs.PREFIX.length()));
    }
}
