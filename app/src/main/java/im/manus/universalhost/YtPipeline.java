package im.manus.universalhost;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Связь очереди «YouTube аудио» с модулем «VOT перевод» (перевод Яндекса, живые голоса). Сама цепочка шагов живёт
 * в {@link YtAudioQueue}; здесь только то, что нужно для VOT: отдать ему ролик, узнать готовность и убрать запись.
 * VOT ведёт свою очередь и результат наружу не возвращает: готовность читается из его настроек (список хранится там же).
 */
public final class YtPipeline {

    private static final String VOT = "VOT перевод";
    private static final String VOT_MIME = "text/x-votaudio";
    /** С этой версии модуль понимает vot_remove (убрать запись из списка). Старый принял бы такое за новую ссылку. */
    private static final int VOT_REMOVE_VERSION = 7;
    private static final Pattern ID = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})");

    private YtPipeline() { }

    private static String videoId(String url) {
        Matcher m = ID.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : null;
    }

    private static IShareHandler votPlugin(Context app) {
        try {
            IPlugin p = PluginRegistry.INSTANCE.find(app, VOT);
            if (p instanceof IShareHandler && ModuleStorage.INSTANCE.isEnabled(app, VOT)) return (IShareHandler) p;
        } catch (Throwable ignored) { }
        return null;
    }

    private static SharedPreferences votPrefs(Context app) {
        return app.getSharedPreferences(ModuleStorage.INSTANCE.prefsName(VOT), Context.MODE_PRIVATE);
    }

    /** Отдаёт ролик VOT. null: принят (или перевод уже готов), иначе причина отказа. Не ждёт результата. */
    public static String votSubmit(Context app, String url, String title) {
        try {
            String id = videoId(url);
            if (id == null) return "не ссылка на ролик";
            IShareHandler vot = votPlugin(app);
            if (vot == null) return "нет модуля VOT";
            if ("done".equals(votState(app, url)[0])) return null;         // перевод уже лежит готовый: заново не просим
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(VOT_MIME);
            i.putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + id);
            if (title != null && title.trim().length() > 0) i.putExtra(Intent.EXTRA_SUBJECT, title.trim());
            return vot.onShare(app, i) ? null : "VOT не принял ссылку";
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /**
     * Как дела у ролика в VOT: {"wait", ""} идёт, {"done", путь} готов, {"err", причина} не вышло,
     * {"gone", ""} записи в списке VOT нет (убрали).
     */
    public static String[] votState(Context app, String url) {
        try {
            String id = videoId(url);
            SharedPreferences vp = votPrefs(app);
            JSONArray a = new JSONArray(vp.getString("queue", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!String.valueOf(id).equals(o.optString("id"))) continue;
                String st = o.optString("status");
                if ("done".equals(st)) {
                    String dir = vp.getString("out_dir", "Music/VOT");
                    if (dir == null || dir.trim().length() == 0) dir = "Music/VOT";
                    File f = new File(new File(Environment.getExternalStorageDirectory(), dir.trim()), o.optString("file"));
                    return f.isFile() ? new String[]{"done", f.getAbsolutePath()} : new String[]{"err", "файл VOT не найден"};
                }
                if ("err".equals(st)) {
                    String m = o.optString("msg");
                    return new String[]{"err", m.length() > 80 ? m.substring(0, 80) : m};
                }
                return new String[]{"wait", ""};
            }
        } catch (Throwable ignored) { }
        return new String[]{"gone", ""};
    }

    /** Убирает запись о готовом переводе из списка VOT (файл остаётся: он уже в плеере). Старому VOT не пишем. */
    public static void votRemove(Context app, String url) {
        try {
            String id = videoId(url);
            IShareHandler vot = votPlugin(app);
            if (id == null || vot == null || ((IPlugin) vot).getVersion() < VOT_REMOVE_VERSION) return;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(VOT_MIME);
            i.putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + id);
            i.putExtra("vot_remove", true);
            vot.onShare(app, i);
        } catch (Throwable ignored) { }
    }
}
