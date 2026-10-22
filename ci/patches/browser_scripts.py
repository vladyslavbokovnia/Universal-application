#!/usr/bin/env python3
"""Второй патч для modules/Browser.java (применяется после browser_hub.py).
Пункт меню «Команды скриптов: N» заменяется экраном «Скрипты и их настройки»:
у каждого скрипта переключатель включения, его команды из GM_registerMenuCommand и его сохранённые настройки
(булевы значения GM_setValue, в том числе булевы поля внутри объекта) с переключателями.
Использование: python3 browser_scripts.py modules/Browser.java"""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "modules/Browser.java"
src = open(path, encoding="utf-8").read()


def rep(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        sys.exit("ANCHOR FAILED (%d found, %d expected): %s" % (n, count, old[:90].replace("\n", "\\n")))
    src = src.replace(old, new)


rep('''            if (!t.menu.isEmpty()) {
                addG(items, "scripts", "📜", "Команды скриптов: " + t.menu.size(), new Runnable() { @Override public void run() { showScriptCommands(t); } });
            }
''',
    '''            addG(items, "scripts", "📜", "Скрипты и их настройки", new Runnable() { @Override public void run() { showScripts(t); } });
''')

SCRIPTS_CODE = r'''        private boolean scriptsDirty;

        private View switchRow(String text, boolean on, boolean big, int indentDp, android.widget.CompoundButton.OnCheckedChangeListener l) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4 + indentDp), dp(6), dp(4), dp(6));
            TextView tv = label(text, big ? 17 : 15, C_TEXT, big);
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            final android.widget.Switch sw = new android.widget.Switch(act);
            sw.setChecked(on);
            sw.setContentDescription(text);
            sw.setOnCheckedChangeListener(l);
            row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sw.toggle(); }
            });
            return row;
        }

        private void pushScriptValue(Tab t, UserScript s, String key, String oldJ, String newJ) {
            values().edit().putString(s.id() + "\t" + key, newJ).apply();
            for (Injected inj : new ArrayList<Injected>(t.injected.values())) {
                if (inj.script.fileName.equals(s.fileName)) broadcastValue(t, inj, key, oldJ, newJ);
            }
            scriptsDirty = true;
        }

        /** Все скрипты с переключателями; под каждым его команды и сохранённые настройки (булевы значения). Изменения применяются обновлением страницы при закрытии. */
        private void showScripts(final Tab t) {
            scriptsDirty = false;
            ScrollView sv = new ScrollView(act);
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), dp(14), dp(14), dp(28));
            sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
            col.addView(panelTitle("Скрипты и их настройки"));
            List<UserScript> scripts = ScriptStore.list();
            if (scripts.isEmpty()) {
                col.addView(label("Скриптов нет. Папка: " + ScriptStore.dir().getPath(), 15, C_TEXT2, false));
            }
            java.util.Map<String, ?> all = values().getAll();
            for (final UserScript s : scripts) {
                col.addView(switchRow(s.name + (s.version.length() > 0 ? " " + s.version : ""),
                        sp.getBoolean("script_on:" + s.fileName, true), true, 0,
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                                sp.edit().putBoolean("script_on:" + s.fileName, on).apply();
                                scriptsDirty = true;
                            }
                        }));
                for (final MenuCmd m : new ArrayList<MenuCmd>(t.menu)) {
                    if (!m.inj.script.fileName.equals(s.fileName)) continue;
                    TextView cmd = label("▶  " + m.name, 15, C_ACCENT, false);
                    cmd.setPadding(dp(22), dp(8), dp(4), dp(8));
                    cmd.setClickable(true);
                    cmd.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (t.wv != null) t.wv.evaluateJavascript("try{window['" + m.inj.disp + "'].m(" + m.id + ")}catch(e){}", null);
                        }
                    });
                    col.addView(cmd);
                }
                String prefix = s.id() + "\t";
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof String)) continue;
                    final String key = e.getKey().substring(prefix.length());
                    final String json = (String) e.getValue();
                    if ("true".equals(json) || "false".equals(json)) {
                        col.addView(switchRow(key, "true".equals(json), false, 18,
                                new android.widget.CompoundButton.OnCheckedChangeListener() {
                                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                                        pushScriptValue(t, s, key, null, on ? "true" : "false");
                                    }
                                }));
                    } else if (json.startsWith("\u007b")) {
                        try {
                            final JSONObject o = new JSONObject(json);
                            java.util.Iterator<String> it = o.keys();
                            while (it.hasNext()) {
                                final String f = it.next();
                                if (!(o.get(f) instanceof Boolean)) continue;
                                col.addView(switchRow(f, o.getBoolean(f), false, 18,
                                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                                            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                                                try {
                                                    String before = o.toString();
                                                    o.put(f, on);
                                                    pushScriptValue(t, s, key, before, o.toString());
                                                } catch (JSONException ignored) { }
                                            }
                                        }));
                            }
                        } catch (JSONException ignored) { }
                    }
                }
            }
            showLayer(sv, true, new Runnable() {
                @Override public void run() {
                    if (scriptsDirty && t.wv != null) {
                        scriptsDirty = false;
                        t.wv.reload();
                    }
                }
            }, "Скрипты и их настройки");
        }

'''
rep("        private void showScriptCommands(final Tab t) {", SCRIPTS_CODE + "        private void showScriptCommands(final Tab t) {")

if src.count("{") != src.count("}"):
    sys.exit("BRACE BALANCE FAILED: %d open, %d close" % (src.count("{"), src.count("}")))

open(path, "w", encoding="utf-8").write(src)
print("patched OK:", path)
