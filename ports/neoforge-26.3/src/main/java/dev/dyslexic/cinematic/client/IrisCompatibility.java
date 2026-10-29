package dev.dyslexic.cinematic.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.dyslexic.cinematic.Cinematic;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.RenderPipelines;

/** Optional shader bindings; geometry follows the game's feature passes, including Iris's passes. */
final class IrisCompatibility {
    private IrisCompatibility() {}

    //? if <26.1 {
    /*private static Object iris;
    private static Method shadowPass;

    *///?}
    static void initialize() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Class<?> program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            //? if >=26.1 {
            Object iris = api.getMethod("getInstance").invoke(null);
            //?} else if <26.1 {
            /*iris = api.getMethod("getInstance").invoke(null);
            shadowPass = api.getMethod("isRenderingShadowPass");
            *///?}
            Method assign = api.getMethod("assignPipeline", RenderPipeline.class, program);
            assign.invoke(iris, CinematicPipelines.HIGHLIGHT, program(program, "BASIC"));
            try {
                copyEntityBindings();
            } catch (ReflectiveOperationException e) {
                //? if <26.1 {
                /*assign.invoke(iris, CinematicPipelines.FADING_ITEM, program(program, "ENTITIES_TRANSLUCENT"));
                *///?}
                assign.invoke(iris, CinematicPipelines.GLOW, program(program, "EMISSIVE_ENTITIES"));
                //? if >=26.1 {
                assign.invoke(iris, CinematicPipelines.ENCHANT_CIRCLE, program(program, "ENTITIES_TRANSLUCENT"));
                //?}
                Cinematic.LOGGER.warn("This Iris version lacks vanilla pipeline binding copies; using its public program selection", e);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Cinematic.LOGGER.warn("Iris is installed, but its pipeline API could not be used; scene effects keep vanilla shaders", e);
        }
    }

    private static void copyEntityBindings() throws ReflectiveOperationException {
        // Iris's API compares vertex formats by identity. Its extended entity format differs from NEW_ENTITY,
        // so EMISSIVE_ENTITIES otherwise selects the particle program SPS and loses our textured glow quads.
        // Copy only Iris's program bindings; our pipelines retain their blending, depth and vertex layout.
        Class<?> pipelines = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
        Method copy = pipelines.getMethod("copyPipeline", RenderPipeline.class, RenderPipeline.class);
        copy.invoke(null, RenderPipelines.EYES, CinematicPipelines.GLOW);
        //? if >=26.1 {
        copy.invoke(null, RenderPipelines.ENTITY_TRANSLUCENT, CinematicPipelines.ENCHANT_CIRCLE);
        //?} else if <26.1 {
        /*copy.invoke(null, RenderPipelines.ENTITY_TRANSLUCENT, CinematicPipelines.FADING_ITEM);
        *///?}
    }

    //? if >=26.1 {
    static void copyBinding(RenderPipeline original, RenderPipeline fading) {
    //?} else if <26.1 {
    /*static boolean inShadowPass() {
        if (shadowPass == null) return false;
    *///?}
        try {
            //? if >=26.1 {
            // Iris has separate combined item/glint programs in 26.3. Match the original
            // vertex attributes and bind groups when choosing a translucent counterpart.
            var flags = original.getShaderDefines().flags();
            RenderPipeline translucent = flags.contains("GLINT_SPECIAL") ? RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL
                    : flags.contains("GLINT") ? RenderPipelines.ITEM_TRANSLUCENT_GLINT : RenderPipelines.ENTITY_TRANSLUCENT;
            Class.forName("net.irisshaders.iris.pipeline.IrisPipelines")
                    // Copying the opaque item's Iris program also copies its alpha-test threshold.
                    // Photon then discards the whole item when the scene fades it to 28% opacity.
                    // Keep the fading pipeline's format/state, but use Iris's translucent entity program.
                    .getMethod("copyPipeline", RenderPipeline.class, RenderPipeline.class)
                    .invoke(null, translucent, fading);
            //?} else if <26.1 {
            /*return (boolean) shadowPass.invoke(iris);
            *///?}
        } catch (ReflectiveOperationException e) {
            //? if >=26.1 {
            throw new IllegalStateException("Could not preserve Iris's item shader while fading", e);
            //?} else if <26.1 {
            /*throw new IllegalStateException("Could not identify the Iris world rendering pass", e);
            *///?}
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object program(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
