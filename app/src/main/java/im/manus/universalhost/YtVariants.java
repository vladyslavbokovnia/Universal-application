package im.manus.universalhost;

import android.content.Context;
import android.content.Intent;

/**
 * Варианты загрузки ролика YouTube в одном месте: и меню долгого нажатия на 🔗 в плеере, и «Поделиться» из скрипта
 * YouTube TTS (тип text/x-ytaudio или text/x-votaudio, поля clean и engine) попадают в одну и ту же очередь через
 * этот класс. Новый вариант добавляется здесь: в {@link #ALL} (меню плеера) и в {@link #submitFromIntent} (скрипт).
 */
public final class YtVariants {
    private YtVariants() { }

    public static final String MIME = "text/x-ytaudio";
    /** Тип кнопки 🎬: mp4 с видео YouTube и переводом Яндекса вместо звука. */
    public static final String MIME_VOT = "text/x-votaudio";

    public static final class Variant {
        public final String label;
        public final boolean video;
        public final boolean clean;
        /** Движок очистки «эээ» только для этого ролика: 0 Uhm, 1 Steady, 2 GigaAM; -1: как в настройках. */
        public final int engine;

        Variant(String label, boolean video, boolean clean, int engine) {
            this.label = label;
            this.video = video;
            this.clean = clean;
            this.engine = engine;
        }

        /** Первый символ-значок названия («🎧», «🧹U»). */
        public String glyph() {
            int sp = label.indexOf(' ');
            return sp > 0 ? label.substring(0, sp) : label;
        }
    }

    /** Меню плеера, как в меню скрипта YouTube TTS. */
    public static final Variant[] ALL = {
            new Variant("🎧 Аудио (очистка «эээ» как в настройках модуля)", false, false, -1),
            new Variant("🧹 Аудио, «эээ» вырезать обязательно", false, true, -1),
            new Variant("🧹U Аудио, очистка движком Uhm", false, false, 0),
            new Variant("🧹S Аудио, очистка движком Steady", false, false, 1),
            new Variant("🧹G Аудио, очистка движком GigaAM", false, false, 2),
            new Variant("🎬 Видео с русским переводом Яндекса", true, false, -1),
    };

    public static boolean isYoutube(String u) {
        if (u == null) return false;
        String s = u.toLowerCase();
        return s.startsWith("http") && (s.contains("youtu.be/") || s.contains("youtube.com/"));
    }

    /** В очередь загрузки с выбранным вариантом. false: это не ссылка YouTube. */
    public static boolean submit(Context ctx, String url, String title, Variant v) {
        YtAudioQueue q = YtAudioQueue.get(ctx);
        if (url != null) url = url.trim();
        if (!isYoutube(url)) {
            q.toast("⚠️🔗");
            return false;
        }
        q.submit(url, title, v.clean || v.engine >= 0, v.video, v.engine);
        return true;
    }

    /** Ссылка уже в списке загрузок (не нашлась дорожка, ошибка): пробуем её другим способом. */
    public static void retry(Context ctx, String url, Variant v) {
        YtAudioQueue.get(ctx).submitAgain(url, v.clean || v.engine >= 0, v.video, v.engine);
    }

    /** «Поделиться» из скрипта YouTube TTS: ссылка в тексте, название в теме, варианты в типе и полях. */
    public static boolean submitFromIntent(Context ctx, Intent intent) {
        try {
            boolean video = intent.getBooleanExtra("video", false) || MIME_VOT.equals(intent.getType());
            int engine = intent.getIntExtra("engine", -1);
            boolean clean = intent.getBooleanExtra("clean", false);
            return submit(ctx, intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    new Variant("", video, clean, engine));
        } catch (Throwable t) {
            return false;
        }
    }
}
