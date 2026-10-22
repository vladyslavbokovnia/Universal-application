#!/usr/bin/env python3
"""Патч modules/Browser.java: кнопка в центре экрана вместо нижней панели, кольцо значков по долгому нажатию,
стартовая страница (поиск + закладки со сменой значков), панель вкладок без кнопок сверху.
Использование: python3 browser_hub.py modules/Browser.java
Каждая замена обязана найти своё место ровно один раз, иначе патч останавливается и файл не меняется."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "modules/Browser.java"
src = open(path, encoding="utf-8").read()


def rep(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        sys.exit("ANCHOR FAILED (%d found, %d expected): %s" % (n, count, old[:90].replace("\n", "\\n")))
    src = src.replace(old, new)


def rep_between(start, end, new, keep_end=True):
    global src
    a = src.find(start)
    if a < 0 or src.count(start) != 1:
        sys.exit("START ANCHOR FAILED: " + start[:90])
    b = src.find(end, a)
    if b < 0:
        sys.exit("END ANCHOR FAILED: " + end[:90])
    src = src[:a] + new + (src[b:] if keep_end else src[b + len(end):])


# ---------------------------------------------------------------- константы
rep("REQ_FILE = 7311;",
    "REQ_FILE = 7311;\n"
    "    private static final int REQ_BM_IMG = 7313;\n"
    "    private static final float HUB_ALPHA = 0.45f;")

# ---------------------------------------------------------------- поля
rep("        private Group openGroup;\n",
    "        private Group openGroup;\n"
    "        private TextView hub;\n"
    "        private boolean startOpen, startForNew;\n"
    "        private long pendingBm = -1;\n"
    "        private final List<View> ringViews = new ArrayList<View>();\n"
    "        private final List<Runnable> ringActions = new ArrayList<Runnable>();\n"
    "        private int ringHot = -1;\n"
    "        private final Runnable hubReturn = new Runnable() {\n"
    "            @Override public void run() {\n"
    "                scrollAccum = 0;\n"
    "                setDockHidden(false, true);\n"
    "            }\n"
    "        };\n")

# ---------------------------------------------------------------- конструктор: нижней панели нет, индикатор загрузки сверху, кнопка в центре
rep("            dock.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));\n"
    "            dock.addView(tool, new LinearLayout.LayoutParams(-1, dp(52)));\n"
    "            column.addView(content, new FrameLayout.LayoutParams(-1, -1));\n"
    "            column.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));\n"
    "            addView(column, new FrameLayout.LayoutParams(-1, -1));\n",
    "            dock.addView(tool, new LinearLayout.LayoutParams(-1, dp(52)));\n"
    "            dock.setVisibility(View.GONE);          // нижней панели больше нет; её кнопки живут скрытыми, их состояние обновляет refreshUi\n"
    "            column.addView(content, new FrameLayout.LayoutParams(-1, -1));\n"
    "            column.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));\n"
    "            column.addView(bar, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));\n"
    "            addView(column, new FrameLayout.LayoutParams(-1, -1));\n"
    "            buildHub();\n")

# ---------------------------------------------------------------- страница занимает весь экран
rep("lp.bottomMargin = fullscreen ? 0 : dp(55);", "lp.bottomMargin = 0;")

# ---------------------------------------------------------------- прятать/показывать кнопку вместо панели
rep_between(
    "        private void setDockHidden(boolean hide, boolean animate) {",
    "        /** Движение пальца по странице",
    """        private void setDockHidden(boolean hide, boolean animate) {
            if (!sp.getBoolean("autohide_dock", true)) hide = false;
            if (hide && layer != null) hide = false;
            boolean changed = hide != dockHidden;
            dockHidden = hide;
            if (changed) dockToggleAt = SystemClock.uptimeMillis();
            if (hub == null) return;
            h.removeCallbacks(hubReturn);
            hub.animate().cancel();
            if (hide) {
                if (animate) {
                    hub.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
                        @Override public void run() { if (dockHidden) hub.setVisibility(View.INVISIBLE); }
                    }).start();
                } else {
                    hub.setAlpha(0f);
                    hub.setVisibility(View.INVISIBLE);
                }
            } else {
                hub.setVisibility(View.VISIBLE);
                if (animate) hub.animate().alpha(HUB_ALPHA).setDuration(150).start();
                else hub.setAlpha(HUB_ALPHA);
            }
        }

