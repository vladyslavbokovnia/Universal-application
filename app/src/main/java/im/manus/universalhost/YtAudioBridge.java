package im.manus.universalhost;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Network;

import im.manus.universalhost.dep.YtExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Скачивание аудиодорожки YouTube нужного языка через NewPipeExtractor (его же использует приложение NewPipe).
 * Сам извлекатель больше не в хосте: он лежит в зависимости dep-newpipe.apk (см. {@link Deps}), хост общается с ним через
 * {@link YtExtractor}, а разбор дорожек, выбор нужной и сама загрузка остались здесь. Если зависимости ещё нет
 * на устройстве, она скачивается из релиза перед первой загрузкой (через ту же сеть, что и дорожка).
 * Модули (YouTubeAudio) вызывают {@link #start}: всё идёт в фоновом потоке, итог приходит в {@link Listener}.
 * Рядом с аудиофайлом кладётся превью: то же имя, расширение .jpg (его показывает плеер).
 * Публичные сигнатуры только на типах JDK и Android, чтобы модуль (dex) компилировался без библиотеки.
 */
public final class YtAudioBridge {

    public interface Listener {
        /** Короткий статус для тоста. */
        void onStatus(String text);

        /** Готово: audioPath — путь к файлу, иначе error — причина на русском. */
        void onDone(String audioPath, String error);
    }

    private static final String FIREFOX_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";
    private static final int CHUNK = 4 * 1024 * 1024;
    private static final String IMPL = "im.manus.deps.NewPipeImpl";

    /** Какую дорожку брать: любую русскую, только оригинал или только дубляж YouTube. */
    public static final int MODE_ANY = 0, MODE_ORIGINAL = 1, MODE_DUBBED = 2;

    /** Сеть, через которую качает текущий поток (null: по умолчанию). Нужна для режима «только Wi-Fi». */
    private static final ThreadLocal<Network> NET = new ThreadLocal<Network>();

    private static HttpURLConnection open(String u) throws IOException {
        URL url = new URL(u);
        Network n = NET.get();
        return (HttpURLConnection) (n != null ? n.openConnection(url) : url.openConnection());
    }

    /** Для зависимости: открывает соединение в том же потоке через выбранную сеть. */
    private static final YtExtractor.Opener OPENER = new YtExtractor.Opener() {
        @Override public HttpURLConnection open(String url) throws IOException {
            return YtAudioBridge.open(url);
        }
    };

    /** Извлекатель из dep-newpipe.apk; если файла ещё нет, скачивает его из релиза. */
    private static YtExtractor extractor(Context ctx) throws Exception {
        Deps.ensure(ctx, Deps.NEWPIPE, NET.get());
        return Deps.create(ctx, Deps.NEWPIPE, IMPL, YtExtractor.class);
    }

    /** Ошибка связана с сетью (нет сети, обрыв, таймаут): такую загрузку стоит поставить в очередь. */
    private static boolean netCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketException || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.NoRouteToHostException || t instanceof javax.net.ssl.SSLException) return true;
        }
        return false;
    }

    private YtAudioBridge() { }

    public static void start(Context ctx, String url, String title, String lang,
                             String outDir, boolean fallbackOriginal, Listener l) {
        start(ctx, url, title, lang, outDir, fallbackOriginal, null, l);
    }

    public static void start(Context ctx, String url, String title, String lang, String outDir,
                             boolean fallbackOriginal, Network net, Listener l) {
        start(ctx, url, title, lang, outDir, fallbackOriginal, MODE_ANY, net, l);
    }

    /**
     * Запускает загрузку в фоне. lang — код языка дорожки (ru), outDir — папка, net — сеть для загрузки (null: любая).
     * mode: MODE_ORIGINAL — только оригинальная дорожка языка, MODE_DUBBED — только дубляж YouTube, MODE_ANY — любая.
     */
    public static void start(final Context ctx, final String url, final String title, final String lang,
                             final String outDir, final boolean fallbackOriginal, final int mode,
                             final Network net, final Listener l) {
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                NET.set(net);
                try {
                    String path = doRun(app, url, title, lang, outDir, fallbackOriginal, mode, l);
                    l.onDone(path, null);
                } catch (UserError e) {
                    l.onDone(null, e.getMessage());
                } catch (Throwable e) {
                    l.onDone(null, (netCause(e) ? "Нет сети: " : "Ошибка: ") + e);
                }
            }
        }, "YtAudio");
        t.start();
    }

    private static final class UserError extends Exception {
        UserError(String m) { super(m); }
    }

    private static boolean sameLang(YtExtractor.Audio s, String lang) {
        if (s.lang != null && lang.equalsIgnoreCase(s.lang)) return true;
        String id = s.trackId;
        return id != null && id.toLowerCase(Locale.ROOT).startsWith(lang.toLowerCase(Locale.ROOT) + ".");
    }

    private static boolean isOriginal(YtExtractor.Audio s) {
        return "ORIGINAL".equals(s.trackType);
    }

    /** Подходит ли дорожка выбранному режиму: тип null (единственная дорожка) считается оригиналом. */
    private static boolean modeFits(YtExtractor.Audio s, int mode) {
        String t = s.trackType;
        if (mode == MODE_ORIGINAL) return t == null || "ORIGINAL".equals(t);
        if (mode == MODE_DUBBED) return "DUBBED".equals(t);
        return true;
    }

    /** Лучший по формату (M4A предпочтительнее: его индексирует медиатека) и битрейту. */
    private static YtExtractor.Audio best(List<YtExtractor.Audio> list) {
        YtExtractor.Audio b = null;
        for (YtExtractor.Audio s : list) {
            if (b == null) { b = s; continue; }
            if (s.m4a && !b.m4a) b = s;
            else if (s.m4a == b.m4a && s.bitrate > b.bitrate) b = s;
        }
        return b;
    }

    private static String doRun(Context ctx, String url, String title, String lang, String outDir,
                              boolean fallbackOriginal, int mode, Listener l) throws Exception {
        if (lang == null || lang.trim().length() == 0) lang = "ru";
        lang = lang.trim();
        YtExtractor ex = extractor(ctx);
        l.onStatus("🎧 Ищу дорожки…");
        YtExtractor.Video info = ex.fetch(url, OPENER);

        List<YtExtractor.Audio> all = new ArrayList<YtExtractor.Audio>(info.audio);
        if (all.isEmpty()) throw new UserError("Нет доступных аудиопотоков");

        List<YtExtractor.Audio> wanted = new ArrayList<YtExtractor.Audio>();
        Set<String> langs = new LinkedHashSet<String>();
        for (YtExtractor.Audio s : all) {
            if (s.lang != null) langs.add(new Locale(s.lang).getDisplayLanguage(new Locale("ru")));
            if (sameLang(s, lang) && modeFits(s, mode)) wanted.add(s);
        }
        if (wanted.isEmpty() && mode != MODE_DUBBED && looksLike(info, lang)) {
            // единственная дорожка (язык в потоках не указан) или оригинал: язык видео совпал по тексту
            Set<String> ids = new HashSet<String>();
            for (YtExtractor.Audio s : all) ids.add(s.trackId == null ? "" : s.trackId);
            if (ids.size() <= 1) wanted.addAll(all);
            else for (YtExtractor.Audio s : all) {
                // оригинал с известным другим языком русским не считается
                if (isOriginal(s) && (s.lang == null || lang.equalsIgnoreCase(s.lang))) wanted.add(s);
            }
        }
        boolean original = false;
        YtExtractor.Audio pick = best(wanted);
        if (pick == null) {
            if (!fallbackOriginal) {
                throw new UserError("Нет дорожки «" + lang + "»" + (langs.isEmpty() ? " (единственная дорожка не русская)"
                        : ". Есть: " + join(langs)));
            }
            List<YtExtractor.Audio> orig = new ArrayList<YtExtractor.Audio>();
            for (YtExtractor.Audio s : all) if (isOriginal(s) || s.lang == null) orig.add(s);
            pick = best(orig.isEmpty() ? all : orig);
            original = true;
        }

        String ext = pick.suffix != null ? pick.suffix : "m4a";
        String name = safeName(title != null && title.trim().length() > 0 ? title : info.title);
        if (original) name = name + " (оригинал)";
        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) throw new UserError("Не создать папку " + dir);
        File out = new File(dir, name + "." + ext);
        for (int n = 2; out.exists(); n++) out = new File(dir, name + " (" + n + ")." + ext);
        String base = out.getName().substring(0, out.getName().length() - ext.length() - 1);

        l.onStatus("⬇ Качаю" + (original ? " оригинал" : "") + "…");
        File part = new File(dir, base + ".part");
        try {
            download(ex, pick.url, part, l);
        } catch (Throwable e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(out)) {
            part.delete();
            throw new UserError("Не удалось сохранить файл");
        }

        File jpg = new File(dir, base + ".jpg");
        try {
            String th = bestThumb(info.thumbs);
            if (th != null) fetchTo(th, jpg);
        } catch (Throwable ignore) {
            jpg.delete();
        }
        try {
            OutputStream lo = new FileOutputStream(new File(dir, base + ".url"));
            try { lo.write(url.getBytes("UTF-8")); } finally { lo.close(); }
        } catch (Throwable ignore) { }
        MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()}, null, null);
        return out.getAbsolutePath();
    }

    /**
     * Для модуля «VOT перевод»: скачивает оригинальную звуковую дорожку ролика во временный файл
     * (Яндексу для перевода нужно оригинальное аудио). Возвращает файл; удалить его должен вызывающий.
     */
    public static File fetchOriginal(Context ctx, String url, File dir) throws Exception {
        return fetchOriginal(ctx, url, dir, "m4a");
    }

    /** format: "m4a" (по умолчанию) или "webm" (Opus). Если потоков такого формата нет, берётся лучший из остальных. */
    public static File fetchOriginal(Context ctx, String url, File dir, String format) throws Exception {
        YtExtractor ex = extractor(ctx);
        YtExtractor.Video info = ex.fetch(url, OPENER);
        List<YtExtractor.Audio> all = new ArrayList<YtExtractor.Audio>(info.audio);
        if (all.isEmpty()) throw new UserError("Нет доступных аудиопотоков");
        List<YtExtractor.Audio> orig = new ArrayList<YtExtractor.Audio>();
        for (YtExtractor.Audio s : all) if (isOriginal(s) || s.lang == null) orig.add(s);
        List<YtExtractor.Audio> pool = orig.isEmpty() ? all : orig;
        YtExtractor.Audio pick = null;
        if ("webm".equalsIgnoreCase(format)) {
            for (YtExtractor.Audio s : pool) {
                if (s.suffix == null || !"webm".equalsIgnoreCase(s.suffix)) continue;
                if (pick == null || s.bitrate > pick.bitrate) pick = s;
            }
        }
        if (pick == null) pick = best(pool);
        if (!dir.exists() && !dir.mkdirs()) throw new UserError("Не создать папку " + dir);
        String ext = pick.suffix != null ? pick.suffix : "m4a";
        File out = new File(dir, "vot-" + System.currentTimeMillis() + "." + ext);
        try {
            download(ex, pick.url, out, new Listener() {
                @Override public void onStatus(String s) { }
                @Override public void onDone(String audioPath, String error) { }
            });
        } catch (Throwable e) {
            out.delete();
            if (e instanceof Exception) throw (Exception) e;
            throw new IOException(e.toString());
        }
        return out;
    }

    /** Язык видео по тексту (название, канал, начало описания): пока умеем только русский (кириллица без украинских букв). */
    private static boolean looksLike(YtExtractor.Video info, String lang) {
        if (!"ru".equalsIgnoreCase(lang)) return false;
        StringBuilder sb = new StringBuilder();
        sb.append(info.title).append(' ').append(info.uploader).append(' ');
        String d = info.description;
        if (d != null) sb.append(d.length() > 300 ? d.substring(0, 300) : d);
        String s = sb.toString();
        int letters = 0, cyr = 0, ukr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            if (c >= 0x0400 && c <= 0x04FF) cyr++;
            if ("\u0456\u0457\u0454\u0491\u0406\u0407\u0404\u0490".indexOf(c) >= 0) ukr++;
        }
        return letters >= 3 && cyr * 2 >= letters && ukr == 0;
    }

    private static String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x);
        }
        return sb.toString();
    }

    private static String safeName(String s) {
        if (s == null) s = "YouTube";
        s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.startsWith(".")) s = s.substring(1).trim();
        if (s.length() > 90) s = s.substring(0, 90).trim();
        return s.length() == 0 ? "YouTube" : s;
    }

    /** Самое крупное превью до 720 px по высоте, иначе самое мелкое. */
    private static String bestThumb(List<YtExtractor.Thumb> imgs) {
        if (imgs == null || imgs.isEmpty()) return null;
        YtExtractor.Thumb best = null;
        YtExtractor.Thumb smallest = null;
        for (YtExtractor.Thumb im : imgs) {
            if (smallest == null || im.height < smallest.height) smallest = im;
            if (im.height <= 720 && (best == null || im.height > best.height)) best = im;
        }
        YtExtractor.Thumb r = best != null ? best : smallest;
        return r == null ? null : r.url;
    }

    private static void fetchTo(String url, File f) throws IOException {
        HttpURLConnection c = open(url);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", FIREFOX_UA);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        InputStream in = c.getInputStream();
        OutputStream o = new FileOutputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            o.close();
            in.close();
        }
    }

    private static long clen(String url) {
        int i = url.indexOf("clen=");
        if (i < 0) return -1;
        int e = i + 5;
        while (e < url.length() && Character.isDigit(url.charAt(e))) e++;
        try { return Long.parseLong(url.substring(i + 5, e)); } catch (Throwable t) { return -1; }
    }

    /** Как плеер NewPipe: POST с телом {0x78, 0}, параметры rn и range в адресе, UA по клиенту потока. */
    private static void download(YtExtractor ex, String url, File to, Listener l) throws Exception {
        long total = clen(url);
        boolean playback = new URL(url).getPath().startsWith("/videoplayback");
        OutputStream o = new FileOutputStream(to);
        long pos = 0;
        int rn = 1;
        int lastPct = -1;
        try {
            while (true) {
                long end = pos + CHUNK - 1;
                if (total > 0 && end >= total) end = total - 1;
                String u = url;
                if (playback) {
                    if (!u.contains("&rn=")) u += "&rn=" + rn;
                    rn++;
                    u += "&range=" + pos + "-" + end;
                }
                HttpURLConnection c = open(u);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                String ua = ex.userAgent(u);
                c.setRequestProperty("User-Agent", ua != null ? ua : FIREFOX_UA);
                if (ex.webStreaming(u)) {
                    c.setRequestProperty("Origin", "https://www.youtube.com");
                    c.setRequestProperty("Referer", "https://www.youtube.com");
                    c.setRequestProperty("Sec-Fetch-Dest", "empty");
                    c.setRequestProperty("Sec-Fetch-Mode", "cors");
                    c.setRequestProperty("Sec-Fetch-Site", "cross-site");
                }
                c.setRequestProperty("TE", "trailers");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (!playback) c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                byte[] body = new byte[]{0x78, 0};
                c.setFixedLengthStreamingMode(body.length);
                OutputStream bo = c.getOutputStream();
                bo.write(body);
                bo.close();
                int code = c.getResponseCode();
                if (code == 416) break;                      // дальше данных нет
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                InputStream in = c.getInputStream();
                long got = 0;
                try {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); got += n; }
                } finally {
                    in.close();
                }
                pos += got;
                if (total > 0) {
                    int pct = (int) (pos * 100 / total);
                    if (pct / 10 != lastPct / 10) { lastPct = pct; l.onStatus("⬇ " + pct + "%"); }
                    if (pos >= total) break;
                } else if (got < CHUNK) {
                    break;
                }
                if (got == 0) break;
            }
        } finally {
            o.close();
        }
        if (pos == 0) throw new IOException("пустой ответ");
    }
}
