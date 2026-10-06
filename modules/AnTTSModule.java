package im.manus.plugins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
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
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.Toast;
import im.manus.universalhost.IPlugin;
import im.manus.universalhost.ISettingsProvider;
import im.manus.universalhost.SettingItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AnTTS как DEX-модуль (порт github.com/vladyslavbokovnia/AnTTS, MIT).
 *
 * - полоса-сенсор: фон, полоса прогресса; тап — старт/пауза, горизонтальный свайп — к соседнему блоку.
 *   Ширина, высота и положение полосы настраиваются;
 * - цифры месячного мобильного трафика (ГБ) — отдельное окно: не зависят от ширины полосы, прогресса и
 *   зоны касания, не перехватывают касания, обведены тёмным контуром, поэтому видны и на светлом фоне;
 * - озвучка страницы блок за блоком; блоки идут в порядке дерева приложения (как у TalkBack);
 *   режим «весь текст экрана» читает всё, что видно;
 * - во время чтения страница постоянно и медленно прокручивается (скорость в настройках);
 * - озвучка поля ввода: предложение у курсора;
 * - если чтение не стартует, тост объясняет причину (движок речи, пустое окно, сколько узлов отсеяно).
 */
public class AnTTSModule implements IPlugin, ISettingsProvider {

    private static final String PREFS = "module_AnTTS";            // настройки, которые пишет хост
    private static final long REFRESH_DEBOUNCE_MS = 200L;
    private static final long TRAFFIC_REFRESH_MS = 2000L;
    private static final long CHUNK_MS = 1000L;                     // длина одного отрезка непрерывного жеста
    private static final int MAX_INVISIBLE_RETRIES = 20;
    private static final int SCROLL_SMOOTH = 0;
    private static final int SCROLL_PAGES = 1;
    private static final int SCROLL_NONE = 2;

    // значения по умолчанию совпадают со схемой настроек ниже
    private SharedPreferences cfg;
    private int barHeightDp = 28;
    private int barWidthPercent = 100;
    private int backgroundAlpha = 82;
    private int progressAlpha = 90;
    private int trafficStartDay = 1;
    private int digitsCorner;
    private int digitsSizeSp = 27;
    private boolean speakInput = true;
    private boolean extractAll;
    private boolean orderByTree = true;
    private int scrollMode = SCROLL_SMOOTH;
    private int scrollSpeedDp = 40;
    private boolean barBottom;

    private AccessibilityService svc;
    private Handler main;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Bar bar;
    private Digits digits;
    private String trafficText = "";
    private volatile boolean active;

    private final List<Block> blocks = new ArrayList<Block>();
    private final Set<String> spoken = new HashSet<String>();
    private int current;
    private boolean reading;
    private boolean speakingInput;
    private String lastSnapshot = "";
    private AccessibilityNodeInfo pendingInputNode;
    private int lastRecordedCursor = -1;
    private long inputRevision;
    private int inputSentenceIndex;
    private String activeUtteranceId;
    private long speechGeneration;
    private int invisibleRetries;

    // непрерывная прокрутка
    private boolean scrollWanted;
    private boolean scrollRunning;
    private int scrollIdle;
    private String scrollSnapshotBefore = "";
    private float chainX;
    private float chainMinY;

    // диагностика
    private Stats lastStats;
    private boolean lastRootNull;
    private String lastRootPkg = "";
    private String lastError;

    // ---------------------------------------------------------------- IPlugin

    @Override public String getName() { return "AnTTS"; }
    @Override public int getVersion() { return 3; }
    @Override public String getDescription() {
        return "Озвучка страницы и поля ввода, полоса прогресса, учёт мобильного трафика";
    }
    @Override public String getIconName() { return "ic_launcher"; }

    @Override
    public void init(Context context) {
        stop();
        if (!(context instanceof AccessibilityService)) return;   // нужны окно и дерево узлов службы
        svc = (AccessibilityService) context;
        main = new Handler(Looper.getMainLooper());
        cfg = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadConfig();
        blocks.clear();
        spoken.clear();
        current = 0;
        reading = false;
        speakingInput = false;
        lastSnapshot = "";
        pendingInputNode = null;
        lastRecordedCursor = -1;
        invisibleRetries = 0;
        scrollWanted = false;
        scrollRunning = false;
        active = true;
        try {
            createTts();
            bar = new Bar();
            bar.show();
            digits = new Digits();
            digits.show();
            trafficText = monthlyText();
            digits.setText(trafficText);
            main.postDelayed(trafficTask, TRAFFIC_REFRESH_MS);
            refreshBlocks();
            main.postDelayed(raiseTask, 2000L);     // другие модули добавляют окна позже и могут оказаться поверх
        } catch (Throwable t) {
            stop();
        }
    }

