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

/** Three independent profiles for each supported station. Preview is local and never writes to disk. */
final class SpatialLayouts {
    private SpatialLayouts() {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "cinematic-spatial-layouts.json";
    private static SpatialAppearance[][] profiles = defaults();
    private static final int TYPES = 12;
    private static int[] selected = new int[TYPES];
    private static final SpatialAppearance[] current = vanillaRow();
    private static final SpatialAppearance[] preview = new SpatialAppearance[TYPES];
    private static boolean loaded;
    private static volatile boolean customShelf;
    static boolean customShelf() { return customShelf; }
    private record Saved(int version, int[] selected, SpatialAppearance[][] profiles) {}

    static int index(StationKind kind) {
        return switch (kind) { case CRAFTER -> 0; case CHISELED_BOOKSHELF -> 1; case BREWING -> 2; case LOOM -> 3; case ENCHANTING -> 4; case ANVIL -> 5; case GRINDSTONE -> 6; case STONECUTTER -> 7;
            case CAMPFIRE -> 8; case LECTERN -> 9; case BEACON -> 10; case JUKEBOX -> 11;
            default -> throw new IllegalArgumentException("Not a spatial layout: " + kind); };
    }
    private static SpatialAppearance[][] defaults() {
        SpatialAppearance[][] values = new SpatialAppearance[TYPES][3];
        for (SpatialAppearance[] row : values) Arrays.fill(row, SpatialAppearance.VANILLA);
        return values;
    }
    private static SpatialAppearance[] vanillaRow() {
        SpatialAppearance[] row = new SpatialAppearance[TYPES]; Arrays.fill(row, SpatialAppearance.VANILLA); return row;
    }
    static Path path() { return FMLPaths.CONFIGDIR.get().resolve(FILE); }
    private static void load() {
        if (loaded) return;
        loaded = true;
        if (Files.exists(path())) try {
            if (Files.size(path()) > 131072) throw new IOException("Layout file is too large");
            Saved saved = JSON.fromJson(Files.readString(path()), Saved.class);
            // Append new station rows without changing any existing profile or selection.
            int rows = saved != null && saved.version == 1 ? 3 : saved != null && saved.version == 2 ? 5 : saved != null && saved.version == 3 ? TYPES : -1;
            if (rows < 0 || saved.selected == null || saved.selected.length != rows
                    || saved.profiles == null || saved.profiles.length != rows) throw new IOException("Unsupported layout file");
            SpatialAppearance[][] restored = defaults();
            for (int i = 0; i < rows; i++) {
                if (saved.profiles[i] == null || saved.profiles[i].length != 3) throw new IOException("Invalid profile count");
                for (int j = 0; j < 3; j++) if (saved.profiles[i][j] != null) restored[i][j] = saved.profiles[i][j];
            }
            profiles = restored;
            for (int i = 0; i < rows; i++) selected[i] = Math.max(0, Math.min(2, saved.selected[i]));
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not load station layouts; using vanilla", e);
        }
        for (int i = 0; i < TYPES; i++) current[i] = profiles[i][selected[i]];
    }
    static SpatialAppearance current(StationKind kind) { load(); return current[index(kind)]; }
    static SpatialAppearance[] profiles(StationKind kind) { load(); return profiles[index(kind)].clone(); }
    static int selected(StationKind kind) { load(); return selected[index(kind)]; }
    static void preview(StationKind kind, SpatialAppearance draft) { preview[index(kind)] = draft; }
    static void cancelPreview(StationKind kind) { preview[index(kind)] = null; }
    static void frame(double dt) {
        load();
        for (int i = 0; i < TYPES; i++) {
            SpatialAppearance target = preview[i] == null ? profiles[i][selected[i]] : preview[i];
            if (current[i].equals(target)) continue;
            current[i] = current[i].blend(target, 1 - Math.exp(-18 * dt));
            boolean close = true;
            for (int r = -1; r < 9; r++) for (int f = 0; f < 7; f++)
                close &= Math.abs(current[i].region(r).value(f) - target.region(r).value(f)) < 0.00001;
            if (close) current[i] = target;
        }
        boolean custom = !current[1].equals(SpatialAppearance.VANILLA);
        if (customShelf != custom) {
            customShelf = custom;
            // The shelf's static occupied pictures change only at this boundary. Moving books use a BER.
            ShelfLayoutBooks.layoutChanged(custom);
        }
    }
    static boolean save(StationKind kind, SpatialAppearance[] drafts, int active) {
        load();
        if (drafts.length != 3 || active < 0 || active > 2 || Arrays.stream(drafts).anyMatch(java.util.Objects::isNull)) return false;
        SpatialAppearance[][] next = profiles.clone();
        int[] nextSelected = selected.clone();
        next[index(kind)] = drafts.clone(); nextSelected[index(kind)] = active;
        try {
            LayoutFiles.write(path(), JSON.toJson(new Saved(3, nextSelected, next)) + "\n");
            profiles = next; selected = nextSelected; cancelPreview(kind);
            return true;
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not save station layouts", e);
            return false;
        }
    }
}
