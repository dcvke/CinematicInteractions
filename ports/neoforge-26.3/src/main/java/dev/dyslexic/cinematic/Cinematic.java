package dev.dyslexic.cinematic;

import com.mojang.logging.LogUtils;
import dev.dyslexic.cinematic.net.Network;
import dev.dyslexic.cinematic.station.StationEvents;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Cinematic.MODID)
public final class Cinematic {
    public static final String MODID = "cinematic";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Cinematic(IEventBus modBus, ModContainer container) {
        modBus.addListener(Network::register);
        NeoForge.EVENT_BUS.register(StationEvents.class);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