""")

# ---------------------------------------------------------------- кнопка возвращается через секунду после остановки прокрутки
rep("            else if (scrollAccum < -dp(24) && dockHidden) setDockHidden(false, true);\n        }\n",
    "            else if (scrollAccum < -dp(24) && dockHidden) setDockHidden(false, true);\n"
    "            if (dockHidden) {\n"
    "                h.removeCallbacks(hubReturn);\n"
    "                h.postDelayed(hubReturn, 1000);\n"
    "            }\n"
    "        }\n")

# ---------------------------------------------------------------- число вкладок на кнопке
rep("""                    + ", профиль " + profileName(t.profile));
        }

        private void setEnabledLook""",
    """                    + ", профиль " + profileName(t.profile));
            updateHub(t);
        }

        private void setEnabledLook""")

# ---------------------------------------------------------------- настройки
rep('"Прятать нижнюю панель при прокрутке",\n'
    '                "Прячется, когда страницу листают вниз, и возвращается при прокрутке вверх или в начале страницы"',
    '"Прятать кнопку при прокрутке",\n'
    '                "Кнопка прячется, пока страницу листают, и возвращается через секунду после остановки, при прокрутке в обратную сторону или в начале страницы"')
rep('"Системные панели скрыты, нижняя панель лежит поверх страницы"', '"Системные панели скрыты"')

# ---------------------------------------------------------------- закрытие слоя сбрасывает признак стартовой страницы
rep("            layer = null;\n            tabsPanel = false;\n",
    "            layer = null;\n            tabsPanel = false;\n            startOpen = false;\n")

# ---------------------------------------------------------------- результат выбора картинки для закладки
rep("            if (requestCode != REQ_FILE) return;\n",
    """            if (requestCode == REQ_BM_IMG) {
                final long bmId = pendingBm;
                pendingBm = -1;
                if (bmId >= 0 && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) saveBookmarkImage(bmId, data.getData());
                return;
            }
            if (requestCode != REQ_FILE) return;
