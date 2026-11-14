package im.manus.deps;

import im.manus.universalhost.dep.YtExtractor;

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
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Реализация {@link YtExtractor} на NewPipeExtractor (его же использует приложение NewPipe). Хост создаёт её через
 * DexClassLoader из dep-newpipe.apk; сети нужны только через {@link YtExtractor.Opener}, который передаёт хост.
 */
public final class NewPipeImpl implements YtExtractor {

    private static final String FIREFOX_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

    /** Открыватель соединений текущего вызова (извлечение идёт в том же потоке, что и fetch). */
    private static final ThreadLocal<Opener> OPENER = new ThreadLocal<Opener>();

    private static boolean inited;

    public NewPipeImpl() { }

    private static synchronized void ensureInit() {
        if (inited) return;
        NewPipe.init(new Dl(), Localization.DEFAULT, ContentCountry.DEFAULT);
        inited = true;
    }

    private static HttpURLConnection connect(String url) throws IOException {
        Opener o = OPENER.get();
        return o != null ? o.open(url) : (HttpURLConnection) new URL(url).openConnection();
    }

    @Override public Video fetch(String url, Opener opener) throws Exception {
        ensureInit();
        OPENER.set(opener);
        try {
            StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
            Video v = new Video();
            try { v.title = String.valueOf(info.getName()); } catch (Throwable ignored) { }
            try { v.uploader = String.valueOf(info.getUploaderName()); } catch (Throwable ignored) { }
            try {
                String d = info.getDescription() == null ? null : info.getDescription().getContent();
                if (d != null) v.description = d.length() > 300 ? d.substring(0, 300) : d;
            } catch (Throwable ignored) { }

            for (AudioStream s : info.getAudioStreams()) {
                if (!(s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP)) continue;
                Audio a = new Audio();
                a.url = s.getContent();
                MediaFormat f = s.getFormat();
                a.suffix = f != null ? f.getSuffix() : null;
                a.m4a = f == MediaFormat.M4A;
                a.bitrate = s.getAverageBitrate();
                Locale lo = s.getAudioLocale();
                a.lang = lo != null ? lo.getLanguage() : null;
                a.trackId = s.getAudioTrackId();
                AudioTrackType t = s.getAudioTrackType();
                a.trackType = t != null ? t.name() : null;
                v.audio.add(a);
            }

            try {
                List<Image> imgs = info.getThumbnails();
                if (imgs != null) {
                    for (Image im : imgs) {
                        Thumb t = new Thumb();
                        t.url = im.getUrl();
                        t.height = im.getHeight();
                        v.thumbs.add(t);
                    }
                }
            } catch (Throwable ignored) { }
            return v;
        } finally {
            OPENER.remove();
        }
    }

    @Override public String userAgent(String url) {
        return YoutubeParsingHelper.isVisionOsStreamingUrl(url) ? YoutubeParsingHelper.getVisionOsUserAgent(null) : null;
    }

    @Override public boolean webStreaming(String url) {
        return YoutubeParsingHelper.isWebStreamingUrl(url);
    }

    /** Запросы самого извлекателя (HttpURLConnection, как у простого загрузчика). */
    private static final class Dl extends Downloader {
        @Override public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection c = connect(request.url());
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
