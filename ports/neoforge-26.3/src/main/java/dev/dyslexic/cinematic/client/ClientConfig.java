package dev.dyslexic.cinematic.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dyslexic.cinematic.Cinematic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** The same player options on Fabric; values are read live and saved independently of any settings-screen mod. */
public final class ClientConfig {
    private final Map<String, Value<?>> values;
    private boolean loaded;
    private final Path path = FMLPaths.CONFIGDIR.get().resolve("cinematic-client.json");

    private ClientConfig(Map<String, Value<?>> values) { this.values = values; }
    public boolean isLoaded() { return loaded; }

    public void load() {
        boolean exists = Files.exists(path);
        if (exists) {
            try (var reader = Files.newBufferedReader(path)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (var entry : values.entrySet()) {
                    if (root.has(entry.getKey())) entry.getValue().read(root, entry.getKey());
                }
                if (!root.has("camera.quickMode") && root.has("camera.quickReturn")) {
                    Value<?> mode = values.get("camera.quickMode");
                    if (mode instanceof IntValue value) value.set(root.get("camera.quickReturn").getAsBoolean() ? 1 : 0);
                }
            } catch (Exception e) { Cinematic.LOGGER.warn("Could not read cinematic settings; using defaults", e); }
        }
        loaded = true;
        if (!exists) save();
    }

    public void save() {
        JsonObject root = new JsonObject();
        values.forEach((key, value) -> {
            if (value.get() instanceof Boolean b) root.addProperty(key, b);
            else root.addProperty(key, (Number) value.get());
        });
        try {
            Files.createDirectories(path.getParent());
            try (var writer = Files.newBufferedWriter(path)) { new GsonBuilder().setPrettyPrinting().create().toJson(root, writer); }
        } catch (Exception e) { Cinematic.LOGGER.warn("Could not save cinematic settings", e); }
    }

    public abstract static class Value<T> {
        protected final T fallback;
        protected T value;
        Value(T fallback) { this.fallback = fallback; value = fallback; }
        public T get() { return value; }
        public T getDefault() { return fallback; }
        public void set(T value) { this.value = value; }
        abstract void read(JsonObject json, String key);
    }
    public static final class BooleanValue extends Value<Boolean> {
        BooleanValue(boolean fallback) { super(fallback); }
        @Override void read(JsonObject json, String key) { value = json.get(key).getAsBoolean(); }
    }
    public static final class IntValue extends Value<Integer> {
        private final int min, max;
        IntValue(int fallback, int min, int max) { super(fallback); this.min = min; this.max = max; }
        @Override public void set(Integer value) { super.set(Math.max(min, Math.min(max, value))); }
        @Override void read(JsonObject json, String key) { set(json.get(key).getAsInt()); }
    }
    public static final class Builder {
        private final ArrayDeque<String> sections = new ArrayDeque<>();
        private final Map<String, Value<?>> values = new LinkedHashMap<>();
        public Builder translation(String text) { return this; }
        public Builder comment(String... text) { return this; }
        public Builder push(String section) { sections.addLast(section); return this; }
        public Builder pop() { sections.removeLast(); return this; }
        private String key(String name) { return String.join(".", sections) + "." + name; }
        public BooleanValue define(String name, boolean fallback) {
            var value = new BooleanValue(fallback); values.put(key(name), value); return value;
        }
        public IntValue defineInRange(String name, int fallback, int min, int max) {
            var value = new IntValue(fallback, min, max); values.put(key(name), value); return value;
        }
        public ClientConfig build() { return new ClientConfig(values); }
    }
}
