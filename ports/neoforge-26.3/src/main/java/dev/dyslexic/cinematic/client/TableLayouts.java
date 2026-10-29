package dev.dyslexic.cinematic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dyslexic.cinematic.Cinematic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Three local profiles, loaded once; draft previews never touch the file or the server. */
final class TableLayouts {
    private TableLayouts() {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "cinematic-table-layouts.json";
    private static TableLayout[] profiles = {TableLayout.VANILLA, TableLayout.VANILLA, TableLayout.VANILLA};
    private static int selected;
    private static boolean loaded;
    private static TableLayout preview;
    private static TableLayout current = TableLayout.VANILLA;
    private record Saved(int version, int selected, TableLayout[] profiles) {}

    static Path path() { return FMLPaths.CONFIGDIR.get().resolve(FILE); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (Files.exists(path())) {
            try {
                if (Files.size(path()) > 65536) throw new IOException("Layout file is too large");
                Saved saved = JSON.fromJson(Files.readString(path()), Saved.class);
                if (saved == null || saved.version != 1 || saved.profiles == null || saved.profiles.length != 3) {
                    throw new IOException("Unsupported layout file");
                }
                for (int i = 0; i < 3; i++) profiles[i] = saved.profiles[i] == null ? TableLayout.VANILLA : saved.profiles[i];
                selected = Math.max(0, Math.min(2, saved.selected));
            } catch (IOException | RuntimeException e) {
                Cinematic.LOGGER.warn("[Cinematic] Could not load crafting-table layouts; using vanilla", e);
            }
        }
        current = profiles[selected];
    }

    static TableLayout current() { load(); return current; }
    static int selected() { load(); return selected; }
    static TableLayout[] profiles() { load(); return profiles.clone(); }
    static void preview(TableLayout draft) { preview = draft; }
    static void cancelPreview() { preview = null; }

    static void frame(double dt) {
        load();
        TableLayout target = preview == null ? profiles[selected] : preview;
        if (current.equals(target)) return;
        current = current.blend(target, 1 - Math.exp(-18 * dt));
        if (close(current, target)) current = target;
    }

    private static boolean close(TableLayout a, TableLayout b) {
        for (int i = 0; i < 7; i++) if (Math.abs(a.value(i) - b.value(i)) > 0.00001) return false;
        return true;
    }

    static boolean save(TableLayout[] drafts, int active) {
        if (drafts.length != 3 || active < 0 || active > 2 || Arrays.stream(drafts).anyMatch(java.util.Objects::isNull)) return false;
        try {
            LayoutFiles.write(path(), JSON.toJson(new Saved(1, active, drafts)) + "\n");
            profiles = drafts.clone();
            selected = active;
            preview = null;
            return true;
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not save crafting-table layouts", e);
            return false;
        }
    }
}
