package fastimageframes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.midnightdust.lib.config.MidnightConfig;
import fastimageframes.bake.BakedFrames;
import fastimageframes.config.ConfigValues;
import fastimageframes.hd.HdCache;
import fastimageframes.hd.HdMaps;
import fastimageframes.ifc.IfcLink;
import fastimageframes.render.Shaders;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FastImageFrames implements ClientModInitializer {

    public static final String MOD_ID = "fastimageframes";
    public static final Logger LOGGER = LoggerFactory.getLogger("Fast ImageFrames");

    private static final Set<String> HEAD_SETTINGS = Set.of("headsEnabled", "headAtlas", "headBatching", "headSkinCache", "headBake",
            "headDistanceCulling", "headDistance", "headOcclusion");

    @Override
    public void onInitializeClient() {
        migrate();
        ConfigValues.captureDefaults();
        MidnightConfig.init(MOD_ID, FifConfig.class);
        IfcLink.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Shaders.tick();
            HdCache.INSTANCE.tick();
            HdMaps.INSTANCE.tick();
            BakedFrames.INSTANCE.tick();
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> BakedFrames.INSTANCE.onLoad(entity, level));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> BakedFrames.INSTANCE.onUnload(entity, level));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onRenderThread(client, () -> HdCache.INSTANCE.startSession(serverKey(client))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onRenderThread(client, HdCache.INSTANCE::endSession));
    }

    private static void migrate() {
        FabricLoader loader = FabricLoader.getInstance();
        Path config = loader.getConfigDir().resolve(MOD_ID + ".json");
        if (!Files.exists(config)) {
            JsonObject voltum = read(loader.getConfigDir().resolve("voltum.json"));
            JsonObject frames = new JsonObject();
            if (voltum != null) {
                for (Map.Entry<String, JsonElement> entry : voltum.entrySet()) {
                    if (!HEAD_SETTINGS.contains(entry.getKey())) {
                        frames.add(entry.getKey().equals("framesEnabled") ? "enabled" : entry.getKey(), entry.getValue());
                    }
                }
            }
            if (frames.size() > 0) {
                try {
                    Files.writeString(config, frames.toString(), StandardCharsets.UTF_8);
                    LOGGER.info("Settings of Voltum copied to {}", config.getFileName());
                } catch (IOException e) {
                    LOGGER.warn("Could not copy the settings of Voltum", e);
                }
            }
        }
        Path oldCache = loader.getGameDir().resolve("voltum").resolve("cache");
        Path cache = loader.getGameDir().resolve(MOD_ID).resolve("cache");
        if (Files.isDirectory(oldCache) && !Files.exists(cache)) {
            try {
                Files.createDirectories(cache.getParent());
                Files.move(oldCache, cache);
                LOGGER.info("HD image cache of Voltum moved to {}", cache);
            } catch (IOException e) {
                LOGGER.warn("Could not move the HD image cache of Voltum", e);
            }
        }
    }

    private static JsonObject read(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            JsonElement json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}", path.getFileName(), e);
            return null;
        }
    }

    private static void onRenderThread(Minecraft client, Runnable runnable) {
        if (client.isSameThread()) {
            runnable.run();
        } else {
            client.execute(runnable);
        }
    }

    private static String serverKey(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "server:" + server.ip.trim().toLowerCase(Locale.ROOT);
        }
        IntegratedServer integrated = client.getSingleplayerServer();
        if (integrated != null) {
            return "world:" + integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        }
        return null;
    }

}
