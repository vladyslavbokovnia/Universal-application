package im.manus.universalhost;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Network;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Одна кнопка 🎧 вместо набора пунктов: что делать со ссылкой, решается по шагам.
 *
 *  1. 🔎 Есть оригинальная русская дорожка → качаем её и вырезаем «эээ» (🧹).
 *  2. 🗣 Дорожка не русская → перевод Яндекса «живые голоса» (модуль «VOT перевод»).
 *  3. 🤖 VOT не справился → ищем русскую дорожку, переведённую самим YouTube (дубляж).
 *  4. 📋 Больше вариантов нет → ссылка остаётся в списке загрузки.
 *
 * Шаги сообщают значками через Listener.onStatus; ошибки сети уходят наверх как есть, чтобы очередь поставила
 * ролик ждать сеть. VOT ведёт свою очередь и сам результат не возвращает: готовность читается из его настроек.
 */
public final class YtPipeline {

    private static final String VOT = "VOT перевод";
    private static final String VOT_MIME = "text/x-votaudio";
    private static final Pattern ID = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})");

    private YtPipeline() { }

    public static void start(final Context ctx, final String url, final String title, final String lang,
                             final String outDir, final Network net, final YtAudioBridge.Listener l) {
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    flow(app, url, title, lang, outDir, net, l);
                } catch (Throwable e) {
                    l.onDone(null, "Ошибка: " + e);
                }
            }
        }, "YtPipeline");
        t.start();
    }

    private static void flow(Context app, String url, String title, String lang, String outDir, Network net,
                            YtAudioBridge.Listener l) throws Exception {
        SharedPreferences p = app.getSharedPreferences(YtAudioQueue.PREFS, Context.MODE_PRIVATE);
        l.onStatus("🔎");

        // 1. оригинальная русская дорожка
        String[] r = fetch(app, url, title, lang, outDir, YtAudioBridge.MODE_ORIGINAL, net);
        if (r[0] != null) {
            String path = r[0];
            if (p.getBoolean("clean_fillers", true)) {
                l.onStatus("🧹");
                try {
                    FillerCleaner.Report rep = FillerCleaner.clean(app, new File(path));
                    if (rep != null && rep.file != null) path = rep.file;
                    l.onStatus(rep != null && rep.count > 0 ? "✂️" : "👌");
                } catch (Throwable e) {
                    l.onStatus("⚠️🧹");
                }
            }
            l.onDone(path, null);
            return;
        }
        if (!r[1].startsWith("Нет дорожки")) {      // сеть или сбой: как раньше, решает очередь
            l.onDone(null, r[1]);
            return;
        }

        // 2. перевод Яндекса (живые голоса)
        l.onStatus("🗣");
        String[] v = vot(app, url, title);
        if (v[0] != null) {
            l.onDone(v[0], null);
            return;
        }

        // 3. русская дорожка YouTube (дубляж)
        l.onStatus("🤖");
        r = fetch(app, url, title, lang, outDir, YtAudioBridge.MODE_DUBBED, net);
        if (r[0] != null) {
            l.onDone(r[0], null);
            return;
        }
        if (r[1].startsWith("Нет сети")) {
            l.onDone(null, r[1]);
            return;
        }

        // 4. вариантов больше нет: ссылка остаётся в списке
        l.onStatus("📋");
        l.onDone(null, "Нет дорожки: русской нет, перевод Яндекса не вышел (" + v[1] + ")");
    }

    /** Скачивает дорожку нужного вида и ждёт конца. Возвращает {путь, null} или {null, ошибка}. */
    private static String[] fetch(Context app, String url, String title, String lang, String outDir, int mode,
                                  Network net) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] r = new String[2];
        YtAudioBridge.start(app, url, title, lang, outDir, false, mode, net, new YtAudioBridge.Listener() {
            @Override public void onStatus(String text) { }     // проценты и пр. наружу не идут: только значки шагов

            @Override public void onDone(String audioPath, String error) {
                r[0] = audioPath;
                r[1] = error == null ? "" : error;
                done.countDown();
            }
        });
        done.await();
        return r;
    }

    private static String videoId(String url) {
        Matcher m = ID.matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : null;
    }

    /** Отдаёт ролик модулю «VOT перевод» и ждёт результат. Возвращает {путь, null} или {null, причина}. */
    private static String[] vot(Context app, String url, String title) {
        try {
            String id = videoId(url);
            if (id == null) return new String[]{null, "не ссылка на ролик"};
            IPlugin plugin = PluginRegistry.INSTANCE.find(app, VOT);
            if (!(plugin instanceof IShareHandler) || !ModuleStorage.INSTANCE.isEnabled(app, VOT)) {
                return new String[]{null, "нет модуля VOT"};
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(VOT_MIME);
            i.putExtra(Intent.EXTRA_TEXT, "https://youtu.be/" + id);
            if (title != null && title.trim().length() > 0) i.putExtra(Intent.EXTRA_SUBJECT, title.trim());
            if (!((IShareHandler) plugin).onShare(app, i)) return new String[]{null, "VOT не принял ссылку"};

            SharedPreferences vp = app.getSharedPreferences(ModuleStorage.INSTANCE.prefsName(VOT), Context.MODE_PRIVATE);
            long until = System.currentTimeMillis() + 6L * 60 * 60 * 1000;
            while (System.currentTimeMillis() < until) {
                Thread.sleep(2000);
                JSONObject o = votEntry(vp, id);
                if (o == null) return new String[]{null, "убрано из списка VOT"};
                String st = o.optString("status");
                if ("done".equals(st)) {
                    String name = o.optString("file");
                    String dir = vp.getString("out_dir", "Music/VOT");
                    if (dir == null || dir.trim().length() == 0) dir = "Music/VOT";
                    File f = new File(new File(Environment.getExternalStorageDirectory(), dir.trim()), name);
                    return f.isFile() ? new String[]{f.getAbsolutePath(), null} : new String[]{null, "файл VOT не найден"};
                }
                if ("err".equals(st)) {
                    String m = o.optString("msg");
                    return new String[]{null, m.length() > 80 ? m.substring(0, 80) : m};
                }
            }
            return new String[]{null, "VOT слишком долго"};
        } catch (Throwable t) {
            return new String[]{null, String.valueOf(t)};
        }
    }

    /** Запись ролика в очереди VOT (она хранится в настройках модуля как JSON). */
    private static JSONObject votEntry(SharedPreferences vp, String id) {
        try {
            JSONArray a = new JSONArray(vp.getString("queue", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (id.equals(o.optString("id"))) return o;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
