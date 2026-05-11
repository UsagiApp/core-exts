package keiyoushi.utils;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InMemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> data = new HashMap<>();

    @Override
    public Map<String, ?> getAll() { return new HashMap<>(data); }

    @Override
    public String getString(String key, String defValue) {
        Object val = data.get(key);
        return val instanceof String ? (String) val : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object val = data.get(key);
        return val instanceof Set ? (Set<String>) val : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object val = data.get(key);
        return val instanceof Integer ? (Integer) val : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object val = data.get(key);
        return val instanceof Long ? (Long) val : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object val = data.get(key);
        return val instanceof Float ? (Float) val : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object val = data.get(key);
        return val instanceof Boolean ? (Boolean) val : defValue;
    }

    @Override
    public boolean contains(String key) { return data.containsKey(key); }

    @Override
    public Editor edit() { return new InMemoryEditor(data); }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    private static class InMemoryEditor implements Editor {
        private final Map<String, Object> data;
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean cleared = false;

        InMemoryEditor(Map<String, Object> data) { this.data = data; }

        @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { pending.put(key, values); return this; }
        @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
        @Override public Editor remove(String key) { removals.add(key); return this; }
        @Override public Editor clear() { cleared = true; return this; }

        @Override
        public boolean commit() { applyChanges(); return true; }

        @Override
        public void apply() { applyChanges(); }

        private void applyChanges() {
            if (cleared) data.clear();
            for (String k : removals) data.remove(k);
            data.putAll(pending);
        }
    }
}
