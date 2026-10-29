package dev.dyslexic.cinematic.client.compat;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.client.Scene;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.client.Minecraft;

/** Let first-person body mods keep their normal view outside a cinematic scene. */
public final class FirstPersonBodyCompat {
    private FirstPersonBodyCompat() {}

    public static boolean hideLocalBody() {
        Minecraft mc = Minecraft.getInstance();
        return Scene.active() && mc.player != null && mc.options.getCameraType().isFirstPerson();
    }

    public static void register() {
        if (ModList.get().isLoaded("firstperson")) {
            registerFirstPersonModel();
        }
        if (ModList.get().isLoaded("realcamera")) {
            registerRealCamera();
        }
    }

    private static void registerFirstPersonModel() {
        try {
            Class<?> api = Class.forName("dev.tr7zw.firstperson.api.FirstPersonAPI");
            Class<?> handlerType = Class.forName("dev.tr7zw.firstperson.api.ActivationHandler");
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "preventFirstperson" -> hideLocalBody();
                        case "toString" -> "Cinematic Interactions first-person scene handler";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            api.getMethod("registerPlayerHandler", Object.class).invoke(null, handler);
        } catch (ReflectiveOperationException | LinkageError error) {
            Cinematic.LOGGER.warn("Could not register First Person Model scene compatibility", error);
        }
    }

    private static void registerRealCamera() {
        try {
            Class<?> helper = Class.forName("com.xtracr.realcamera.compat.DisableHelper");
            Predicate<Object> whileInScene = player -> hideLocalBody();
            for (String feature : new String[] {"MAIN_FEATURE", "RENDER_MODEL", "RENDER_HANDS"}) {
                Field entryField = helper.getField(feature);
                Object entry = entryField.get(null);
                Method registerOr = entry.getClass().getMethod("registerOr", Predicate.class);
                registerOr.invoke(entry, whileInScene);
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            Cinematic.LOGGER.warn("Could not register Real Camera scene compatibility", error);
        }
    }
}
