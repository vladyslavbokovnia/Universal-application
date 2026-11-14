package im.manus.universalhost;

import android.content.Context;
import android.net.Network;
import android.os.Build;

import dalvik.system.DexClassLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Зависимости, вынесенные из хоста: NewPipeExtractor (dep-newpipe.apk) и Uhm (dep-uhm.apk). Каждая собирается в свой
 * APK-контейнер (не устанавливается, это просто архив с classes.dex и нативными библиотеками) и публикуется в релизе latest
 * рядом с модулями. Лежат в files/deps, грузятся через DexClassLoader с хостом в роли родителя: общие интерфейсы
 * (пакет im.manus.universalhost.dep) и Kotlin берутся у хоста, всё остальное из самого контейнера.
 *
 * Скачиваются при первом обращении ({@link #ensure}) или кнопкой в настройках «YouTube аудио» ({@link #updateAsync}).
 * Новая версия NewPipe подхватывается сразу (его загрузчик пересоздаётся); Uhm держит нативные библиотеки, их второй раз
 * загрузить другим загрузчиком нельзя, поэтому обновление Uhm вступает в силу после перезапуска приложения.
 */
public final class Deps {

    public static final String NEWPIPE = "newpipe";
    public static final String UHM = "uhm";

    private static final String API =
            "https://api.github.com/repos/vladyslavbokovnia/Universal-application/releases/tags/latest";

    /** Зависимости нет на устройстве. */
    public static final class Missing extends IOException {
        Missing(String m) { super(m); }
    }

    private static Context app;
    private static final Map<String, ClassLoader> LOADERS = new HashMap<String, ClassLoader>();
    private static final Map<String, Object> INSTANCES = new HashMap<String, Object>();
    /** Скачивание по одной зависимости за раз. */
    private static final Object LOCK = new Object();

    private Deps() { }

    public static void init(Context c) { app = c.getApplicationContext(); }

    /** Контекст приложения, если он уже известен (после UniversalApp.onCreate); иначе null. */
    public static Context app() { return app; }

    // ------------------------------------------------------------ файлы

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "deps");
        d.mkdirs();
        return d;
    }

    public static File file(Context c, String name) {
        return new File(dir(c), "dep-" + name + ".apk");
    }

    public static boolean installed(Context c, String name) {
        return file(c, name).isFile();
    }

    /** Для строки в настройках. */
    public static String state(Context c, String name) {
        File f = file(c, name);
        if (!f.isFile()) return "не скачана";
        return "скачана, " + Math.max(1, Math.round(f.length() / 1048576.0)) + " МБ";
    }

    // ------------------------------------------------------------ загрузка классов

    private static String abi() {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String a : abis) if ("arm64-v8a".equals(a)) return a;
        return abis.length > 0 ? abis[0] : "arm64-v8a";
    }

    public static synchronized ClassLoader loader(Context c, String name) throws IOException {
        ClassLoader l = LOADERS.get(name);
        if (l != null) return l;
        File apk = file(c, name);
        if (!apk.isFile()) throw new Missing("Нет зависимости " + name);
        // с Android 14 файл с кодом должен быть только для чтения
        if (apk.canWrite()) apk.setReadOnly();
        File opt = new File(c.getCodeCacheDir(), "dep-" + name);
        opt.mkdirs();
        // нативные библиотеки берутся прямо из архива (хранятся без сжатия): путь вида файл.apk!/lib/abi
        String libs = apk.getAbsolutePath() + "!/lib/" + abi();
        l = new DexClassLoader(apk.getAbsolutePath(), opt.getAbsolutePath(), libs, c.getApplicationContext().getClassLoader());
        LOADERS.put(name, l);
        return l;
    }

    /** Создаёт (один раз) объект класса cls из зависимости и приводит его к интерфейсу хоста. */
    public static synchronized <T> T create(Context c, String name, String cls, Class<T> type) throws Exception {
        Object o = INSTANCES.get(name);
        if (o == null) {
            o = loader(c, name).loadClass(cls).getDeclaredConstructor().newInstance();
            INSTANCES.put(name, o);
        }
        return type.cast(o);
    }

    private static synchronized void evict(String name) {
        LOADERS.remove(name);
        INSTANCES.remove(name);
    }

    // ------------------------------------------------------------ скачивание из релиза

    /** Скачивает зависимость, если её ещё нет. net: сеть для загрузки (null: любая). Блокирующий вызов. */
    public static void ensure(Context c, String name, Network net) throws IOException {
        if (installed(c, name)) return;
        update(c, name, net, false);
    }

    /**
     * Скачивает или обновляет dep-&lt;name&gt;.apk из релиза latest. force: сверить с релизом, даже если файл уже есть
     * (по SHA-256, который GitHub указывает у файла). Возвращает true, если файл заменён. Блокирующий вызов.
     */
    public static boolean update(Context c, String name, Network net, boolean force) throws IOException {
        synchronized (LOCK) {
            File target = file(c, name);
            if (!force && target.isFile()) return false;
            JSONObject a = asset(net, "dep-" + name + ".apk");
            String url = a.optString("browser_download_url", "");
            long size = a.optLong("size", 0L);
            String digest = a.optString("digest", "");
            digest = digest.startsWith("sha256:") ? digest.substring(7) : "";
            if (url.length() == 0) throw new IOException("у файла в релизе нет ссылки");
            if (target.isFile() && digest.length() > 0 && digest.equalsIgnoreCase(sha256(target))) return false;
            File tmp = new File(target.getPath() + ".tmp");
            try {
                HttpURLConnection h = open(url, net);
                if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                InputStream in = h.getInputStream();
                OutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                if (size > 0 && tmp.length() != size) throw new IOException("размер не совпал");
                String hex = hex(md.digest());
                if (digest.length() > 0 && !hex.equalsIgnoreCase(digest)) throw new IOException("контрольная сумма не совпала");
                if (target.exists() && !target.delete()) throw new IOException("не удалось заменить файл");
                if (!tmp.renameTo(target)) throw new IOException("не удалось сохранить файл");
                target.setReadOnly();
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e.toString());
            } finally {
                tmp.delete();
            }
            if (!UHM.equals(name)) evict(name);
            return true;
        }
    }

    /** То же в фоне, с уведомлениями из значков: 🧩⬇ качается, ✅🧩 обновлено, 👌🧩 уже свежая, ⚠️🧩 не вышло. */
    public static void updateAsync(Context c, final String name) {
        final Context a = c.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                YtNotify.show(a, "🧩⬇", "🧩");
                try {
                    boolean changed = update(a, name, null, true);
                    YtNotify.show(a, changed ? "✅🧩" : "👌🧩", "🧩");
                } catch (Throwable e) {
                    YtNotify.show(a, "⚠️🧩", "⚠️");
                }
            }
        }, "DepUpdate");
        t.start();
    }

    // ------------------------------------------------------------ сеть

    private static HttpURLConnection open(String url, Network net) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "UniversalHost");
        return c;
    }

    private static JSONObject asset(Network net, String file) throws IOException {
        HttpURLConnection c = open(API, net);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IOException(code == 404 ? "релиз latest не найден"
                    : code == 403 ? "лимит запросов GitHub, повтори позже" : "GitHub ответил " + code);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        try {
            JSONArray arr = new JSONObject(bos.toString("UTF-8")).getJSONArray("assets");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (file.equals(o.optString("name", ""))) return o;
            }
        } catch (JSONException e) {
            throw new IOException("ответ GitHub не разобрать");
        }
        throw new IOException("в релизе нет " + file);
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        }
    }
}
