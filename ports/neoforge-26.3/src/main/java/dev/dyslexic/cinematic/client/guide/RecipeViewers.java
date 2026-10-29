package dev.dyslexic.cinematic.client.guide;

import dev.dyslexic.cinematic.Cinematic;
import javax.annotation.Nullable;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** The installed recipe viewer, if any. Only JEI is supported; without it the field guide simply does not appear. */
public final class RecipeViewers {
    private RecipeViewers() {}

    private static boolean failed;

    /** The viewer once its runtime is ready (after the world has loaded its recipes), else null. */
    @Nullable
    public static RecipeViewer get() {
        if (failed || !ModList.get().isLoaded("jei")) {
            return null;
        }
        try {
            // the adapter names JEI's classes: it is loaded only when JEI is installed
            return dev.dyslexic.cinematic.client.compat.jei.JeiRecipeViewer.current();
        } catch (LinkageError | RuntimeException e) {
            failed = true;
            Cinematic.LOGGER.warn("[Cinematic] JEI integration unavailable; the scenes go on without the field guide", e);
            return null;
        }
    }
}
