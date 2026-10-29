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

/** Three independent profiles for smithing and cartography. Preview is local and never writes to disk. */
final class SurfaceLayouts {
    private SurfaceLayouts() {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "cinematic-surface-layouts.json";
    private static SurfaceAppearance[][] profiles = defaults();
    private static int[] selected = new int[2];
    private static final SurfaceAppearance[] current = {SurfaceAppearance.VANILLA, SurfaceAppearance.VANILLA};
    private static final SurfaceAppearance[] preview = new SurfaceAppearance[2];
    private static boolean loaded;
    private record Saved(int version, int[] selected, SurfaceAppearance[][] profiles) {}

    static int index(StationKind kind) {
        return switch (kind) { case SMITHING -> 0; case CARTOGRAPHY -> 1;
            default -> throw new IllegalArgumentException("Not a tabletop: " + kind); };
    }
    private static SurfaceAppearance[][] defaults() {
        SurfaceAppearance[][] values = new SurfaceAppearance[2][3];
        for (SurfaceAppearance[] row : values) Arrays.fill(row, SurfaceAppearance.VANILLA);
        return values;
    }
    static Path path() { return FMLPaths.CONFIGDIR.get().resolve(FILE); }
    private static void load() {
        if (loaded) return;
        loaded = true;
        if (Files.exists(path())) try {
            if (Files.size(path()) > 131072) throw new IOException("Layout file is too large");
            Saved saved = JSON.fromJson(Files.readString(path()), Saved.class);
            if (saved == null || saved.version != 1 || saved.selected == null || saved.selected.length != 2
                    || saved.profiles == null || saved.profiles.length != 2) throw new IOException("Unsupported layout file");
            SurfaceAppearance[][] restored = defaults();
            for (int i = 0; i < 2; i++) {
                if (saved.profiles[i] == null || saved.profiles[i].length != 3) throw new IOException("Invalid profile count");
                for (int j = 0; j < 3; j++) if (saved.profiles[i][j] != null) restored[i][j] = saved.profiles[i][j];
            }
            profiles = restored;
            for (int i = 0; i < 2; i++) selected[i] = Math.max(0, Math.min(2, saved.selected[i]));
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not load tabletop layouts; using vanilla", e);
        }
        for (int i = 0; i < 2; i++) current[i] = profiles[i][selected[i]];
    }
    static SurfaceAppearance current(StationKind kind) { load(); return current[index(kind)]; }
    static SurfaceAppearance[] profiles(StationKind kind) { load(); return profiles[index(kind)].clone(); }
    static int selected(StationKind kind) { load(); return selected[index(kind)]; }
    static void preview(StationKind kind, SurfaceAppearance draft) { preview[index(kind)] = draft; }
    static void cancelPreview(StationKind kind) { preview[index(kind)] = null; }
    static void frame(double dt) {
        load();
        for (int i = 0; i < 2; i++) {
            SurfaceAppearance target = preview[i] == null ? profiles[i][selected[i]] : preview[i];
            if (current[i].equals(target)) continue;
            current[i] = current[i].blend(target, 1 - Math.exp(-18 * dt));
            boolean close = true;
            for (int r = 0; r < 3; r++) for (int f = 0; f < 7; f++)
                close &= Math.abs(current[i].region(r).value(f) - target.region(r).value(f)) < 0.00001;
            if (close) current[i] = target;
        }
    }
    static boolean save(StationKind kind, SurfaceAppearance[] drafts, int active) {
        load();
        if (drafts.length != 3 || active < 0 || active > 2 || Arrays.stream(drafts).anyMatch(java.util.Objects::isNull)) return false;
        SurfaceAppearance[][] next = profiles.clone();
        int[] nextSelected = selected.clone();
        next[index(kind)] = drafts.clone(); nextSelected[index(kind)] = active;
        try {
            LayoutFiles.write(path(), JSON.toJson(new Saved(1, nextSelected, next)) + "\n");
            profiles = next; selected = nextSelected; cancelPreview(kind);
            return true;
        } catch (IOException | RuntimeException e) {
            Cinematic.LOGGER.warn("[Cinematic] Could not save tabletop layouts", e);
            return false;
        }
    }
}
