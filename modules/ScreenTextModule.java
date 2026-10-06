package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import im.manus.universalhost.IPlugin;

import java.util.Map;

/**
 * Извлечение текста экрана для озвучки.
 *
 * Правила отбора текста повторяют идеи TalkBack (Apache License 2.0,
 * AccessibilityNodeInfoUtils.getNodeText / hasText / isVisible / hasMinimumPixelsVisibleOnScreen),
 * но реализованы заново на платформенных классах, без androidx и Guava:
 * - описание (contentDescription) важнее текста, пустые и состоящие из пробелов значения не считаются;
 * - у пустого поля ввода берётся подсказка (hint);
 * - текст контейнеров-коллекций (списки, таблицы) не читается, читаются их элементы;
 * - невидимые узлы вместе с потомками, поля паролей и узлы меньше 15 px пропускаются;
 * - если у узла есть описание, его потомки не дублируются.
 *
 * Модуль сам ничего не озвучивает: execute() возвращает строку с текстом активного окна,
 * по одному фрагменту на строку. Необязательный параметр data: "max_chars" (Number) — предел длины.
 */
public class ScreenTextModule implements IPlugin {

    private static final int MIN_VISIBLE_PIXELS = 15;
    private static final int MAX_DEPTH = 80;
    private static final int MAX_NODES = 6000;
    private static final int DEFAULT_MAX_CHARS = 50000;

    private volatile AccessibilityService service;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "ScreenText"; }
    @Override public int getVersion() { return 1; }
    @Override public String getDescription() {
        return "Извлечение текста активного окна для озвучки (правила отбора как в TalkBack)";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        service = (context instanceof AccessibilityService) ? (AccessibilityService) context : null;
    }

    @Override
    public void stop() {
        service = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService svc) {
        service = svc;
    }

    @Override
    public Object execute(Map<String, ?> data) {
        AccessibilityService s = service;
        if (s == null) return "";
        int maxChars = DEFAULT_MAX_CHARS;
        if (data != null) {
            Object v = data.get("max_chars");
            if (v instanceof Number) maxChars = Math.max(1, ((Number) v).intValue());
        }
        AccessibilityNodeInfo root = null;
        try {
            root = s.getRootInActiveWindow();
            if (root == null) return "";
            Walk w = new Walk(maxChars);
            w.visit(root, 0);
            if (w.out.length() > maxChars) w.out.setLength(maxChars);
            return w.out.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            recycle(root);
        }
    }

    // ---------------------------------------------------------------- обход

    private static final class Walk {
        final StringBuilder out = new StringBuilder();
        final int maxChars;
        int nodes;
        String last = "";

        Walk(int maxChars) { this.maxChars = maxChars; }

        boolean full() { return out.length() >= maxChars || nodes >= MAX_NODES; }

        void visit(AccessibilityNodeInfo n, int depth) {
            if (n == null || full() || depth > MAX_DEPTH) return;
            nodes++;
            if (!n.isVisibleToUser() || n.isPassword()) return;   // вместе с потомками

            CharSequence text = speakable(n);
            boolean descend = true;
            if (text != null && hasMinimumSize(n)) {
                append(text);
                // описание узла заменяет его содержимое, если это не контейнер
                if (nonBlank(n.getContentDescription()) != null
                        && n.getCollectionInfo() == null
                        && !n.isScrollable()) {
                    descend = false;
                }
            }
            if (!descend) return;

            int count = n.getChildCount();
            for (int i = 0; i < count && !full(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c == null) continue;
                try {
                    visit(c, depth + 1);
                } finally {
                    recycle(c);
                }
            }
        }

        void append(CharSequence cs) {
            String s = cs.toString().trim();
            if (s.isEmpty() || s.equals(last)) return;      // подряд идущие повторы не нужны
            last = s;
            out.append(s).append('\n');
        }
    }

    /** Текст узла по правилам TalkBack: описание, затем текст, затем подсказка. */
    private static CharSequence speakable(AccessibilityNodeInfo n) {
        if (n.getCollectionInfo() != null) return null;     // заголовки коллекций не читаем
        CharSequence cd = nonBlank(n.getContentDescription());
        if (cd != null) return cd;
        CharSequence t = nonBlank(n.getText());
        if (t != null) return t;
        if (Build.VERSION.SDK_INT >= 26) return nonBlank(n.getHintText());
        return null;
    }

    private static CharSequence nonBlank(CharSequence cs) {
        return (!TextUtils.isEmpty(cs) && TextUtils.getTrimmedLength(cs) > 0) ? cs : null;
    }

    private static boolean hasMinimumSize(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return Math.abs(r.height()) >= MIN_VISIBLE_PIXELS && Math.abs(r.width()) >= MIN_VISIBLE_PIXELS;
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo n) {
        if (n == null) return;
        try { n.recycle(); } catch (Throwable ignored) { }
    }
}
