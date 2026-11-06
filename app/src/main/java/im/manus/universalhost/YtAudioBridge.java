package im.manus.universalhost;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Network;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Скачивание аудиодорожки YouTube нужного языка через NewPipeExtractor (его же использует приложение NewPipe).
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

    private static boolean inited;

    /** Сеть, через которую качает текущий поток (null: по умолчанию). Нужна для режима «только Wi-Fi». */
    private static final ThreadLocal<Network> NET = new ThreadLocal<Network>();

    private static HttpURLConnection open(String u) throws IOException {
        URL url = new URL(u);
        Network n = NET.get();
        return (HttpURLConnection) (n != null ? n.openConnection(url) : url.openConnection());
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

    /** Запускает загрузку в фоне. lang — код языка дорожки (ru), outDir — папка, net — сеть для загрузки (null: любая). */
    public static void start(final Context ctx, final String url, final String title, final String lang,
                             final String outDir, final boolean fallbackOriginal, final Network net, final Listener l) {
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                NET.set(net);
                try {
                    String path = doRun(app, url, title, lang, outDir, fallbackOriginal, l);
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

    private static synchronized void ensureInit() {
        if (inited) return;
        NewPipe.init(new Dl(), Localization.DEFAULT, ContentCountry.DEFAULT);
        inited = true;
    }

    private static boolean sameLang(AudioStream s, String lang) {
        Locale lo = s.getAudioLocale();
        if (lo != null && lang.equalsIgnoreCase(lo.getLanguage())) return true;
        String id = s.getAudioTrackId();
        return id != null && id.toLowerCase(Locale.ROOT).startsWith(lang.toLowerCase(Locale.ROOT) + ".");
    }

    private static boolean isOriginal(AudioStream s) {
        return s.getAudioTrackType() == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL;
    }

    /** Лучший по формату (M4A предпочтительнее: его индексирует медиатека) и битрейту. */
    private static AudioStream best(List<AudioStream> list) {
        AudioStream b = null;
        for (AudioStream s : list) {
            if (b == null) { b = s; continue; }
            boolean sm = s.getFormat() == MediaFormat.M4A;
            boolean bm = b.getFormat() == MediaFormat.M4A;
            if (sm && !bm) b = s;
            else if (sm == bm && s.getAverageBitrate() > b.getAverageBitrate()) b = s;
        }
        return b;
    }

    private static String doRun(Context ctx, String url, String title, String lang, String outDir,
                              boolean fallbackOriginal, Listener l) throws Exception {
        if (lang == null || lang.trim().length() == 0) lang = "ru";
        lang = lang.trim();
        ensureInit();
        l.onStatus("🎧 Ищу дорожки…");
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);

        List<AudioStream> all = new ArrayList<AudioStream>();
        for (AudioStream s : info.getAudioStreams()) {
            if (s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) all.add(s);
        }
        if (all.isEmpty()) throw new UserError("Нет доступных аудиопотоков");

        List<AudioStream> wanted = new ArrayList<AudioStream>();
        Set<String> langs = new LinkedHashSet<String>();
        for (AudioStream s : all) {
            Locale lo = s.getAudioLocale();
            if (lo != null) langs.add(lo.getDisplayLanguage(new Locale("ru")));
            if (sameLang(s, lang)) wanted.add(s);
        }
        boolean original = false;
        AudioStream pick = best(wanted);
        if (pick == null) {
            if (!fallbackOriginal) {
                throw new UserError("Нет дорожки «" + lang + "»" + (langs.isEmpty() ? " (у видео одна дорожка)"
                        : ". Есть: " + join(langs)));
            }
            List<AudioStream> orig = new ArrayList<AudioStream>();
            for (AudioStream s : all) if (isOriginal(s) || s.getAudioLocale() == null) orig.add(s);
            pick = best(orig.isEmpty() ? all : orig);
            original = true;
        }

        String ext = pick.getFormat() != null ? pick.getFormat().getSuffix() : "m4a";
        String name = safeName(title != null && title.trim().length() > 0 ? title : info.getName());
        if (original) name = name + " (оригинал)";
        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) throw new UserError("Не создать папку " + dir);
        File out = new File(dir, name + "." + ext);
        for (int n = 2; out.exists(); n++) out = new File(dir, name + " (" + n + ")." + ext);
        String base = out.getName().substring(0, out.getName().length() - ext.length() - 1);

        l.onStatus("⬇ Качаю" + (original ? " оригинал" : "") + "…");
        File part = new File(dir, base + ".part");
        try {
            download(pick.getContent(), part, l);
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
            String th = bestThumb(info.getThumbnails());
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
    private static String bestThumb(List<Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return null;
        Image best = null;
        Image smallest = null;
        for (Image im : imgs) {
            if (smallest == null || im.getHeight() < smallest.getHeight()) smallest = im;
            if (im.getHeight() <= 720 && (best == null || im.getHeight() > best.getHeight())) best = im;
        }
        Image r = best != null ? best : smallest;
        return r == null ? null : r.getUrl();
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
    private static void download(String url, File to, Listener l) throws Exception {
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
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(u)) {
                    c.setRequestProperty("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null));
                } else {
                    c.setRequestProperty("User-Agent", FIREFOX_UA);
                }
                if (YoutubeParsingHelper.isWebStreamingUrl(u)) {
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

    /** Запросы самого извлекателя (HttpURLConnection, как у простого загрузчика). */
    private static final class Dl extends Downloader {
        @Override public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection c = open(request.url());
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(request.httpMethod());
            c.setRequestProperty("User-Agent", FIREFOX_UA);
            Map<String, List<String>> reqHeaders = request.headers();
            if (reqHeaders == null) reqHeaders = new java.util.HashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> e : reqHeaders.entrySet()) {
                List<String> v = e.getValue();
                if (v == null || v.isEmpty()) continue;
                StringBuilder sb = new StringBuilder();
                for (String x : v) {
                    if (sb.length() > 0) sb.append("Cookie".equalsIgnoreCase(e.getKey()) ? "; " : ", ");
                    sb.append(x);
                }
                c.setRequestProperty(e.getKey(), sb.toString());
            }
            byte[] data = request.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                OutputStream o = c.getOutputStream();
                o.write(data);
                o.close();
            }
            int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                body = bos.toString("UTF-8");
            }
            Map<String, List<String>> hs = new java.util.HashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) hs.put(e.getKey(), e.getValue());
            }
            return new Response(code, c.getResponseMessage(), hs, body, c.getURL().toString());
        }
    }
}
