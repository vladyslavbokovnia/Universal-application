package im.manus.universalhost;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Уведомления без текста: только эмодзи и значки. Любой текст пропускается через {@link #glyphs}: слова, цифры и
 * знаки препинания отбрасываются, остаются пиктограммы. Подробности смотрите на плитке (двойной тап) и в журнале модуля.
 * Картинки в тостах Android 11+ не показывает (пользовательские виды из фона игнорируются), поэтому логотипами
 * служат сами эмодзи.
 */
public final class YtNotify {

    private static final Handler H = new Handler(Looper.getMainLooper());

    private YtNotify() { }

    private static boolean isGlyph(int cp) {
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return true;      // эмодзи, флаги, символы
        if (cp >= 0x2190 && cp <= 0x21FF) return true;        // стрелки
        if (cp >= 0x2300 && cp <= 0x23FF) return true;        // технические знаки (⏳, ⌛, ⏸)
        if (cp >= 0x25A0 && cp <= 0x25FF) return true;        // геометрические фигуры (▶, ◀)
        if (cp >= 0x2600 && cp <= 0x27BF) return true;        // разные символы и dingbats (✅, ⚠, ✂)
        if (cp >= 0x2B00 && cp <= 0x2BFF) return true;        // стрелки и фигуры (⬇, ⭐)
        return false;
    }

    /** Оставляет в тексте только эмодзи и значки. Пустая строка, если их нет. */
    public static String glyphs(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isGlyph(cp)) sb.appendCodePoint(cp);
            else if ((cp == 0xFE0F || cp == 0x200D) && sb.length() > 0) sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** Показывает только значки из текста; если их нет, общий значок {@code fallback}. */
    public static void show(final Context ctx, String text, String fallback) {
        String g = glyphs(text);
        final String out = g.length() == 0 ? fallback : g;
        final Context app = ctx.getApplicationContext();
        H.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(app, out, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
            }
        });
    }

    public static void show(Context ctx, String text) {
        show(ctx, text, "\uD83D\uDD14");
    }
}
