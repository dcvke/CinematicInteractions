package dev.dyslexic.cinematic.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.station.StationKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Three independent profiles for each furnace type. Preview is local and never writes to disk. */
final class FurnaceLayouts {
    private FurnaceLayouts() {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "cinematic-furnace-layouts.json";
    private static FurnaceAppearance[][] profiles = defaults();
    private static int[] selected = new int[3];
    private static final FurnaceAppearance[] current = {FurnaceAppearance.VANILLA, FurnaceAppearance.VANILLA, FurnaceAppearance.VANILLA};
    private static final FurnaceAppearance[] preview = new FurnaceAppearance[3];
    private static boolean loaded;
    private record Saved(int version, int[] selected, FurnaceAppearance[][] profiles) {}

    static int index(StationKind kind) {
        return switch (kind) { case FURNACE -> 0; case SMOKER -> 1; case BLAST_FURNACE -> 2;
            default -> throw new IllegalArgumentException("Not a furnace: " + kind); };
    }
    private static FurnaceAppearance[][] defaults() {
        FurnaceAppearance[][] values = new FurnaceAppearance[3][3];
        for (FurnaceAppearance[] row : values) Arrays.fill(row, FurnaceAppearance.VANILLA);
        return values;
    }
    static Path path() { return FMLPaths.CONFIGDIR.get().resolve(FILE); }
    private static void load() {
        if (loaded) return;
        loaded = true;
        if (Files.exists(path())) try {
            if (Files.size(path()) > 131072) throw new IOException("Layout file is too large");
            Saved saved = JSON.fromJson(Files.readString(path()), Saved.class);
            if (saved == null || saved.version != 1 || saved.selected == null || saved.selected.length != 3
                    || saved.profiles == null || saved.profiles.length != 3) throw new IOException("Unsupported layout file");
            FurnaceAppearance[][] restored = defaults();
            for (int i = 0; i < 3; i++) {
                if (saved.profiles[i] == null || saved.profiles[i].length != 3) throw new IOException("Invalid profile count");
                for (int j = 0; j < 3; j++) if (saved.profiles[i][j] != null) restored[i][j] = saved.profiles[i][j];
            }
            profiles = restored;
            for (int i = 0; i < 3; i++) selected[i] = Math.max(0, Math.min(2, saved.selected[i]));
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not load furnace layouts; using vanilla", e);
        }
        for (int i = 0; i < 3; i++) current[i] = profiles[i][selected[i]];
    }
    static FurnaceAppearance current(StationKind kind) { load(); return current[index(kind)]; }
    static FurnaceAppearance[] profiles(StationKind kind) { load(); return profiles[index(kind)].clone(); }
    static int selected(StationKind kind) { load(); return selected[index(kind)]; }
    static void preview(StationKind kind, FurnaceAppearance draft) { preview[index(kind)] = draft; }
    static void cancelPreview(StationKind kind) { preview[index(kind)] = null; }
    static void frame(double dt) {
        load();
        for (int i = 0; i < 3; i++) {
            FurnaceAppearance target = preview[i] == null ? profiles[i][selected[i]] : preview[i];
            if (current[i].equals(target)) continue;
            current[i] = current[i].blend(target, 1 - Math.exp(-18 * dt));
            boolean close = true;
            for (int r = 0; r < 5; r++) for (int f = 0; f < 10; f++)
                close &= Math.abs(current[i].region(r).value(f) - target.region(r).value(f)) < 0.00001;
            if (close) current[i] = target;
        }
    }
    static boolean save(StationKind kind, FurnaceAppearance[] drafts, int active) {
        load();
        if (drafts.length != 3 || active < 0 || active > 2 || Arrays.stream(drafts).anyMatch(java.util.Objects::isNull)) return false;
        FurnaceAppearance[][] next = profiles.clone();
        int[] nextSelected = selected.clone();
        next[index(kind)] = drafts.clone(); nextSelected[index(kind)] = active;
        try {
            LayoutFiles.write(path(), JSON.toJson(new Saved(1, nextSelected, next)) + "\n");
            profiles = next; selected = nextSelected; cancelPreview(kind);
            return true;
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not save furnace layouts", e);
            return false;
        }
    }
}
