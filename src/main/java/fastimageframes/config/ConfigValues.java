package fastimageframes.config;

import eu.midnightdust.lib.config.MidnightConfig;
import fastimageframes.FastImageFrames;
import fastimageframes.FifConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigValues {

    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    private final Map<String, Object> values = new LinkedHashMap<>();

    private ConfigValues() {
    }

    public static void captureDefaults() {
        for (Field field : entries()) {
            DEFAULTS.put(field.getName(), read(field));
        }
    }

    public static ConfigValues load() {
        ConfigValues config = new ConfigValues();
        for (Field field : entries()) {
            config.values.put(field.getName(), read(field));
        }
        return config;
    }

    public boolean bool(String name) {
        return (Boolean) values.get(name);
    }

    public int integer(String name) {
        return (Integer) values.get(name);
    }

    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> E enumValue(String name) {
        return (E) values.get(name);
    }

    public void set(String name, Object value) {
        if (!values.containsKey(name)) {
            throw new IllegalArgumentException(name);
        }
        values.put(name, value);
    }

    public void reset(String... names) {
        for (String name : names) {
            if (DEFAULTS.containsKey(name)) {
                values.put(name, DEFAULTS.get(name));
            }
        }
    }

    public boolean changed() {
        for (Field field : entries()) {
            if (!values.get(field.getName()).equals(read(field))) {
                return true;
            }
        }
        return false;
    }

    public void apply() {
        for (Field field : entries()) {
            try {
                field.set(null, values.get(field.getName()));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        MidnightConfig.write(FastImageFrames.MOD_ID);
        FifConfig.onChanged();
    }

    private static Field[] entries() {
        return Arrays.stream(FifConfig.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.isAnnotationPresent(MidnightConfig.Entry.class))
                .toArray(Field[]::new);
    }

    private static Object read(Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

}