""")

# ---------------------------------------------------------------- новые методы: кнопка, кольцо, стартовая страница
HUB_CODE = r'''        // ------------------------------------------------------------ кнопка в центре экрана: нажатие, долгое нажатие, перетаскивание

        private void buildHub() {
            hub = new TextView(act);
            hub.setTextColor(0xFFFFFFFF);
            hub.setTextSize(15);
            hub.setTypeface(hub.getTypeface(), android.graphics.Typeface.BOLD);
            hub.setGravity(Gravity.CENTER);
            hub.setText("1");
            hub.setBackground(round(0x55000000, 28, 0x55FFFFFF, 1));
            hub.setAlpha(HUB_ALPHA);
            hub.setClickable(true);
            hub.setFocusable(true);
            hub.setContentDescription("Вкладки");
            hub.setTranslationX(sp.getFloat("hub_dx", 0f));
            hub.setTranslationY(sp.getFloat("hub_dy", 0f));
            addView(hub, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
            // для экранного диктора: двойное нажатие и долгое двойное нажатие; обычные касания разбирает слушатель ниже
            hub.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openTabsRoot(); }
            });
            hub.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showRing();
                    return true;
                }
            });
            final float slop = ViewConfiguration.get(act).getScaledTouchSlop();
            hub.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY, startTx, startTy;
                private boolean moved, ringShown, longFired;
                private final Runnable longRun = new Runnable() {
                    @Override public void run() {
                        longFired = true;
                        hub.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        showRing();
                        ringShown = layer != null;
                    }
                };

                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            startTx = v.getTranslationX();
                            startTy = v.getTranslationY();
                            moved = false;
                            longFired = false;
                            ringShown = false;
                            h.removeCallbacks(hubReturn);
                            v.animate().cancel();
                            v.setAlpha(1f);
                            v.postDelayed(longRun, ViewConfiguration.getLongPressTimeout());
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            if (ringShown) {
                                ringHover(e.getRawX(), e.getRawY());
                                return true;
                            }
                            float dx = e.getRawX() - downX;
                            float dy = e.getRawY() - downY;
                            if (!moved && !longFired && (Math.abs(dx) > slop * 2 || Math.abs(dy) > slop * 2)) {
                                moved = true;
                                v.removeCallbacks(longRun);
                            }
                            if (moved) moveHub(startTx + dx, startTy + dy);
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            v.removeCallbacks(longRun);
                            v.setAlpha(HUB_ALPHA);
                            if (ringShown) ringRelease(e.getRawX(), e.getRawY());
                            else if (moved) sp.edit().putFloat("hub_dx", v.getTranslationX()).putFloat("hub_dy", v.getTranslationY()).apply();
                            else if (!longFired) openTabsRoot();
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.removeCallbacks(longRun);
                            v.setAlpha(HUB_ALPHA);
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        private void moveHub(float tx, float ty) {
            float maxX = Math.max(0f, getWidth() / 2f - dp(28));
            float maxY = Math.max(0f, getHeight() / 2f - dp(28));
            hub.setTranslationX(Math.max(-maxX, Math.min(maxX, tx)));
            hub.setTranslationY(Math.max(-maxY, Math.min(maxY, ty)));
        }

        private void updateHub(Tab t) {
            if (hub == null) return;
            hub.setText(String.valueOf(tabs.size()));
            hub.setContentDescription("Вкладки: открыто " + tabs.size() + ", сейчас " + (tabs.indexOf(t) + 1)
                    + ". Долгое нажатие: кнопки управления");
        }

        private final class RingItem {
            final String glyph, desc;
            final boolean on;
            final Runnable run;

            RingItem(String glyph, String desc, boolean on, Runnable run) {
                this.glyph = glyph;
                this.desc = desc;
                this.on = on;
                this.run = run;
            }
        }

        /** Полное кольцо значков без текста вокруг кнопки. Можно, не отпуская палец, довести его до значка и отпустить, или нажать значок отдельно. */
        private void showRing() {
            final Tab t = cur;
            if (t == null || hub == null) return;
            closeLayer(true);
            ringViews.clear();
            ringActions.clear();
            ringHot = -1;
            List<RingItem> items = new ArrayList<RingItem>();
            boolean back = (t.wv != null && t.wv.canGoBack()) || (t.opener != null && tabs.contains(t.opener));
            items.add(new RingItem("‹", "Назад", back, new Runnable() { @Override public void run() { goBackOrClose(); } }));
            items.add(new RingItem("›", "Вперёд", t.wv != null && t.wv.canGoForward(), new Runnable() {
                @Override public void run() { if (t.wv != null && t.wv.canGoForward()) t.wv.goForward(); }
            }));
            items.add(new RingItem(t.loading ? "✕" : "↻", t.loading ? "Остановить загрузку" : "Обновить страницу", true, new Runnable() {
                @Override public void run() {
                    if (t.wv == null) return;
                    if (t.loading) t.wv.stopLoading(); else t.wv.reload();
                }
            }));
            items.add(new RingItem("＋", "Новая вкладка", true, new Runnable() { @Override public void run() { newTabWithStart(); } }));
            items.add(new RingItem("⌕", "Поиск, адрес и закладки", true, new Runnable() { @Override public void run() { showStart(false, true); } }));
            items.add(new RingItem("⋮", "Меню", true, new Runnable() { @Override public void run() { showMenu(); } }));

            int[] hp = new int[2];
            int[] mp = new int[2];
            hub.getLocationOnScreen(hp);
            getLocationOnScreen(mp);
            float r = dp(96);
            float half = dp(26);
            float cx = hp[0] - mp[0] + hub.getWidth() / 2f;
            float cy = hp[1] - mp[1] + hub.getHeight() / 2f;
            float pad = r + half + dp(6);
            cx = Math.max(pad, Math.min(getWidth() - pad, cx));
            cy = Math.max(pad, Math.min(getHeight() - pad, cy));

            FrameLayout l = new FrameLayout(act);
            l.setClickable(true);
            l.setClipChildren(false);
            l.setBackgroundColor(0x66000000);
            l.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeLayer(true); }
            });
            for (int i = 0; i < items.size(); i++) {
                final RingItem it = items.get(i);
                double ang = -Math.PI / 2 + 2 * Math.PI * i / items.size();
                TextView v = glyph(it.glyph, C_TEXT);
                v.setBackground(round(0xEE202124, 26, C_TEXT2, 1));
                v.setContentDescription(it.desc);
                v.setAlpha(it.on ? 1f : 0.4f);
                v.setClickable(true);
                v.setFocusable(true);
                v.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View x) {
                        if (!it.on) return;
                        closeLayer(false);
                        it.run.run();
                    }
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.START);
                lp.leftMargin = Math.round(cx + (float) (r * Math.cos(ang)) - half);
                lp.topMargin = Math.round(cy + (float) (r * Math.sin(ang)) - half);
                l.addView(v, lp);
                ringViews.add(v);
                ringActions.add(it.on ? it.run : null);
            }
            layer = l;
            layerCancel = null;
            addView(l, new FrameLayout.LayoutParams(-1, -1));
            column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            l.announceForAccessibility("Кнопки управления браузером");
        }

        private int ringIndexAt(float rx, float ry) {
            int best = -1;
            float bestD = dp(38);
            for (int i = 0; i < ringViews.size(); i++) {
                View v = ringViews.get(i);
                int[] p = new int[2];
                v.getLocationOnScreen(p);
                float d = (float) Math.hypot(rx - (p[0] + v.getWidth() / 2f), ry - (p[1] + v.getHeight() / 2f));
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        private void ringHover(float rx, float ry) {
            int i = ringIndexAt(rx, ry);
            if (i == ringHot) return;
            if (ringHot >= 0 && ringHot < ringViews.size()) {
                ringViews.get(ringHot).setScaleX(1f);
                ringViews.get(ringHot).setScaleY(1f);
            }
            ringHot = i;
            if (i >= 0) {
                ringViews.get(i).setScaleX(1.25f);
                ringViews.get(i).setScaleY(1.25f);
                try { ringViews.get(i).performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
            }
        }

        private void ringRelease(float rx, float ry) {
            int i = ringIndexAt(rx, ry);
            if (i < 0 || i >= ringActions.size()) return;       // отпустили не на значке: кольцо остаётся, значок можно нажать
            Runnable a = ringActions.get(i);
            if (a == null) return;
            closeLayer(false);
            a.run();
        }

        // ------------------------------------------------------------ стартовая страница: строка поиска и значки закладок

        private void newTabWithStart() {
            if (cur == null) return;
            newTab(home(), cur.profile, null, true);
            showStart(true, true);
        }

        /** Обновить стартовую страницу после смены закладки. */
        private void afterBookmarkChange() {
            if (cur != null) showStart(startForNew, false);
        }

        private void showStart(final boolean forNew, boolean keyboard) {
            final Tab t = cur;
            if (t == null) return;
            loadBookmarks();
            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(16), dp(12), 0);

            final EditText box = new EditText(act);
            box.setSingleLine(true);
            box.setTextColor(C_TEXT);
            box.setHintTextColor(C_TEXT2);
            box.setHint("Поиск или адрес");
            box.setTextSize(17);
            box.setSelectAllOnFocus(true);
            box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            box.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            box.setBackground(round(C_SURFACE2, 24, 0, 0));
            box.setPadding(dp(18), 0, dp(18), 0);
            if (!forNew && t.url != null) box.setText(t.url);
            box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                    boolean go = actionId == EditorInfo.IME_ACTION_GO
                            || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN);
                    if (!go) return false;
                    String text = box.getText().toString();
                    closeLayer(false);
                    if (cur != null) navigate(cur, text);
                    return true;
                }
            });
            root.addView(box, new LinearLayout.LayoutParams(-1, dp(52)));

            final int sel = bookmarkTargetProfile();
            List<View> cells = new ArrayList<View>();

            View pb = profileBadge(sel, 56);
            pb.setClickable(true);
            pb.setFocusable(true);
            pb.setContentDescription("Профиль для закладок: " + profileName(sel) + ". Нажмите, чтобы выбрать другой");
            pb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chooseProfile("Открывать закладки в профиле", new Pick() {
                        @Override public void on(int idx) {
                            bmProfile = idx;
                            showStart(forNew, false);
                        }
                    });
                }
            });
            cells.add(pb);

            for (final Bookmark b : new ArrayList<Bookmark>(bookmarks)) {
                View ic = bookmarkIcon(b, 56);
                ic.setClickable(true);
                ic.setFocusable(true);
                ic.setContentDescription("Закладка: " + b.title + ", " + hostOf(b.url) + ", профиль " + profileName(sel));
                ic.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (forNew && cur != null && bookmarkTargetProfile() == cur.profile) {
                            closeLayer(false);
                            navigate(cur, b.url);         // новая вкладка сразу открывает закладку, пустой вкладки не остаётся
                        } else {
                            openBookmark(b, bookmarkTargetProfile(), true);
                        }
                    }
                });
                ic.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        startForNew = forNew;
                        showBookmarkMenu(b);
                        return true;
                    }
                });
                cells.add(ic);
            }

            TextView plus = glyph("⭐", C_TEXT);
            plus.setBackground(round(C_SURFACE2, 28, 0, 0));
            plus.setClickable(true);
            plus.setFocusable(true);
            plus.setContentDescription("Добавить текущую страницу в закладки");
            plus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addBookmark(cur);
                    showStart(forNew, false);
                }
            });
            cells.add(plus);

            ScrollView sv = new ScrollView(act);
            LinearLayout grid = new LinearLayout(act);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(0, dp(16), 0, dp(24));
            sv.addView(grid, new ViewGroup.LayoutParams(-1, -2));
            final int cols = 4;
            LinearLayout row = null;
            for (int i = 0; i < cells.size(); i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                FrameLayout cell = new FrameLayout(act);
                cell.addView(cells.get(i), new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
                row.addView(cell, new LinearLayout.LayoutParams(0, dp(76), 1f));
            }
            while (row != null && row.getChildCount() < cols) row.addView(new View(act), new LinearLayout.LayoutParams(0, dp(76), 1f));
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

            showLayer(root, true, null, "Поиск и закладки");
            startOpen = true;
            startForNew = forNew;
            if (keyboard) {
                box.requestFocus();
                box.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            ((InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
                        } catch (Throwable ignored) { }
                    }
                }, 200);
            }
        }

        /** Своя картинка для значка закладки: квадрат из середины выбранной картинки. */
        private void saveBookmarkImage(final long id, final Uri uri) {
            loadBookmarks();
            Bookmark found = null;
            for (Bookmark x : bookmarks) if (x.id == id) found = x;
            if (found == null) return;
            final Bookmark b = found;
            try {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            InputStream in = act.getContentResolver().openInputStream(uri);
                            byte[] data;
                            try {
                                data = Net.readAll(in, 12 * 1024 * 1024);
                            } finally {
                                if (in != null) in.close();
                            }
                            BitmapFactory.Options bo = new BitmapFactory.Options();
                            bo.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(data, 0, data.length, bo);
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inSampleSize = 1;
                            while (Math.max(bo.outWidth, bo.outHeight) / (o.inSampleSize * 2) >= 256) o.inSampleSize *= 2;
                            Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                            if (src == null) throw new IOException("не картинка");
                            int side = Math.min(src.getWidth(), src.getHeight());
                            Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                            final Bitmap fin = Bitmap.createScaledBitmap(sq, 128, 128, true);
                            OutputStream os = new FileOutputStream(bookmarkIconFile(b));
                            try { fin.compress(Bitmap.CompressFormat.PNG, 90, os); } finally { os.close(); }
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed) return;
                                    b.icon = fin;
                                    b.iconTried = true;
                                    toast("Значок закладки заменён");
                                    afterBookmarkChange();
                                }
                            });
                        } catch (Throwable ex) {
                            h.post(new Runnable() {
                                @Override public void run() {
                                    if (closed) return;
                                    toast("Не удалось взять эту картинку");
                                    afterBookmarkChange();
                                }
                            });
                        }
                    }
                });
            } catch (Throwable ignored) { }
        }

'''

rep("        // ------------------------------------------------------------ группы, снимки и значки сайтов",
    HUB_CODE + "        // ------------------------------------------------------------ группы, снимки и значки сайтов")

# ---------------------------------------------------------------- меню закладки: своя картинка / вернуть значок сайта; обновление стартовой страницы
a = src.find("        private void showBookmarkMenu(final Bookmark b) {")
b = src.find("        // ------------------------------------------------------------ панель вкладок: снимок")
if a < 0 or b < 0 or b < a:
    sys.exit("BOOKMARK MENU ANCHOR FAILED")
bm = src[a:b]
if bm.count("showTabs();") < 3:
    sys.exit("BOOKMARK MENU: unexpected showTabs count %d" % bm.count("showTabs();"))
bm = bm.replace("showTabs();", "afterBookmarkChange();")
marker = '            add(icons, labels, actions, "✕", "Удалить закладку", new Runnable()'
if bm.count(marker) != 1:
    sys.exit("BOOKMARK MENU: delete item anchor failed")
bm = bm.replace(marker,
    '''            add(icons, labels, actions, "🖼", "Своя картинка…", new Runnable() { @Override public void run() {
                pendingBm = b.id;
                try {
                    act.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                            .addCategory(Intent.CATEGORY_OPENABLE), REQ_BM_IMG);
                } catch (Throwable e) {
                    pendingBm = -1;
                    toast("Нет приложения для выбора картинки");
                }
            } });
            add(icons, labels, actions, "↺", "Вернуть значок сайта", new Runnable() { @Override public void run() {
                try { bookmarkIconFile(b).delete(); } catch (Throwable ignored) { }
                b.icon = null;
                b.iconTried = false;
                afterBookmarkChange();
            } });
''' + marker)
src = src[:a] + bm + src[b:]

# ---------------------------------------------------------------- панель вкладок: без кнопок сверху, без ряда закладок
rep_between(
    "            LinearLayout head = new LinearLayout(act);",
    "            root.addView(head, new LinearLayout.LayoutParams(-1, -2));\n",
    "            int shown = og != null ? tabsOf(og).size() : tabs.size();\n",
    keep_end=False)
rep("            if (og != null) root.addView(outOfGroupZone(og), new LinearLayout.LayoutParams(-1, -2));\n"
    "            else root.addView(bookmarkStrip(), new LinearLayout.LayoutParams(-1, -2));\n",
    "            if (og != null) root.addView(outOfGroupZone(og), new LinearLayout.LayoutParams(-1, -2));\n")
rep("grid.setPadding(dp(6), dp(4), dp(6), dp(16));", "grid.setPadding(dp(6), dp(14), dp(6), dp(16));")

# ---------------------------------------------------------------- число вкладок в группе: одна цифра на маленьком круге
rep('TextView badge = label("📁 " + count, 15, C_TEXT, true);', 'TextView badge = label(String.valueOf(count), 16, C_TEXT, true);')
rep("badge.setBackground(round(0xCC000000, 14, 0, 0));", "badge.setBackground(round(0xCC000000, 16, 0, 0));")
rep("badge.setPadding(dp(10), dp(4), dp(10), dp(4));",
    "badge.setPadding(dp(8), dp(2), dp(8), dp(2));\n"
    "                badge.setMinWidth(dp(32));\n"
    "                badge.setMinHeight(dp(32));")

# ---------------------------------------------------------------- проверка баланса скобок (грубая защита от обрыва вставки)
if src.count("{") != src.count("}"):
    sys.exit("BRACE BALANCE FAILED: %d open, %d close" % (src.count("{"), src.count("}")))

open(path, "w", encoding="utf-8").write(src)
print("patched OK:", path)
