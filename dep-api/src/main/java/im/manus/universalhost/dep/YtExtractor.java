package im.manus.universalhost.dep;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Интерфейс зависимости NewPipeExtractor. Реализация (im.manus.deps.NewPipeImpl) лежит в dep-newpipe.apk, хост грузит её
 * через Deps. Здесь только типы JDK: классы самого NewPipeExtractor наружу не выходят, поэтому хост от него не зависит.
 */
public interface YtExtractor {

    /** Как зависимость открывает соединения: открывает хост (он учитывает выбранную сеть, например только Wi-Fi). */
    interface Opener {
        HttpURLConnection open(String url) throws IOException;
    }

    /** Одна звуковая дорожка (только прямые ссылки PROGRESSIVE_HTTP). */
    final class Audio {
        public String url;
        /** Расширение файла: m4a, webm; null, если формат неизвестен. */
        public String suffix;
        public boolean m4a;
        public int bitrate;
        /** Язык дорожки (ru, en...) или null. */
        public String lang;
        public String trackId;
        /** ORIGINAL, DUBBED, DESCRIPTIVE, SECONDARY или null (единственная дорожка). */
        public String trackType;
    }

    final class Thumb {
        public String url;
        public int height;
    }

    final class Video {
        public String title = "";
        public String uploader = "";
        /** Начало описания (до 300 символов): по нему определяется язык ролика. */
        public String description = "";
        public final List<Audio> audio = new ArrayList<Audio>();
        public final List<Thumb> thumbs = new ArrayList<Thumb>();
    }

    /** Получает данные ролика. Блокирующий вызов: только из фонового потока. */
    Video fetch(String url, Opener opener) throws Exception;

    /** User-Agent для адреса потока, если ему нужен особый (VisionOS); null: обычный. */
    String userAgent(String url);

    /** Адрес потока веб-клиента YouTube: нужны заголовки Origin, Referer и Sec-Fetch. */
    boolean webStreaming(String url);
}
