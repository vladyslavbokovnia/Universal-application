package im.manus.universalhost;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Настройки движка перевода (бывший модуль «VOT перевод») внутри настроек модуля «YouTube аудио»: все его ключи лежат
 * там с префиксом vot_. Единственный общий ключ без префикса — wifi_only («Только по Wi-Fi» для всего модуля).
 * При первом обращении значения (вход в Яндекс, голос и т. д.) копируются из старого файла настроек VOT.
 */
final class VotPrefs implements SharedPreferences {

    static final String PREFIX = "vot_";
    private static final String OLD = "VOT перевод";

    private final SharedPreferences base;

    private VotPrefs(SharedPreferences base) { this.base = base; }

    static SharedPreferences of(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences base = app.getSharedPreferences(YtAudioQueue.PREFS, Context.MODE_PRIVATE);
        synchronized (VotPrefs.class) {
            if (!base.getBoolean(PREFIX + "migrated", false)) migrate(app, base);
        }
        return new VotPrefs(base);
    }

    /** Один раз переносит значения старого модуля (кроме списка, папки и Wi-Fi: они теперь общие). */
    private static void migrate(Context app, SharedPreferences base) {
        try {
            SharedPreferences old = app.getSharedPreferences(ModuleStorage.INSTANCE.prefsName(OLD), Context.MODE_PRIVATE);
            Editor e = base.edit();
            for (Map.Entry<String, ?> en : old.getAll().entrySet()) {
                String k = en.getKey();
                Object v = en.getValue();
                if ("queue".equals(k) || "out_dir".equals(k) || "wifi_only".equals(k) || v == null) continue;
                String nk = PREFIX + k;
                if (v instanceof String) e.putString(nk, (String) v);
                else if (v instanceof Boolean) e.putBoolean(nk, (Boolean) v);
                else if (v instanceof Integer) e.putInt(nk, (Integer) v);
                else if (v instanceof Long) e.putLong(nk, (Long) v);
                else if (v instanceof Float) e.putFloat(nk, (Float) v);
                else if (v instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> set = (Set<String>) v;
                    e.putStringSet(nk, set);
                }
            }
            e.putBoolean(PREFIX + "migrated", true);
            e.apply();
        } catch (Throwable t) {
            base.edit().putBoolean(PREFIX + "migrated", true).apply();
        }
    }

    private static boolean shared(String key) { return "wifi_only".equals(key); }

    private static String k(String key) { return shared(key) ? key : PREFIX + key; }

    @Override public Map<String, ?> getAll() {
        Map<String, Object> r = new HashMap<String, Object>();
        for (Map.Entry<String, ?> en : base.getAll().entrySet()) {
            if (en.getKey().startsWith(PREFIX)) r.put(en.getKey().substring(PREFIX.length()), en.getValue());
        }
        return r;
    }

    @Override public String getString(String key, String def) { return base.getString(k(key), def); }

    @Override public Set<String> getStringSet(String key, Set<String> def) { return base.getStringSet(k(key), def); }

    @Override public int getInt(String key, int def) { return base.getInt(k(key), def); }

    @Override public long getLong(String key, long def) { return base.getLong(k(key), def); }

    @Override public float getFloat(String key, float def) { return base.getFloat(k(key), def); }

    /** «Только по Wi-Fi» общий для модуля: по умолчанию выключено, как у загрузки. */
    @Override public boolean getBoolean(String key, boolean def) {
        return shared(key) ? base.getBoolean(key, false) : base.getBoolean(k(key), def);
    }

    @Override public boolean contains(String key) { return base.contains(k(key)); }

    @Override public Editor edit() { return new Ed(base.edit()); }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        base.registerOnSharedPreferenceChangeListener(l);
    }

    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        base.unregisterOnSharedPreferenceChangeListener(l);
    }

    private final class Ed implements Editor {
        private final Editor e;

        Ed(Editor e) { this.e = e; }

        @Override public Editor putString(String key, String v) { e.putString(k(key), v); return this; }

        @Override public Editor putStringSet(String key, Set<String> v) { e.putStringSet(k(key), v); return this; }

        @Override public Editor putInt(String key, int v) { e.putInt(k(key), v); return this; }

        @Override public Editor putLong(String key, long v) { e.putLong(k(key), v); return this; }

        @Override public Editor putFloat(String key, float v) { e.putFloat(k(key), v); return this; }

        @Override public Editor putBoolean(String key, boolean v) { e.putBoolean(k(key), v); return this; }

        @Override public Editor remove(String key) { e.remove(k(key)); return this; }

        /** Чистит только ключи движка, а не весь модуль. */
        @Override public Editor clear() {
            for (String key : base.getAll().keySet()) if (key.startsWith(PREFIX)) e.remove(key);
            return this;
        }

        @Override public boolean commit() { return e.commit(); }

        @Override public void apply() { e.apply(); }
    }
}
