package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KeyboardButton — помощник для сторонней клавиатуры (Gboard, в том числе пропатченного): одна маленькая
 * полупрозрачная кнопка поверх экрана.
 *
 *  - короткое нажатие — последняя выбранная функция; картинка кнопки показывает, какая это функция;
 *  - долгое нажатие — круговое меню значков без подписей вокруг кнопки: голосовой ввод, клавиатура,
 *    буфер обмена, переводчик, выбор клавиатуры (выбор — касанием значка, касание в стороне закрывает меню);
 *  - перетаскивание кнопки — положение запоминается.
 *
 * Как это работает: «клавиатура» скрывает/показывает любую экранную клавиатуру через
 * AccessibilityService.getSoftKeyboardController(); «выбор клавиатуры» открывает системное окно выбора
 * способа ввода; остальные функции нажимают соответствующий значок в окне клавиатуры (по подписи значка
 * для доступности — «Голосовой ввод», «Буфер обмена», «Перевод» и т.п.). Подписи зависят от языка и версии
 * Gboard, поэтому в настройках есть «Какие значки видит модуль».
 *
 * При остановке модуля режим показа клавиатуры возвращается в «авто», чтобы клавиатура не осталась скрытой.
 */
public class KeyboardButtonModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_KeyboardButton";          // настройки, которые пишет хост
    private static final String STATE = "keyboard_button_state";          // положение кнопки и последняя функция

    private static final int F_VOICE = 0;
    private static final int F_KEYBOARD = 1;
    private static final int F_CLIPBOARD = 2;
    private static final int F_TRANSLATE = 3;
    private static final int F_PICKER = 4;
    private static final int FUNCTIONS = 5;

    private static final long LONG_PRESS_MS = 450L;
    private static final long VISIBILITY_DEBOUNCE_MS = 200L;
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_NODES = 5000;          // потолок обхода дерева (веб-страницы очень глубокие и большие)
    private static final long EVENT_HINT_MS = 4000L;    // сколько доверяем событию «фокус в поле ввода»

    // подписи значков (в нижнем регистре, часть слова); Gboard на разных языках и в разных версиях называет их по-разному
    private static final String[] VOICE_KEYS = {"голосов", "голос", "voice", "микрофон", "microphone", "диктов", "dictat"};
    private static final String[] CLIPBOARD_KEYS = {"буфер", "clipboard"};
    private static final String[] TRANSLATE_KEYS = {"перевод", "перевест", "перекла", "translat"};

    private AccessibilityService svc;
    private Handler main;
    private SharedPreferences sp;
    private SharedPreferences st;
    private WindowManager wm;
    private FloatButton button;
    private WindowManager.LayoutParams buttonLp;
    private Ring ring;
    private boolean buttonShown;
    private boolean active;

    private int sizeDp = 52;
    private int alphaPercent = 55;
    private boolean onlyWhenInput;
    private boolean haptic = true;
    private long menuTimeoutMs = 6000L;
    private int last = F_KEYBOARD;
    private int sizePx;
    private long inputEventAt;
    private int evTotal, evInput;           // счётчики для проверки «Что видит модуль в поле ввода»
    private String infoProblem;             // что было не так с конфигурацией службы при запуске (null — всё в порядке)

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "KeyboardButton"; }
    @Override public int getVersion() { return 5; }
    @Override public String getDescription() {
        return "Кнопка для Gboard: клавиатура, голосовой ввод, буфер обмена, переводчик, выбор клавиатуры";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        if (!(context instanceof AccessibilityService)) return;
        svc = (AccessibilityService) context;
        main = new Handler(Looper.getMainLooper());
        sp = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        st = svc.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        loadConfig();
        last = clamp(st.getInt("last", F_KEYBOARD), 0, FUNCTIONS - 1);
        active = true;
        repairServiceInfo();
        try {
            svc.getSoftKeyboardController().setShowMode(AccessibilityService.SHOW_MODE_AUTO);
            sizePx = dp(sizeDp);
            if (!onlyWhenInput) showButton(); else updateVisibility();
            main.postDelayed(raiseTask, 2000L);       // другие модули добавляют окна позже и могут оказаться поверх
        } catch (Throwable t) {
            toast("KeyboardButton: не удалось запуститься: " + t);
            stop();
        }
    }

    @Override
    public void stop() {
        active = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        if (svc != null) {
            try { svc.getSoftKeyboardController().setShowMode(AccessibilityService.SHOW_MODE_AUTO); } catch (Throwable ignored) { }
        }
        dismissRing();
        hideButton();
        svc = null;
    }

    /** execute() — выполнить последнюю выбранную функцию, как короткое нажатие. */
    @Override
    public Object execute(Map<String, ?> data) {
        if (active) runFunction(last);
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        if (!active || event == null) return;
        evTotal++;
        if (!onlyWhenInput) return;
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED || t == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                || t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            try {
                AccessibilityNodeInfo src = event.getSource();      // само событие надёжнее поиска по дереву (особенно в WebView)
                if (src != null && isInputNode(src)) { inputEventAt = SystemClock.uptimeMillis(); evInput++; }
            } catch (Throwable ignored) { }
        }
        if (t == AccessibilityEvent.TYPE_WINDOWS_CHANGED || t == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            main.removeCallbacks(visibilityTask);
            main.postDelayed(visibilityTask, VISIBILITY_DEBOUNCE_MS);
            main.postDelayed(visibilityTask, 800L);                 // клавиатура появляется позже события — проверяем ещё раз
            if (SystemClock.uptimeMillis() - inputEventAt < EVENT_HINT_MS) main.postDelayed(visibilityTask, EVENT_HINT_MS + 200L);
        }
    }

    private final Runnable visibilityTask = new Runnable() {
        @Override public void run() { if (active) updateVisibility(); }
    };

    private final Runnable raiseTask = new Runnable() {
        @Override public void run() {
            if (!active || !buttonShown) return;
            hideButton();
            showButton();
        }
    };

    // ---------------------------------------------------------------- настройки (экран в хосте)

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int dp(int v) { return (int) (v * svc.getResources().getDisplayMetrics().density + 0.5f); }

    private void loadConfig() {
        sizeDp = clamp(sp.getInt("button_size", 52), 32, 96);
        alphaPercent = clamp(sp.getInt("button_alpha", 55), 20, 100);
        onlyWhenInput = sp.getInt("visibility", 0) == 1;
        haptic = sp.getBoolean("haptic", true);
        menuTimeoutMs = clamp(sp.getInt("menu_timeout", 6), 2, 15) * 1000L;
    }

    /**
     * Конфигурация службы (какие события присылать, можно ли читать окна) задана в accessibility_config.xml,
     * но на устройстве она может оказаться битой: например, другой код хоста переписывает serviceInfo
     * (перехват движений геймпада на Android 14+) и затирает типы событий или флаг окон — тогда служба жива,
     * кнопка рисуется, а событий и окон приходит ноль. Проверяем и чиним прямо из модуля.
     */
    private void repairServiceInfo() {
        try {
            AccessibilityServiceInfo info = svc.getServiceInfo();
            if (info == null) { infoProblem = "serviceInfo отсутствует"; return; }
            int needFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            int needCaps = AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
                    | AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
                    | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS;
            StringBuilder bad = new StringBuilder();
            if (info.eventTypes != AccessibilityEvent.TYPES_ALL_MASK) bad.append("типы событий=").append(info.eventTypes).append("; ");
            if ((info.flags & needFlags) != needFlags) bad.append("флаги=").append(info.flags).append("; ");
            if ((info.getCapabilities() & needCaps) != needCaps) bad.append("возможности=").append(info.getCapabilities()).append("; ");
            if (bad.length() == 0) { infoProblem = null; return; }
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.flags |= needFlags;
            setCapabilities(info, info.getCapabilities() | needCaps);
            svc.setServiceInfo(info);
            infoProblem = bad.toString().trim();
        } catch (Throwable t) {
            infoProblem = "ошибка проверки конфигурации: " + t;
        }
    }

    /** setCapabilities в SDK, которым собирает CI, ещё нет (появился в API 33) — ставим рефлексией: метод или поле. */
    private static void setCapabilities(AccessibilityServiceInfo info, int caps) {
        try {
            AccessibilityServiceInfo.class.getMethod("setCapabilities", int.class).invoke(info, Integer.valueOf(caps));
            return;
        } catch (Throwable ignored) { }
        for (String name : new String[]{"capabilities", "mCapabilities"}) {
            try {
                java.lang.reflect.Field f = AccessibilityServiceInfo.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(info, caps);
                return;
            } catch (Throwable ignored) { }
        }
    }

    private String serviceInfoText() {
        try {
            AccessibilityServiceInfo info = svc.getServiceInfo();
            if (info == null) return "служба: конфигурации нет";
            return "служба: типы событий=" + info.eventTypes + ", флаги=" + info.flags
                    + ", возможности=" + info.getCapabilities();
        } catch (Throwable t) {
            return "служба: ошибка чтения конфигурации: " + t;
        }
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("🔘 Кнопка"));
        l.add(SettingItem.slider("button_size", "📏 Размер кнопки", "dp", 32, 96, 4, 52));
        l.add(SettingItem.slider("button_alpha", "🌫 Непрозрачность кнопки", "%", 20, 100, 5, 55));
        l.add(SettingItem.choice("visibility", "👁 Когда показывать", "",
                Arrays.asList("Всегда", "Только когда есть поле ввода или клавиатура"), 0));
        l.add(SettingItem.toggle("haptic", "📳 Виброотклик", "При долгом нажатии", true));
        l.add(SettingItem.slider("menu_timeout", "⏱ Автозакрытие меню, сек", "", 2, 15, 1, 6));
        l.add(SettingItem.section("🧪 Проверка"));
        l.add(SettingItem.action("diag", "🔍 Какие значки видит модуль", "Подписи кнопок в окне клавиатуры; открой клавиатуру и нажми"));
        l.add(SettingItem.action("diag_input", "🔎 Что видит модуль в поле ввода", "Нажми, открой браузер и поставь курсор в поле; через 8 секунд отчёт появится и скопируется в буфер"));
        l.add(SettingItem.action("reset_pos", "↩ Вернуть кнопку на место", ""));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (svc == null || !active) return;
        if ("diag".equals(key)) { toast(diagText()); return; }
        if ("diag_input".equals(key)) {
            toast("KeyboardButton: открой браузер и поставь курсор в поле ввода — проверю через 8 секунд");
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!active || svc == null) return;
                    String report = inputDiagText();
                    copyToClipboard(report);
                    toast(report + "\n(скопировано в буфер обмена)");
                }
            }, 8000L);
            return;
        }
        if ("reset_pos".equals(key)) {
            st.edit().remove("posX").remove("posY").apply();
            hideButton();
            if (!onlyWhenInput || inputActive()) showButton();
            return;
        }
        Context c = svc;       // размер, прозрачность, режим показа — проще перезапустить
        stop();
        init(c);
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        Icons.draw(c, F_KEYBOARD, size / 2f, size / 2f, size * 0.8f, Color.WHITE, p);
        return b;
    }

    // ---------------------------------------------------------------- служебное

    private void toast(final String text) {
        if (main == null || svc == null) return;
        final Context c = svc;
        main.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
            }
        });
    }

    private void showButton() {
        if (buttonShown || svc == null) return;
        button = new FloatButton(svc);
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int defX = dm.widthPixels - sizePx - dp(8);
        int defY = (int) (dm.heightPixels * 0.55f);
        buttonLp = new WindowManager.LayoutParams(sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        buttonLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        buttonLp.x = clamp(st.getInt("posX", defX), 0, Math.max(0, dm.widthPixels - sizePx));
        buttonLp.y = clamp(st.getInt("posY", defY), 0, Math.max(0, dm.heightPixels - sizePx));
        try {
            wm.addView(button, buttonLp);
            buttonShown = true;
        } catch (Throwable t) {
            button = null;
            toast("KeyboardButton: не удалось показать кнопку: " + t);
        }
    }

    private void hideButton() {
        if (button != null && wm != null) {
            try { wm.removeView(button); } catch (Throwable ignored) { }
        }
        button = null;
        buttonShown = false;
    }

    private void savePosition() {
        if (buttonLp == null) return;
        st.edit().putInt("posX", buttonLp.x).putInt("posY", buttonLp.y).apply();
    }

    private void updateVisibility() {
        boolean want = !onlyWhenInput || inputActive();
        if (want && !buttonShown) {
            showButton();
        } else if (!want && buttonShown) {
            dismissRing();
            hideButton();
        }
    }

    private void buzz(View v) {
        if (haptic && v != null) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    // ---------------------------------------------------------------- клавиатура и её окно

    private boolean imeVisible() {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private boolean inputActive() {
        if (imeVisible()) return true;
        if (SystemClock.uptimeMillis() - inputEventAt < EVENT_HINT_MS) return true;
        return findInputField(true) != null;
    }

    /** Узел — поле ввода? В WebView у веб-полей бывает не isEditable, а класс EditText или inputType. */
    private boolean isInputNode(AccessibilityNodeInfo n) {
        try {
            if (n.isEditable()) return true;
            CharSequence cn = n.getClassName();
            if (cn != null && cn.toString().contains("EditText")) return true;
            return n.getInputType() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Обход в ширину без рекурсии и без ограничения глубины (у веб-страниц она больше 40). */
    private AccessibilityNodeInfo findInput(AccessibilityNodeInfo root, boolean focusedOnly) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        AccessibilityNodeInfo firstVisible = null;
        int seen = 0;
        while (!q.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (isInputNode(n)) {
                if (n.isFocused()) return n;
                if (!focusedOnly && firstVisible == null && n.isVisibleToUser()) firstVisible = n;
            }
            int c = n.getChildCount();
            for (int i = 0; i < c; i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return firstVisible;
    }

    /** Поле ввода: сначала активное окно, потом остальные окна приложений. focusedOnly — только сфокусированное. */
    private AccessibilityNodeInfo findInputField(boolean focusedOnly) {
        try {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f == null) f = findInput(root, focusedOnly);
                if (f != null) return f;
            }
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo f = findInput(w.getRoot(), focusedOnly);
                if (f != null) return f;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private AccessibilityNodeInfo imeRoot() {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return w.getRoot();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private boolean matches(AccessibilityNodeInfo n, String[] keys) {
        CharSequence d = n.getContentDescription();
        CharSequence t = n.getText();
        String s = ((d == null ? "" : d.toString()) + " " + (t == null ? "" : t.toString())).toLowerCase(Locale.ROOT);
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String[] keys, int depth) {
        if (node == null || depth > 40) return null;
        if (node.isVisibleToUser() && matches(node, keys)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(node.getChild(i), keys, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 4 && n != null; i++) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            n = n.getParent();
        }
        return false;
    }

    private boolean clickImeNode(String[] keys) {
        AccessibilityNodeInfo root = imeRoot();
        if (root == null) return false;
        AccessibilityNodeInfo node = find(root, keys, 0);
        return node != null && clickNode(node);
    }

    /** Настоящее касание по центру узла: ACTION_CLICK на EditText/веб-поле часто не открывает клавиатуру. */
    private void tapNode(AccessibilityNodeInfo node) {
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.isEmpty()) return;
            Path p = new Path();
            p.moveTo(r.exactCenterX(), r.exactCenterY());
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0L, 50L)).build();
            svc.dispatchGesture(g, null, null);
        } catch (Throwable ignored) { }
    }

    /** Показывает клавиатуру: фокус на поле (сфокусированное или первое видимое), клик, а если не помогло — тап. */
    private boolean focusInputField() {
        try {
            AccessibilityNodeInfo field = findInputField(true);
            if (field == null) field = findInputField(false);
            if (field == null) return false;
            final AccessibilityNodeInfo f = field;
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            f.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (active && !imeVisible()) tapNode(f);
                }
            }, 250L);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureKeyboardShown() {
        AccessibilityService.SoftKeyboardController c = svc.getSoftKeyboardController();
        if (c.getShowMode() == AccessibilityService.SHOW_MODE_HIDDEN) c.setShowMode(AccessibilityService.SHOW_MODE_AUTO);
        if (!imeVisible() && !focusInputField()) toast("KeyboardButton: нет поля ввода, где можно открыть клавиатуру");
    }

    private void toggleKeyboard() {
        AccessibilityService.SoftKeyboardController c = svc.getSoftKeyboardController();
        if (c.getShowMode() == AccessibilityService.SHOW_MODE_HIDDEN) {
            c.setShowMode(AccessibilityService.SHOW_MODE_AUTO);
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (active && !imeVisible() && !focusInputField()) toast("KeyboardButton: нет поля ввода, где можно открыть клавиатуру");
                }
            }, 250L);
        } else if (imeVisible()) {
            c.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN);
        } else if (!focusInputField()) {
            toast("KeyboardButton: нет поля ввода, где можно открыть клавиатуру");
        }
    }

    /** Системное окно выбора клавиатуры (способа ввода). */
    private void showPicker() {
        try {
            ((InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
        } catch (Throwable t) {
            toast("KeyboardButton: не удалось открыть выбор клавиатуры: " + t);
        }
    }

    private void imeAction(final String[] keys, final String what) {
        if (imeVisible() && clickImeNode(keys)) return;
        ensureKeyboardShown();
        pollClick(keys, what, 0);
    }

    private void pollClick(final String[] keys, final String what, final int attempt) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!active) return;
                if (clickImeNode(keys)) return;
                if (attempt >= MAX_ATTEMPTS) {
                    toast("KeyboardButton: в клавиатуре не нашёл значок «" + what
                            + "». Открой клавиатуру и нажми «Какие значки видит модуль» в настройках");
                } else {
                    pollClick(keys, what, attempt + 1);
                }
            }
        }, 300L);
    }

    private void runFunction(int f) {
        if (svc == null) return;
        last = f;
        st.edit().putInt("last", f).apply();
        if (button != null) button.invalidate();
        switch (f) {
            case F_VOICE: imeAction(VOICE_KEYS, "голосовой ввод"); break;
            case F_CLIPBOARD: imeAction(CLIPBOARD_KEYS, "буфер обмена"); break;
            case F_TRANSLATE: imeAction(TRANSLATE_KEYS, "переводчик"); break;
            case F_PICKER: showPicker(); break;
            default: toggleKeyboard(); break;
        }
    }

    private String diagText() {
        AccessibilityNodeInfo root = imeRoot();
        if (root == null) return "KeyboardButton: окно клавиатуры не найдено — откройте клавиатуру и нажмите ещё раз";
        List<String> names = new ArrayList<String>();
        collectNames(root, names, 0);
        if (names.isEmpty()) return "KeyboardButton: в окне клавиатуры нет подписанных кнопок";
        StringBuilder sb = new StringBuilder("Значки клавиатуры (" + names.size() + "):\n");
        for (int i = 0; i < names.size() && i < 30; i++) sb.append(names.get(i)).append(i + 1 < names.size() ? " | " : "");
        return sb.toString();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("KeyboardButton", text));
        } catch (Throwable ignored) { }
    }

    /** Отчёт о том, что модуль видит в окне с полем ввода: окна, активный корень, узлы, поля, события. */
    private String inputDiagText() {
        StringBuilder sb = new StringBuilder("KeyboardButton v5, поле ввода:\n");
        try {
            repairServiceInfo();
            sb.append(serviceInfoText()).append('\n');
            if (infoProblem != null) sb.append("конфигурация службы была сломана (").append(infoProblem)
                    .append(") — только что исправил, подожди 5 секунд и нажми проверку ещё раз\n");
            int app = 0, ime = 0, ovl = 0, other = 0;
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                int t = w.getType();
                if (t == AccessibilityWindowInfo.TYPE_APPLICATION) app++;
                else if (t == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ime++;
                else if (t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) ovl++;
                else other++;
            }
            sb.append("окна: приложений ").append(app).append(", клавиатура ").append(ime)
                    .append(", оверлеи ").append(ovl).append(", другие ").append(other).append('\n');
            sb.append("события: всего ").append(evTotal).append(", из полей ввода ").append(evInput)
                    .append(", режим «только с полем» ").append(onlyWhenInput ? "да" : "нет").append('\n');
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root == null) { sb.append("активное окно: корня нет"); return sb.toString(); }
            sb.append("активное окно: ").append(root.getPackageName()).append('\n');
            AccessibilityNodeInfo fi = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            sb.append("FOCUS_INPUT: ").append(fi == null ? "нет" : "есть, " + fi.getClassName()).append('\n');
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
            ArrayDeque<Integer> qd = new ArrayDeque<Integer>();
            q.add(root);
            qd.add(Integer.valueOf(0));
            int seen = 0, maxDepth = 0, inputs = 0, focused = 0, web = 0;
            String first = "";
            while (!q.isEmpty() && seen < 8000) {
                AccessibilityNodeInfo n = q.poll();
                int d = qd.poll().intValue();
                seen++;
                if (d > maxDepth) maxDepth = d;
                CharSequence cn = n.getClassName();
                if (cn != null && cn.toString().contains("WebView")) web++;
                if (isInputNode(n)) {
                    inputs++;
                    if (n.isFocused()) focused++;
                    if (first.length() == 0) {
                        first = cn + (n.isEditable() ? ", editable" : "") + (n.isFocused() ? ", в фокусе" : "")
                                + (n.isVisibleToUser() ? ", видим" : ", не видим") + ", глубина " + d;
                    }
                }
                int c = n.getChildCount();
                for (int i = 0; i < c; i++) {
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) { q.add(ch); qd.add(Integer.valueOf(d + 1)); }
                }
            }
            sb.append("узлов ").append(seen).append(q.isEmpty() ? "" : "+ (обход оборван)")
                    .append(", глубина ").append(maxDepth).append(", WebView ").append(web).append('\n');
            sb.append("полей ввода ").append(inputs).append(", в фокусе ").append(focused);
            if (first.length() > 0) sb.append("\nпервое: ").append(first);
            sb.append("\nклавиатура видна: ").append(imeVisible() ? "да" : "нет");
        } catch (Throwable t) {
            sb.append("ошибка: ").append(t);
        }
        return sb.toString();
    }

    private void collectNames(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 40 || out.size() >= 60) return;
        CharSequence d = node.getContentDescription();
        if (d != null && d.toString().trim().length() > 1 && node.isVisibleToUser()) out.add(d.toString().trim());
        for (int i = 0; i < node.getChildCount(); i++) collectNames(node.getChild(i), out, depth + 1);
    }

    // ---------------------------------------------------------------- кнопка

    private final class FloatButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int slop;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging, longFired;

        private final Runnable longTask = new Runnable() {
            @Override public void run() {
                if (dragging) return;
                longFired = true;
                buzz(FloatButton.this);
                showRing();
            }
        };

        FloatButton(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setAlpha(alphaPercent / 100f);        // вся кнопка вместе со значком полупрозрачная
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float border = Math.max(1.5f, sizePx * 0.025f);
            float r = Math.min(cx, cy) - border;
            bg.setStyle(Paint.Style.FILL);
            bg.setColor(Color.argb(200, 0, 0, 0));
            canvas.drawCircle(cx, cy, r, bg);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(Color.WHITE);
            line.setStrokeWidth(border);
            canvas.drawCircle(cx, cy, r, line);
            Icons.draw(canvas, last, cx, cy, r * 1.25f, Color.WHITE, line);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = buttonLp.x;
                    startY = buttonLp.y;
                    dragging = false;
                    longFired = false;
                    postDelayed(longTask, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && !longFired && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        removeCallbacks(longTask);
                    }
                    if (dragging) {
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        buttonLp.x = clamp(startX + (int) dx, 0, Math.max(0, dm.widthPixels - sizePx));
                        buttonLp.y = clamp(startY + (int) dy, 0, Math.max(0, dm.heightPixels - sizePx));
                        try { wm.updateViewLayout(this, buttonLp); } catch (Throwable ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longTask);
                    if (dragging) savePosition();
                    else if (!longFired) runFunction(last);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longTask);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------------------------------------------------------------- круговое меню

    private final Runnable dismissRingTask = new Runnable() {
        @Override public void run() { dismissRing(); }
    };

    private void showRing() {
        dismissRing();
        if (svc == null || buttonLp == null) return;
        ring = new Ring(svc, buttonLp.x + sizePx / 2f, buttonLp.y + sizePx / 2f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        try {
            wm.addView(ring, lp);
            main.postDelayed(dismissRingTask, menuTimeoutMs);
        } catch (Throwable t) {
            ring = null;
        }
    }

    private void dismissRing() {
        if (main != null) main.removeCallbacks(dismissRingTask);
        if (ring != null && wm != null) {
            try { wm.removeView(ring); } catch (Throwable ignored) { }
        }
        ring = null;
    }

    private final class Ring extends View {
        private final float anchorX, anchorY;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] px = new float[FUNCTIONS];
        private final float[] py = new float[FUNCTIONS];
        private float btn;
        private int pressed = -1;

        Ring(Context c, float anchorX, float anchorY) {
            super(c);
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            line.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            btn = Math.max(dp(24), sizePx * 0.55f);
            float radius = sizePx * 0.5f + btn + dp(10);
            float extent = radius + btn + dp(4);
            float cx = Math.max(extent, Math.min(w - extent, anchorX));
            float cy = Math.max(extent, Math.min(h - extent, anchorY));
            for (int i = 0; i < FUNCTIONS; i++) {
                double a = Math.toRadians(-90.0 + i * 360.0 / FUNCTIONS);      // первая сверху, дальше по часовой
                px[i] = cx + (float) Math.cos(a) * radius;
                py[i] = cy + (float) Math.sin(a) * radius;
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.argb(40, 0, 0, 0));
            for (int i = 0; i < FUNCTIONS; i++) {
                boolean on = i == pressed;
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(on ? Color.WHITE : Color.argb(215, 0, 0, 0));
                canvas.drawCircle(px[i], py[i], btn, fill);
                line.setStyle(Paint.Style.STROKE);
                line.setColor(on ? Color.BLACK : Color.WHITE);
                line.setStrokeWidth(i == last ? dp(3) : dp(1));        // последняя выбранная функция обведена жирнее
                canvas.drawCircle(px[i], py[i], btn - dp(1), line);
                Icons.draw(canvas, i, px[i], py[i], btn * 1.3f, on ? Color.BLACK : Color.WHITE, line);
            }
        }

        private int hit(float x, float y) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            float r = btn + dp(8);
            for (int i = 0; i < FUNCTIONS; i++) {
                float dx = x - px[i], dy = y - py[i], d = dx * dx + dy * dy;
                if (d <= r * r && d < bestD) { bestD = d; best = i; }
            }
            return best;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    int h = hit(e.getX(), e.getY());
                    if (h != pressed) { pressed = h; invalidate(); }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int h = hit(e.getX(), e.getY());
                    dismissRing();
                    if (h >= 0) runFunction(h);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    dismissRing();
                    return true;
                default:
                    return true;
            }
        }
    }

    // ---------------------------------------------------------------- значки (рисуются кодом, без ресурсов)

    private static final class Icons {
        /** Значок функции kind в центре (cx, cy) размером s; p — кисть, её параметры меняются. */
        static void draw(Canvas c, int kind, float cx, float cy, float s, int color, Paint p) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, s * 0.07f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            switch (kind) {
                case F_VOICE: voice(c, cx, cy, s, p); break;
                case F_CLIPBOARD: clipboard(c, cx, cy, s, p); break;
                case F_TRANSLATE: translate(c, cx, cy, s, p); break;
                case F_PICKER: picker(c, cx, cy, s, p); break;
                default: keyboard(c, cx, cy, s, p); break;
            }
        }

        private static void keyboard(Canvas c, float cx, float cy, float s, Paint p) {
            float w = s * 0.62f, h = s * 0.4f;
            c.drawRoundRect(new RectF(cx - w, cy - h, cx + w, cy + h), s * 0.08f, s * 0.08f, p);
            p.setStyle(Paint.Style.FILL);
            for (int row = 0; row < 2; row++) {
                for (int i = 0; i < 4; i++) {
                    c.drawCircle(cx - w * 0.6f + i * w * 0.4f, cy - h * 0.45f + row * h * 0.5f, s * 0.035f, p);
                }
            }
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(cx - w * 0.45f, cy + h * 0.55f, cx + w * 0.45f, cy + h * 0.55f, p);
        }

        private static void voice(Canvas c, float cx, float cy, float s, Paint p) {
            c.drawRoundRect(new RectF(cx - s * 0.13f, cy - s * 0.42f, cx + s * 0.13f, cy + s * 0.06f), s * 0.13f, s * 0.13f, p);
            c.drawArc(new RectF(cx - s * 0.26f, cy - s * 0.22f, cx + s * 0.26f, cy + s * 0.28f), 0f, 180f, false, p);
            c.drawLine(cx, cy + s * 0.28f, cx, cy + s * 0.42f, p);
            c.drawLine(cx - s * 0.14f, cy + s * 0.42f, cx + s * 0.14f, cy + s * 0.42f, p);
        }

        private static void clipboard(Canvas c, float cx, float cy, float s, Paint p) {
            c.drawRoundRect(new RectF(cx - s * 0.3f, cy - s * 0.36f, cx + s * 0.3f, cy + s * 0.42f), s * 0.06f, s * 0.06f, p);
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(cx - s * 0.14f, cy - s * 0.46f, cx + s * 0.14f, cy - s * 0.28f), s * 0.04f, s * 0.04f, p);
            p.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 3; i++) {
                float y = cy - s * 0.06f + i * s * 0.17f;
                c.drawLine(cx - s * 0.17f, y, cx + s * 0.17f, y, p);
            }
        }

        private static void translate(Canvas c, float cx, float cy, float s, Paint p) {
            RectF a = new RectF(cx - s * 0.46f, cy - s * 0.4f, cx + s * 0.06f, cy + s * 0.1f);
            RectF b = new RectF(cx - s * 0.06f, cy - s * 0.1f, cx + s * 0.46f, cy + s * 0.4f);
            c.drawRoundRect(a, s * 0.06f, s * 0.06f, p);
            c.drawRoundRect(b, s * 0.06f, s * 0.06f, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(s * 0.34f);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText("A", (a.left + a.right) / 2f, (a.top + a.bottom) / 2f - (fm.ascent + fm.descent) / 2f, p);
            c.drawText("\u6587", (b.left + b.right) / 2f, (b.top + b.bottom) / 2f - (fm.ascent + fm.descent) / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setTypeface(Typeface.DEFAULT);
        }

        /** Выбор клавиатуры: глобус (способ ввода) и стрелка переключения. */
        private static void picker(Canvas c, float cx, float cy, float s, Paint p) {
            c.drawCircle(cx, cy, s * 0.34f, p);
            c.drawOval(new RectF(cx - s * 0.15f, cy - s * 0.34f, cx + s * 0.15f, cy + s * 0.34f), p);
            c.drawLine(cx - s * 0.34f, cy, cx + s * 0.34f, cy, p);
            c.drawLine(cx + s * 0.40f, cy + s * 0.20f, cx + s * 0.52f, cy + s * 0.32f, p);
            c.drawLine(cx + s * 0.52f, cy + s * 0.32f, cx + s * 0.40f, cy + s * 0.44f, p);
        }
    }
}