    @Override
    public void stop() {
        active = false;
        scrollWanted = false;
        scrollRunning = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        reading = false;
        speakingInput = false;
        speechGeneration++;
        activeUtteranceId = null;
        pendingInputNode = null;
        if (bar != null) { bar.hide(); bar = null; }
        if (digits != null) { digits.hide(); digits = null; }
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) { }
            try { tts.shutdown(); } catch (Throwable ignored) { }
            tts = null;
        }
        ttsReady = false;
        blocks.clear();
        svc = null;
    }

    /** Старт/пауза чтения, как тап по полосе. */
    @Override
    public Object execute(Map<String, ?> data) {
        if (active) startOrPause();
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service) {
        if (!active || event == null) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs != null && pkgCs.toString().equals(svc.getPackageName())) return;   // окна самого хоста

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && isEditable(source)) {
                pendingInputNode = source;
                int from = event.getFromIndex();
                if (from >= 0) lastRecordedCursor = from + event.getAddedCount();
                inputRevision++;
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && isEditable(source)) {
                pendingInputNode = source;
                int from = event.getFromIndex();
                if (from >= 0) lastRecordedCursor = from;
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && isEditable(source)) {
                pendingInputNode = source;
                if (source.getTextSelectionStart() >= 0) lastRecordedCursor = source.getTextSelectionStart();
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            pendingInputNode = null;
            lastRecordedCursor = -1;
            if (reading) {
                reading = false;
                stopAutoScroll();
                if (tts != null) tts.stop();
                if (bar != null) bar.setPlaying(false);
            }
        }
        // дерево перечитываем не на каждое событие (в хосте приходят все типы), а с задержкой
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (!reading || blocks.isEmpty()) {
                main.removeCallbacks(refreshTask);
                main.postDelayed(refreshTask, REFRESH_DEBOUNCE_MS);
            }
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() { if (active) refreshBlocks(); }
    };

    private final Runnable raiseTask = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (bar != null) bar.raise();
            if (digits != null) digits.raise();       // цифры всегда над полосой
        }
    };

    private final Runnable trafficTask = new Runnable() {
        @Override public void run() {
            if (!active || digits == null) return;
            String t = monthlyText();
            if (!t.equals(trafficText)) {            // не перерисовываем, если цифра не изменилась
                trafficText = t;
                digits.setText(t);
            }
            main.postDelayed(this, TRAFFIC_REFRESH_MS);
        }
    };

    // ---------------------------------------------------------------- настройки (экран в хосте)

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void loadConfig() {
        barHeightDp = clamp(cfg.getInt("bar_height_dp", 28), 16, 64);
        barWidthPercent = clamp(cfg.getInt("bar_width_percent", 100), 10, 100);
        backgroundAlpha = clamp(cfg.getInt("background_alpha", 82), 0, 100);
        progressAlpha = clamp(cfg.getInt("progress_alpha", 90), 10, 100);
        trafficStartDay = clamp(cfg.getInt("traffic_start_day", 1), 1, 31);
        digitsCorner = clamp(cfg.getInt("digits_corner", 0), 0, 5);
        digitsSizeSp = clamp(cfg.getInt("digits_size", 27), 12, 48);
        speakInput = cfg.getBoolean("speak_input", true);
        extractAll = cfg.getInt("extract_mode", 0) == 1;
        orderByTree = cfg.getInt("order_mode", 0) == 0;
        scrollMode = clamp(cfg.getInt("scroll_mode", 0), 0, 2);
        scrollSpeedDp = clamp(cfg.getInt("scroll_speed", 40), 10, 200);
        barBottom = cfg.getInt("bar_position", 0) == 1;
    }

    @Override
    public List<SettingItem> getSettingsSchema() {
        List<SettingItem> l = new ArrayList<SettingItem>();
        l.add(SettingItem.section("Полоса (сенсор и прогресс)"));
        l.add(SettingItem.choice("bar_position", "Положение полосы", "", Arrays.asList("Сверху", "Снизу"), 0));
        l.add(SettingItem.slider("bar_height_dp", "Высота полосы", "dp", 16, 64, 2, 28));
        l.add(SettingItem.slider("bar_width_percent", "Ширина полосы", "%", 10, 100, 5, 100));
        l.add(SettingItem.slider("background_alpha", "Непрозрачность фона", "%", 0, 100, 5, 82));
        l.add(SettingItem.slider("progress_alpha", "Непрозрачность полосы прогресса", "%", 10, 100, 5, 90));
        l.add(SettingItem.section("Цифры мобильного трафика"));
        l.add(SettingItem.choice("digits_corner", "Где показывать", "",
                Arrays.asList("Сверху по центру", "Сверху слева", "Сверху справа",
                        "Снизу по центру", "Снизу слева", "Снизу справа"), 0));
        l.add(SettingItem.slider("digits_size", "Размер цифр", "sp", 12, 48, 2, 27));
        l.add(SettingItem.slider("traffic_start_day", "День начала учётного периода", "", 1, 31, 1, 1));
        l.add(SettingItem.section("Чтение"));
        l.add(SettingItem.choice("extract_mode", "Какой текст читать", "",
                Arrays.asList("Основной текст страницы", "Весь текст экрана (как TalkBack)"), 0));
        l.add(SettingItem.choice("order_mode", "Порядок блоков", "",
                Arrays.asList("По дереву приложения (как TalkBack)", "По положению на экране"), 0));
        l.add(SettingItem.choice("scroll_mode", "Прокрутка", "",
                Arrays.asList("Постоянная медленная", "По страницам", "Без прокрутки"), 0));
        l.add(SettingItem.slider("scroll_speed", "Скорость прокрутки", "dp/с", 10, 200, 5, 40));
        l.add(SettingItem.toggle("speak_input", "Озвучивать поле ввода",
                "Читает предложение у курсора (по тапу на полосу, когда выбрано поле ввода)", true));
        return l;
    }

    @Override
    public void onSettingChanged(String key) {
        if (svc == null || !active) return;
        Context c = svc;
        stop();
        init(c);        // размеры и вид полосы читаются при создании
    }

    @Override
    public Bitmap createIcon(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = size / 96f;
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f * s);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawRoundRect(new RectF(10 * s, 30 * s, 86 * s, 66 * s), 8 * s, 8 * s, p);   // полоса
        p.setStyle(Paint.Style.FILL);
        Path tri = new Path();                                                          // «играть»
        tri.moveTo(40 * s, 40 * s);
        tri.lineTo(40 * s, 56 * s);
        tri.lineTo(56 * s, 48 * s);
        tri.close();
        c.drawPath(tri, p);
        return b;
    }

    // ---------------------------------------------------------------- речь

    private void toast(final String text) {
        if (main == null || svc == null) return;
        final Context c = svc;
        main.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
            }
        });
    }

    private void createTts() {
        ttsReady = false;
        try {
            tts = new TextToSpeech(svc, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) { onTtsInit(status); }
            });
        } catch (Throwable t) {
            tts = null;
            toast("AnTTS: не удалось создать движок речи: " + t);
        }
    }

    private void restartTts() {
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) { }
            tts = null;
        }
        createTts();
    }

    private void onTtsInit(int status) {
        if (!active || tts == null) return;
        if (status != TextToSpeech.SUCCESS) {
            toast("AnTTS: движок речи не запустился (код " + status + ")");
            return;
        }
        ttsReady = true;
        tts.setLanguage(Locale.getDefault());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override public void onError(final String utteranceId) {
                if (main == null) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (active && utteranceId != null && utteranceId.equals(activeUtteranceId)) {
                            speakingInput = false;
                            reading = false;
                            stopAutoScroll();
                            if (bar != null) bar.setPlaying(false);
                        }
                    }
                });
            }

            @Override public void onDone(final String utteranceId) {
                if (main == null) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!active || utteranceId == null || !utteranceId.equals(activeUtteranceId)) return;
                        if (speakingInput) {
                            speakingInput = false;
                            if (bar != null) bar.setPlaying(false);
                        } else if (reading) {
                            advanceAfterSpeech();
                        }
                    }
                });
            }
        });
    }

    private boolean isEditable(AccessibilityNodeInfo node) {
        if (node.isEditable()) return true;
        CharSequence cn = node.getClassName();
        String className = cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
        return className.contains("edittext") || className.contains("autocompletetextview");
    }

    private AccessibilityNodeInfo findActiveInputNode() {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && isEditable(focused)) return focused;
        }
        if (pendingInputNode != null && pendingInputNode.refresh() && isEditable(pendingInputNode)) {
            return pendingInputNode;
        }
        try {
            for (AccessibilityWindowInfo window : svc.getWindows()) {
                AccessibilityNodeInfo winRoot = window.getRoot();
                if (winRoot == null) continue;
                AccessibilityNodeInfo focused = winRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null && isEditable(focused)) return focused;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Озвучивает предложение у курсора в выбранном поле ввода. false — поля нет, читаем страницу. */
    private boolean speakPendingInput() {
        if (!speakInput || !ttsReady) return false;
        AccessibilityNodeInfo node = findActiveInputNode();
        if (node == null) return false;
        node.refresh();
        CharSequence cs = node.getText();
        String text = cs == null ? "" : cs.toString();
        if (text.trim().isEmpty()) return false;

        List<SentenceSpan> sentences = InputSentenceParser.parse(text);
        if (sentences.isEmpty()) return false;

        int cursor;
        if (node.getTextSelectionStart() >= 0) cursor = node.getTextSelectionStart();
        else if (lastRecordedCursor >= 0 && lastRecordedCursor <= text.length()) cursor = lastRecordedCursor;
        else cursor = text.length();

        inputSentenceIndex = InputSentenceParser.indexAt(sentences, cursor);
        speakInputSentence(node, inputSentenceIndex);
        return true;
    }

    private void speakInputSentence(AccessibilityNodeInfo node, int index) {
        CharSequence cs = node.getText();
        List<SentenceSpan> sentences = InputSentenceParser.parse(cs == null ? "" : cs.toString());
        if (sentences.isEmpty() || tts == null) return;
        inputSentenceIndex = clamp(index, 0, sentences.size() - 1);
        String text = sentences.get(inputSentenceIndex).text;
        if (text.trim().isEmpty()) return;

        speakingInput = true;
        if (bar != null) bar.setPlaying(true);
        activeUtteranceId = "antts-input-" + inputRevision + "-" + System.nanoTime();
        int rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
        if (rc != TextToSpeech.SUCCESS) {
            speakingInput = false;
            if (bar != null) bar.setPlaying(false);
            toast("AnTTS: движок речи отказался читать (код " + rc + ")");
        }
    }

    // ---------------------------------------------------------------- чтение страницы

    private int dp(int v) { return (int) (v * svc.getResources().getDisplayMetrics().density); }

    private int statusBarHeight() {
        int id = svc.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? clamp(svc.getResources().getDimensionPixelSize(id), 0, 160) : dp(24);
    }

    private int navigationBarHeight() {
        int id = svc.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? clamp(svc.getResources().getDimensionPixelSize(id), 0, 160) : dp(48);
    }

    private int topEdge() {
        return barBottom ? statusBarHeight() : Math.max(statusBarHeight(), dp(barHeightDp));
    }

    private ScreenMetrics screenMetrics() {
        android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int top = topEdge();
        int bottom = barBottom
                ? dm.heightPixels - Math.max(navigationBarHeight(), dp(barHeightDp))
                : dm.heightPixels - navigationBarHeight();
        return new ScreenMetrics(dm.widthPixels, dm.heightPixels, dm.density, top, bottom);
    }

    private int indexOfBlock(Block b) {
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).node.equals(b.node)) return i;
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).text.equals(b.text)) return i;
        return -1;
    }

    private void refreshBlocks() {
        if (svc == null) return;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        lastRootNull = root == null;
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        lastRootPkg = pkg == null ? "?" : pkg.toString();

        Stats st = new Stats();
        List<Block> fresh;
        try {
            fresh = Extractor.extract(root, screenMetrics(), extractAll, orderByTree, st);
        } catch (Throwable e) {
            lastError = e.toString();
            return;
        }
        lastError = null;
        lastStats = st;
        if (fresh.isEmpty()) {
            if (!reading) { blocks.clear(); lastSnapshot = ""; }     // не читаем устаревшие узлы прошлой страницы
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Block b : fresh) {
            sb.append(b.text).append('|').append(b.rect.top).append('|').append(b.rect.bottom)
                    .append('|').append(b.node.getViewIdResourceName()).append('\u0000');
        }
        String snapshot = sb.toString();
        if (!snapshot.equals(lastSnapshot)) {
            Block old = (current >= 0 && current < blocks.size()) ? blocks.get(current) : null;
            blocks.clear();
            blocks.addAll(fresh);
            lastSnapshot = snapshot;
            int idx = (reading && old != null) ? indexOfBlock(old) : -1;     // озвучиваемый блок остаётся «текущим»
            current = idx >= 0 ? idx : clamp(current, 0, blocks.size() - 1);
            if (bar != null) bar.setProgress(current, blocks.size());
        }
    }

    private String diagText() {
        if (lastRootNull) return "AnTTS: нет доступа к содержимому окна (корневой узел пуст)";
        if (lastError != null) return "AnTTS: ошибка разбора окна: " + lastError;
        Stats s = lastStats;
        if (s == null) return "AnTTS: окно ещё не разобрано, попробуйте ещё раз";
        return "AnTTS: текст не найден. Окно " + lastRootPkg + ": узлов " + s.visited
                + ", с текстом " + s.withText + ", пропущено панелями " + s.panel
                + ", кнопками " + s.control + ", невидимых " + s.invisible
                + ", вне экрана " + s.offscreen;
    }

    private void startOrPause() {
        if (reading || speakingInput) {
            reading = false;
            speakingInput = false;
            stopAutoScroll();
            if (tts != null) tts.stop();
            if (bar != null) bar.setPlaying(false);
            return;
        }
        if (!ttsReady) {
            toast("AnTTS: движок речи не готов, запускаю заново. Нажмите ещё раз через пару секунд");
            restartTts();
            return;
        }
        if (speakPendingInput()) return;
        refreshBlocks();
        if (blocks.isEmpty()) { toast(diagText()); return; }
        toast("AnTTS: блоков " + blocks.size());        // временная диагностика
        current = 0;
        spoken.clear();
        reading = true;
        invisibleRetries = 0;
        scrollIdle = 0;
        if (bar != null) bar.setPlaying(true);
        speakCurrent();
        if (scrollMode == SCROLL_SMOOTH) startAutoScroll();
    }

    private void speakCurrent() {
        if (!reading || blocks.isEmpty() || tts == null) return;
        current = clamp(current, 0, blocks.size() - 1);
        Block block = blocks.get(current);
        if (!block.node.isVisibleToUser()) {
            if (++invisibleRetries > MAX_INVISIBLE_RETRIES) {      // блок так и не показался — идём дальше
                invisibleRetries = 0;
                if (current + 1 < blocks.size()) { current++; speakCurrent(); } else finishReading();
                return;
            }
            if (scrollMode == SCROLL_PAGES) bringIntoView(block.node);
            final long generation = speechGeneration;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (active && reading && speechGeneration == generation) speakCurrent();
                }
            }, 180L);
            return;
        }
        invisibleRetries = 0;
        block.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        activeUtteranceId = "antts-block-" + current + "-" + (speechGeneration++) + "-" + System.nanoTime();
        int rc = tts.speak(block.text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
        if (rc != TextToSpeech.SUCCESS) {
            toast("AnTTS: движок речи отказался читать (код " + rc + ")");
            finishReading();
            return;
        }
        spoken.add(block.text);
        if (bar != null) bar.setProgress(current, blocks.size());
    }

    private void advanceAfterSpeech() {
        if (!reading) return;
        if (scrollMode == SCROLL_SMOOTH) { advanceSmooth(0); return; }
        if (current + 1 < blocks.size()) {
            current++;
            speakCurrent();
        } else if (scrollMode == SCROLL_PAGES) {
            scrollAndLoadNextPage();
        } else {
            finishReading();
        }
    }

    /** Постоянная прокрутка: после блока заново читаем экран и берём следующий за только что озвученным. */
    private void advanceSmooth(final int attempt) {
        if (!active || !reading) return;
        Block done = (current >= 0 && current < blocks.size()) ? blocks.get(current) : null;
        refreshBlocks();
        int next = nextIndexAfter(done);
        if (next >= 0) {
            current = next;
            speakCurrent();
            return;
        }
        // следующего блока пока нет на экране — ждём, пока прокрутка принесёт новый текст
        if ((scrollRunning || scrollWanted) && attempt < 40) {
            main.postDelayed(new Runnable() {
                @Override public void run() { advanceSmooth(attempt + 1); }
            }, 400L);
            return;
        }
        finishReading();
    }

    private int nextIndexAfter(Block done) {
        int idx = done == null ? -1 : indexOfBlock(done);
        if (idx >= 0) return idx + 1 < blocks.size() ? idx + 1 : -1;
        // озвученный блок уже уехал за край экрана: берём первый ещё не читанный
        for (int i = 0; i < blocks.size(); i++) {
            if (!spoken.contains(blocks.get(i).text)) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- непрерывная прокрутка

    private void startAutoScroll() {
        scrollWanted = true;
        scrollIdle = 0;
        if (!scrollRunning) beginScrollChain();
    }

    private void stopAutoScroll() {
        scrollWanted = false;       // цепочка жестов сама закончится на ближайшем отрезке (не позже секунды)
    }

    private final Runnable scrollRestart = new Runnable() {
        @Override public void run() {
            if (active && reading && scrollWanted && !scrollRunning) beginScrollChain();
        }
    };

    private void scheduleScrollRestart(long delayMs) {
        if (main == null || !active || !reading || !scrollWanted) return;
        main.removeCallbacks(scrollRestart);
        main.postDelayed(scrollRestart, delayMs);
    }

    private float chunkPx() {
        return scrollSpeedDp * svc.getResources().getDisplayMetrics().density * (CHUNK_MS / 1000f);
    }

    /** Прокрутка не должна уносить озвучиваемый блок за верхний край, пока он читается. */
    private boolean scrollAllowed() {
        if (blocks.isEmpty() || current < 0 || current >= blocks.size()) return true;
        AccessibilityNodeInfo n = blocks.get(current).node;
        try {
            if (!n.refresh()) return true;
        } catch (Throwable t) {
            return true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r.top > topEdge() + dp(16);
    }

    private void beginScrollChain() {
        if (!active || !reading || !scrollWanted || svc == null) { scrollRunning = false; return; }
        if (!scrollAllowed()) { scrollRunning = false; scheduleScrollRestart(300L); return; }
        AccessibilityNodeInfo target = findScrollable(svc.getRootInActiveWindow());
        if (target == null) { scrollRunning = false; scrollWanted = false; return; }
        Rect r = new Rect();
        target.getBoundsInScreen(r);
        if (r.height() < dp(120)) { scrollRunning = false; scrollWanted = false; return; }
        chainX = (r.left + r.right) / 2f;
        chainMinY = r.top + r.height() * 0.15f;
        float startY = r.bottom - r.height() * 0.15f;
        scrollRunning = true;
        scrollSnapshotBefore = lastSnapshot;
        scrollChunk(null, startY);
    }

    /** Один отрезок непрерывного жеста; на API 26+ палец не отрывается между отрезками. */
    private void scrollChunk(final GestureDescription.StrokeDescription prev, final float fromY) {
        boolean go = active && reading && scrollWanted && scrollAllowed();
        if (!go) {
            if (prev != null) endStroke(prev, fromY);        // отпускаем палец
            else { scrollRunning = false; scheduleScrollRestart(300L); }
            return;
        }
        final float step = chunkPx();
        final float toY = fromY - step;
        final boolean cont = Build.VERSION.SDK_INT >= 26 && (toY - step) >= chainMinY;
        Path path = new Path();
        path.moveTo(chainX, fromY);
        path.lineTo(chainX, toY);
        final GestureDescription.StrokeDescription stroke;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                stroke = (prev == null)
                        ? new GestureDescription.StrokeDescription(path, 0L, CHUNK_MS, cont)
                        : prev.continueStroke(path, 0L, CHUNK_MS, cont);
            } else {
                stroke = new GestureDescription.StrokeDescription(path, 0L, CHUNK_MS);
            }
        } catch (Throwable t) {
            scrollRunning = false;
            scrollWanted = false;
            toast("AnTTS: не удалось построить жест прокрутки: " + t);
            return;
        }
        boolean dispatched = false;
        try {
            dispatched = svc.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                    new AccessibilityService.GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g) {
                            if (cont) scrollChunk(stroke, toY); else onScrollChainDone(true);
                        }
                        @Override public void onCancelled(GestureDescription g) {
                            scrollRunning = false;
                            scheduleScrollRestart(500L);
                        }
                    }, main);
        } catch (Throwable ignored) { }
        if (!dispatched) {
            scrollRunning = false;
            scrollWanted = false;
            toast("AnTTS: система не приняла жест прокрутки (нужно право жестов у службы)");
        }
    }

    private void endStroke(GestureDescription.StrokeDescription prev, float y) {
        boolean ok = false;
        try {
            Path p = new Path();
            p.moveTo(chainX, y);
            p.lineTo(chainX, y);
            GestureDescription.StrokeDescription end = prev.continueStroke(p, 0L, 1L, false);
            ok = svc.dispatchGesture(new GestureDescription.Builder().addStroke(end).build(),
                    new AccessibilityService.GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g) { onScrollChainDone(false); }
                        @Override public void onCancelled(GestureDescription g) {
                            scrollRunning = false;
                            scheduleScrollRestart(500L);
                        }
                    }, main);
        } catch (Throwable ignored) { }
        if (!ok) {
            scrollRunning = false;
            scheduleScrollRestart(500L);
        }
    }

    private void onScrollChainDone(boolean travelled) {
        scrollRunning = false;
        if (!active || !reading) return;
        if (travelled) {
            refreshBlocks();
            if (lastSnapshot.equals(scrollSnapshotBefore)) scrollIdle++; else scrollIdle = 0;
            if (scrollIdle >= 2) { scrollWanted = false; return; }     // страница кончилась
        }
        scheduleScrollRestart(travelled ? 30L : 300L);
    }

    // ---------------------------------------------------------------- прокрутка по страницам

    private void scrollAndLoadNextPage() {
        if (!reading) return;
        AccessibilityNodeInfo scrollable = findScrollable(svc.getRootInActiveWindow());
        if (scrollable == null) { finishReading(); return; }
        final String before = lastSnapshot;
        if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            finishReading();
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!active || !reading) return;
                refreshBlocks();
                if (!blocks.isEmpty() && !lastSnapshot.equals(before)) {
                    current = 0;
                    speakCurrent();
                } else {
                    finishReading();
                }
            }
        }, 500L);
    }

    private void finishReading() {
        reading = false;
        activeUtteranceId = null;
        stopAutoScroll();
        if (bar != null) bar.setPlaying(false);
    }

    private void move(int delta) {
        AccessibilityNodeInfo inputNode = findActiveInputNode();
        if (!reading && inputNode != null) {
            CharSequence cs = inputNode.getText();
            List<SentenceSpan> sentences = InputSentenceParser.parse(cs == null ? "" : cs.toString());
            if (!sentences.isEmpty()) {
                if (tts != null) tts.stop();
                speakInputSentence(inputNode, inputSentenceIndex + delta);
                return;
            }
        }
        if (!ttsReady) { toast("AnTTS: движок речи не готов"); return; }
        refreshBlocks();
        if (blocks.isEmpty()) { toast(diagText()); return; }
        current = clamp(current + delta, 0, blocks.size() - 1);
        boolean started = false;
        if (!reading) {
            reading = true;
            spoken.clear();
            started = true;
            if (bar != null) bar.setPlaying(true);
        }
        if (tts != null) tts.stop();
        speakCurrent();
        if (started && scrollMode == SCROLL_SMOOTH) startAutoScroll();
    }

    private void bringIntoView(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isScrollable() && !node.isVisibleToUser()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                break;
            }
            parent = parent.getParent();
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> candidates = new ArrayList<AccessibilityNodeInfo>();
        collectScrollables(root, candidates, 0);
        AccessibilityNodeInfo best = null;
        long bestArea = -1;
        for (AccessibilityNodeInfo n : candidates) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() >= dp(120) && !isSideRailOrDrawer(n, r)) {
                long a = (long) r.width() * (long) r.height();
                if (a > bestArea) { bestArea = a; best = n; }
            }
        }
        if (best != null) return best;
        for (AccessibilityNodeInfo n : candidates) {       // запасной вариант — самый большой
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long a = (long) r.width() * (long) r.height();
            if (a > bestArea) { bestArea = a; best = n; }
        }
        return best;
    }

    private boolean isSideRailOrDrawer(AccessibilityNodeInfo node, Rect rect) {
        int widthPx = svc.getResources().getDisplayMetrics().widthPixels;
        int railMaxW = dp(96);
        if (rect.left <= 0 && rect.right <= railMaxW) return true;
        if (rect.right >= widthPx && rect.left >= widthPx - railMaxW) return true;
        CharSequence cn = node.getClassName();
        String className = cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
        return className.contains("drawer") || className.contains("navigationrail");
    }

    private void collectScrollables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 100) return;
        if (node.isScrollable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectScrollables(node.getChild(i), out, depth + 1);
    }

    // ---------------------------------------------------------------- мобильный трафик

    private long periodStartMillis() {
        long now = System.currentTimeMillis();
        Calendar cycle = Calendar.getInstance();
        cycle.setTimeInMillis(now);
        cycle.set(Calendar.DAY_OF_MONTH, Math.min(trafficStartDay, cycle.getActualMaximum(Calendar.DAY_OF_MONTH)));
        cycle.set(Calendar.HOUR_OF_DAY, 0);
        cycle.set(Calendar.MINUTE, 0);
        cycle.set(Calendar.SECOND, 0);
        cycle.set(Calendar.MILLISECOND, 0);
        if (now < cycle.getTimeInMillis()) cycle.add(Calendar.MONTH, -1);
        return cycle.getTimeInMillis();
    }

    /** Мобильный трафик (приём + передача) за учётный период, ГБ; «—», если нет доступа к статистике. */
    private String monthlyText() {
        try {
            NetworkStatsManager m = (NetworkStatsManager) svc.getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStats.Bucket b = m.querySummaryForDevice(
                    ConnectivityManager.TYPE_MOBILE, null, periodStartMillis(), System.currentTimeMillis());
            long bytes = Math.max(0L, b.getRxBytes()) + Math.max(0L, b.getTxBytes());
            return String.format(Locale.getDefault(), "%.2f", bytes / 1e9);
        } catch (Throwable t) {
            return "\u2014";
        }
    }

    // ---------------------------------------------------------------- полоса

    private final class Bar {
        private final WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        private final FrameLayout root = new FrameLayout(svc);
        private final View progress = new View(svc);
        private WindowManager.LayoutParams lp;
        private boolean shown;
        private float downX;

        void show() {
            if (shown) return;
            root.setBackgroundColor(Color.TRANSPARENT);
            View back = new View(svc);
            back.setBackgroundColor(Color.argb((int) (backgroundAlpha * 2.55), 0, 0, 0));
            root.addView(back, new FrameLayout.LayoutParams(-1, -1));
            progress.setBackgroundColor(Color.argb((int) (progressAlpha * 2.55), 255, 255, 255));
            root.addView(progress, new FrameLayout.LayoutParams(0, -1));

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            return true;
                        case MotionEvent.ACTION_UP: {
                            float dx = e.getRawX() - downX;
                            if (Math.abs(dx) >= 28f) move(dx > 0 ? 1 : -1); else startOrPause();
                            return true;
                        }
                        default:
                            return true;
                    }
                }
            });

            int screenW = svc.getResources().getDisplayMetrics().widthPixels;
            int width = barWidthPercent >= 100 ? -1 : Math.max(dp(48), screenW * barWidthPercent / 100);
            lp = new WindowManager.LayoutParams(
                    width, dp(barHeightDp), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = (barBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            lp.y = 0;
            wm.addView(root, lp);
            shown = true;
        }

        /** Переставляет окно полосы поверх остальных окон службы (добавленных после неё). */
        void raise() {
            if (!shown || lp == null) return;
            try {
                wm.removeView(root);
                wm.addView(root, lp);
            } catch (Throwable ignored) { }
        }

        void setProgress(final int index, final int total) {
            if (total <= 0 || !shown) return;
            root.post(new Runnable() {
                @Override public void run() {
                    int full = root.getWidth() > 0 ? root.getWidth()
                            : svc.getResources().getDisplayMetrics().widthPixels;
                    int width = clamp((int) (full * (index + 1f) / total), 1, full);
                    FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) progress.getLayoutParams();
                    if (plp.width != width) {
                        plp.width = width;
                        progress.setLayoutParams(plp);
                    }
                }
            });
        }

        void setPlaying(boolean playing) {
            root.setContentDescription(playing ? "AnTTS: чтение включено" : "AnTTS: чтение остановлено");
        }

        void hide() {
            if (!shown) return;
            shown = false;
            try { wm.removeView(root); } catch (Throwable ignored) { }
        }
    }

    /**
     * Цифры трафика — отдельное окно: не зависят от ширины, высоты и положения полосы, не перехватывают
     * касания (касания проходят в полосу или в приложение под ними).
     */
    private final class Digits {
        private final WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        private final OutlinedText view = new OutlinedText(svc, digitsSizeSp);
        private WindowManager.LayoutParams lp;
        private boolean shown;

        void show() {
            if (shown) return;
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            int vertical = digitsCorner >= 3 ? Gravity.BOTTOM : Gravity.TOP;
            int horizontal;
            switch (digitsCorner % 3) {
                case 1: horizontal = Gravity.START; break;
                case 2: horizontal = Gravity.END; break;
                default: horizontal = Gravity.CENTER_HORIZONTAL; break;
            }
            lp.gravity = vertical | horizontal;
            lp.x = (digitsCorner % 3 == 0) ? 0 : dp(8);
            lp.y = dp(2);
            wm.addView(view, lp);
            shown = true;
        }

        void setText(String t) { view.setText(t); }

        void raise() {
            if (!shown || lp == null) return;
            try {
                wm.removeView(view);
                wm.addView(view, lp);
            } catch (Throwable ignored) { }
        }

        void hide() {
            if (!shown) return;
            shown = false;
            try { wm.removeView(view); } catch (Throwable ignored) { }
        }
    }

    /** Белые цифры с тёмным контуром: читаются и на светлом, и на тёмном фоне. */
    private static final class OutlinedText extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float pad;
        private String text = "";

        OutlinedText(Context c, float sizeSp) {
            super(c);
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, c.getResources().getDisplayMetrics());
            Typeface tf = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            fill.setTypeface(tf);
            fill.setTextSize(px);
            fill.setColor(Color.WHITE);
            stroke.setTypeface(tf);
            stroke.setTextSize(px);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, px * 0.16f));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            pad = stroke.getStrokeWidth();
        }

        void setText(String t) {
            if (t == null) t = "";
            if (t.equals(text)) return;
            text = t;
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            Paint.FontMetrics fm = fill.getFontMetrics();
            int w = (int) Math.ceil(fill.measureText(text) + 2 * pad);
            int h = (int) Math.ceil(fm.descent - fm.ascent + 2 * pad);
            setMeasuredDimension(Math.max(w, 1), Math.max(h, 1));
        }

        @Override protected void onDraw(Canvas canvas) {
            Paint.FontMetrics fm = fill.getFontMetrics();
            float y = pad - fm.ascent;
            canvas.drawText(text, pad, y, stroke);
            canvas.drawText(text, pad, y, fill);
        }
    }

    // ---------------------------------------------------------------- модель

    private static final class Stats {
        int visited;
        int invisible;
        int offscreen;
        int panel;
        int withText;
        int control;
        int parentOfText;
        int collected;
        boolean fallback;
    }

    private static final class ScreenMetrics {
        final int screenWidth;
        final int screenHeight;
        final float density;
        final int topBoundary;
        final int bottomBoundary;
        ScreenMetrics(int w, int h, float density, int top, int bottom) {
            this.screenWidth = w; this.screenHeight = h; this.density = density;
            this.topBoundary = top; this.bottomBoundary = bottom;
        }
    }

    private static final class Block {
        final String text;
        final AccessibilityNodeInfo node;
        final Rect rect = new Rect();
        Block(String text, AccessibilityNodeInfo node) {
            this.text = text;
            this.node = node;
            node.getBoundsInScreen(rect);
        }
    }

    private static final class SentenceSpan {
        final int contentStart;
        final int contentEnd;
        final int spanEnd;
        final String text;
        SentenceSpan(int contentStart, int contentEnd, int spanEnd, String text) {
            this.contentStart = contentStart; this.contentEnd = contentEnd;
            this.spanEnd = spanEnd; this.text = text;
        }
    }

    /** Делит текст поля ввода на предложения и находит предложение у курсора. */
    private static final class InputSentenceParser {
        private static final String CLOSERS = "\"'\u00bb\u201d\u2019)]";

        private static boolean isTerminator(char ch) {
            return ch == '.' || ch == '!' || ch == '?' || ch == '\u2026';
        }

        static List<SentenceSpan> parse(String text) {
            List<SentenceSpan> spans = new ArrayList<SentenceSpan>();
            if (text.trim().isEmpty()) return spans;
            int start = 0;
            int len = text.length();
            while (start < len) {
                while (start < len && Character.isWhitespace(text.charAt(start))) start++;
                if (start >= len) break;

                int end = start;
                while (end < len) {
                    char ch = text.charAt(end);
                    if (ch == '\n' || ch == '\r') { end++; break; }
                    if (isTerminator(ch)) {
                        if (ch == '.' && end > start && Character.isDigit(text.charAt(end - 1))
                                && end + 1 < len && Character.isDigit(text.charAt(end + 1))) {
                            end++;                       // десятичная точка внутри числа
                            continue;
                        }
                        while (end < len && isTerminator(text.charAt(end))) end++;
                        while (end < len && CLOSERS.indexOf(text.charAt(end)) >= 0) end++;
                        break;
                    }
                    end++;
                }

                int spanEnd = end;
                while (spanEnd < len && Character.isWhitespace(text.charAt(spanEnd))) spanEnd++;

                String sentence = text.substring(start, end).trim();
                if (!sentence.isEmpty()) spans.add(new SentenceSpan(start, end, spanEnd, sentence));
                start = spanEnd;
            }
            return spans;
        }

        static int indexAt(List<SentenceSpan> sentences, int cursor) {
            if (sentences.isEmpty()) return 0;
            if (cursor <= sentences.get(0).contentStart) return 0;
            for (int i = 0; i < sentences.size(); i++) {
                SentenceSpan s = sentences.get(i);
                if (cursor >= s.contentStart && cursor <= s.spanEnd) return i;
            }
            return sentences.size() - 1;
        }
    }

    /** Отбор текста страницы: «основной» (без панелей, кнопок, реклам, полей ввода) или весь, как у TalkBack. */
    private static final class Extractor {
        private static final int MAX_ANCESTOR_DEPTH = 4;
        private static final int MAX_DEPTH = 100;
        private static final int MAX_NODES = 8000;
        private static final int MIN_VISIBLE_PIXELS = 15;

        private static final String[] TOP_PANEL = {
                "toolbar", "actionbar", "action_bar", "appbar", "app_bar",
                "topbar", "top_bar", "header_bar", "header_container", "header_view",
                "url_bar", "urlbar", "omnibox", "location_bar", "search_bar", "searchbar",
                "tablayout", "tab_layout", "tabbar", "tab_bar", "tabstrip"};
        private static final String[] BOTTOM_PANEL = {
                "bottomnavigation", "bottom_navigation", "bottom_nav", "bottomnav",
                "bottomappbar", "bottom_app_bar", "bottombar", "bottom_bar",
                "footer", "navigation_bar", "navbar", "nav_bar", "snackbar",
                "action_bar_bottom"};
        private static final String[] SIDE_PANEL = {
                "navigationrail", "navigation_rail", "nav_rail", "navrail",
                "drawer", "drawerlayout", "sidebar", "side_bar", "side_nav", "sidenav",
                "sidesheet", "side_sheet", "sidepanel", "side_panel"};
        private static final String[] CONTROL_CLASS = {
                "button", "imagebutton", "switch", "checkbox", "radiobutton",
                "togglebutton", "chip", "tab", "menuitem", "spinner", "seekbar",
                "floatingactionbutton", "ratingbar", "progressbar"};
        private static final String[] AD = {"adview", "ad_view", "banner", "advertisement"};

        static List<Block> extract(AccessibilityNodeInfo root, ScreenMetrics m, boolean all,
                                   boolean byTree, Stats st) {
            List<Block> out = all ? collectAllText(root, m, st) : collectMainText(root, m, st);
            if (out.isEmpty() && !all) {                    // фильтры отсеяли всё — читаем весь видимый текст
                out = collectAllText(root, m, st);
                st.fallback = true;
            }
            if (!byTree) out = sortByRows(out, m);
            return out;
        }

        // ---- основной текст страницы (порт AnTTS)

        private static List<Block> collectMainText(AccessibilityNodeInfo root, ScreenMetrics m, Stats st) {
            List<AccessibilityNodeInfo> candidates = new ArrayList<AccessibilityNodeInfo>();
            collect(root, candidates, m, 0, new int[]{0}, st);

            Map<String, Block> unique = new LinkedHashMap<String, Block>();
            for (AccessibilityNodeInfo node : candidates) {
                String text = str(node.getText());
                if (text.length() < 2 || !hasLetter(text)) continue;
                Block b = new Block(text, node);
                String key = text + "|" + (b.rect.top / 12) + "|" + (b.rect.left / 12);
                if (!unique.containsKey(key)) unique.put(key, b);
            }
            return new ArrayList<Block>(unique.values());          // порядок обхода дерева сохраняется
        }

        private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out,
                                    ScreenMetrics m, int depth, int[] counter, Stats st) {
            if (node == null || depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return;
            st.visited++;
            if (!node.isVisibleToUser()) { st.invisible++; return; }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.bottom <= m.topBoundary || rect.top >= m.bottomBoundary
                    || rect.right <= 0 || rect.left >= m.screenWidth
                    || rect.width() <= 0 || rect.height() <= 0) {
                st.offscreen++;
                return;
            }
            if (isAuxiliaryPanel(node, rect, m)) { st.panel++; return; }

            String value = str(node.getText());
            if (!value.isEmpty()) {
                st.withText++;
                if (isAuxiliaryControl(node, rect, m)) st.control++;
                else if (hasTextInChildren(node, 0)) st.parentOfText++;
                else { out.add(node); st.collected++; }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                collect(node.getChild(i), out, m, depth + 1, counter, st);
            }
        }

        // ---- весь видимый текст, правила как в TalkBack (описание важнее текста, затем подсказка)

        private static List<Block> collectAllText(AccessibilityNodeInfo root, ScreenMetrics m, Stats st) {
            List<Block> raw = new ArrayList<Block>();
            walkAll(root, raw, m, 0, new int[]{0}, st);
            List<Block> res = new ArrayList<Block>();
            String last = null;
            for (Block b : raw) {                                  // подряд идущие повторы не нужны
                if (!b.text.equals(last)) { res.add(b); last = b.text; }
            }
            return res;
        }

        private static void walkAll(AccessibilityNodeInfo node, List<Block> out, ScreenMetrics m,
                                    int depth, int[] counter, Stats st) {
            if (node == null || depth > MAX_DEPTH || ++counter[0] > MAX_NODES) return;
            st.visited++;
            if (!node.isVisibleToUser() || node.isPassword()) { st.invisible++; return; }

            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (rect.bottom <= m.topBoundary || rect.top >= m.bottomBoundary
                    || rect.right <= 0 || rect.left >= m.screenWidth) {
                st.offscreen++;
                return;
            }
            CharSequence cd = nonBlank(node.getContentDescription());
            CharSequence spokenText = cd != null ? cd : nonBlank(node.getText());
            if (spokenText == null && Build.VERSION.SDK_INT >= 26) spokenText = nonBlank(node.getHintText());
            boolean collection = node.getCollectionInfo() != null;
            if (collection) spokenText = null;                    // заголовки списков не читаем, читаем элементы

            boolean descend = true;
            if (spokenText != null && rect.width() >= MIN_VISIBLE_PIXELS && rect.height() >= MIN_VISIBLE_PIXELS) {
                String s = spokenText.toString().trim();
                if (hasLetterOrDigit(s)) {
                    st.withText++;
                    out.add(new Block(s, node));
                    st.collected++;
                    if (cd != null && !collection && !node.isScrollable()) descend = false;   // описание заменяет потомков
                }
            }
            if (!descend) return;
            for (int i = 0; i < node.getChildCount(); i++) {
                walkAll(node.getChild(i), out, m, depth + 1, counter, st);
            }
        }

        // ---- порядок «по положению на экране»: сверху вниз, на одной строке слева направо

        private static List<Block> sortByRows(List<Block> in, ScreenMetrics m) {
            List<Block> byTop = new ArrayList<Block>(in);
            Collections.sort(byTop, new Comparator<Block>() {
                @Override public int compare(Block a, Block b) { return a.rect.top < b.rect.top ? -1 : (a.rect.top == b.rect.top ? 0 : 1); }
            });
            final int tolerance = (int) (m.density * 8);
            List<Block> result = new ArrayList<Block>();
            int i = 0;
            while (i < byTop.size()) {
                int rowTop = byTop.get(i).rect.top;
                List<Block> row = new ArrayList<Block>();
                while (i < byTop.size() && Math.abs(byTop.get(i).rect.top - rowTop) <= tolerance) {
                    row.add(byTop.get(i));
                    i++;
                }
                Collections.sort(row, new Comparator<Block>() {
                    @Override public int compare(Block a, Block b) { return a.rect.left < b.rect.left ? -1 : (a.rect.left == b.rect.left ? 0 : 1); }
                });
                result.addAll(row);
            }
            return result;
        }

        // ---- вспомогательное

        private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }

        private static CharSequence nonBlank(CharSequence cs) {
            return (cs != null && cs.toString().trim().length() > 0) ? cs : null;
        }

        private static boolean hasLetter(String s) {
            for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
            return false;
        }

        private static boolean hasLetterOrDigit(String s) {
            for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return true;
            return false;
        }

        private static int dp(ScreenMetrics m, int v) { return (int) (v * m.density); }

        private static String classNameOf(AccessibilityNodeInfo n) {
            CharSequence cn = n.getClassName();
            return cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
        }

        private static String viewIdOf(AccessibilityNodeInfo n) {
            String id = n.getViewIdResourceName();
            return id == null ? "" : id.toLowerCase(Locale.ROOT);
        }

        private static boolean any(String s, String[] markers) {
            for (String mk : markers) if (s.contains(mk)) return true;
            return false;
        }

        private static boolean any(String a, String b, String[] markers) {
            return any(a, markers) || any(b, markers);
        }

        private static int textLen(AccessibilityNodeInfo n) { return str(n.getText()).length(); }

        private static boolean isEditableNode(AccessibilityNodeInfo n) {
            if (n.isEditable()) return true;
            String c = classNameOf(n);
            return c.contains("edittext") || c.contains("autocompletetextview");
        }

        private static boolean hasTextInChildren(AccessibilityNodeInfo node, int depth) {
            if (depth > MAX_DEPTH) return false;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                if (!str(child.getText()).isEmpty()) return true;
                if (hasTextInChildren(child, depth + 1)) return true;
            }
            return false;
        }

        private static boolean isAuxiliaryPanel(AccessibilityNodeInfo node, Rect rect, ScreenMetrics m) {
            String cls = classNameOf(node);
            String id = viewIdOf(node);
            int tlen = textLen(node);

            // верхние панели
            if (any(cls, id, TOP_PANEL)) return true;
            if (rect.top <= m.topBoundary + dp(m, 12)
                    && rect.bottom <= m.topBoundary + dp(m, 80)
                    && rect.width() >= m.screenWidth * 0.75
                    && !node.isScrollable() && tlen < 50) {
                if (cls.contains("layout") || cls.contains("group") || cls.contains("view")) {
                    if (id.contains("head") || id.contains("bar") || id.contains("nav")) return true;
                }
            }

            // нижние панели
            if (any(cls, id, BOTTOM_PANEL)) return true;
            if (rect.bottom >= m.bottomBoundary - dp(m, 12)
                    && rect.top >= m.bottomBoundary - dp(m, 80)
                    && rect.width() >= m.screenWidth * 0.75
                    && !node.isScrollable() && tlen < 50) {
                if (id.contains("bottom") || id.contains("foot") || id.contains("nav") || id.contains("bar")) return true;
            }

            // боковые панели
            if (any(cls, id, SIDE_PANEL)) return true;
            int railMaxW = dp(m, 96);
            int minRailH = (int) (m.screenHeight * 0.35);
            boolean railLike = id.contains("rail") || id.contains("side") || id.contains("nav") || cls.contains("navigation");
            if (rect.left <= 0 && rect.right <= railMaxW && rect.height() >= minRailH && tlen < 50 && railLike) return true;
            if (rect.right >= m.screenWidth && rect.left >= m.screenWidth - railMaxW
                    && rect.height() >= minRailH && tlen < 50 && railLike) return true;

            // реклама и плавающая кнопка
            if (any(cls, id, AD)) return true;
            return cls.contains("floatingactionbutton") || id.contains("fab");
        }

        private static boolean isAuxiliaryControl(AccessibilityNodeInfo node, Rect rect, ScreenMetrics m) {
            if (isEditableNode(node)) return true;

            String text = str(node.getText());
            String cls = classNameOf(node);
            String id = viewIdOf(node);

            if (any(cls, CONTROL_CLASS)) return true;
            if (any(id, TOP_PANEL) || any(id, BOTTOM_PANEL) || any(id, SIDE_PANEL)) return true;
            if (any(cls, id, AD)) return true;
            if ((node.isClickable() || node.isCheckable()) && text.length() < 45) return true;

            AccessibilityNodeInfo parent = node.getParent();
            int depth = 0;
            while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
                String pc = classNameOf(parent);
                String pid = viewIdOf(parent);
                if (any(pc, pid, TOP_PANEL) || any(pc, pid, BOTTOM_PANEL) || any(pc, pid, SIDE_PANEL)) return true;
                if (any(pc, CONTROL_CLASS)) return true;
                if (any(pc, pid, AD)) return true;
                if (parent.isClickable() && text.length() < 45) {
                    Rect pr = new Rect();
                    parent.getBoundsInScreen(pr);
                    if (pr.height() <= dp(m, 64) || pr.width() <= dp(m, 220)) return true;
                }
                parent = parent.getParent();
                depth++;
            }
            return false;
        }
    }
}
