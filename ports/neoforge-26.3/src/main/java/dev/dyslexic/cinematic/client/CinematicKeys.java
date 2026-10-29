package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.client.KeyMapping;

/** The mod's own group in the controls screen (since 1.21.9 a key's category is a registered id, not a text key). */
final class CinematicKeys {
    private CinematicKeys() {}

    static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Cinematic.id("interactions"));
}
