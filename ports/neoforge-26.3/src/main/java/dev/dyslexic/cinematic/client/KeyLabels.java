package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
//? if <26.3 {
/*import org.lwjgl.glfw.GLFW;
*///?}


/** Physical letter keys keep their Latin label when the operating-system layout changes. */
final class KeyLabels {
    private KeyLabels() {}

    static Component of(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        int code = key.getValue();
        //? if >=26.3 {
        if (key.getType() == InputConstants.Type.KEYBOARD && code >= InputConstants.KEY_A && code <= InputConstants.KEY_Z) {
            return Component.literal(String.valueOf((char) ('A' + code - InputConstants.KEY_A)));
        //?} else if <26.3 {
        /*if (key.getType() == InputConstants.Type.KEYBOARD && code >= GLFW.GLFW_KEY_A && code <= GLFW.GLFW_KEY_Z) {
            return Component.literal(String.valueOf((char) code));
        *///?}
        }
        return mapping.getTranslatedKeyMessage();
    }
}
