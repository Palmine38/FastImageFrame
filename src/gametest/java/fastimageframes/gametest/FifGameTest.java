package fastimageframes.gametest;

import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import fastimageframes.bake.BakeableFrame;
import fastimageframes.bake.BakedFrames;
import fastimageframes.config.ConfigValues;
import fastimageframes.config.FifConfigScreen;
import fastimageframes.hd.HdCache;
import fastimageframes.hd.HdMaps;
import fastimageframes.ifc.IfcLink;
import fastimageframes.render.OptimizedFrameMesh;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
//#if MC >= 1.21.9
import net.minecraft.client.input.KeyEvent;
//#endif
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class FifGameTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("fastimageframes-gametest");
    private static final int WALL = 40;
    private static final double VIEW_DISTANCE = 28.0;
    private static final double MAX_VISUAL_DIFF = 1.0;
    private static final String INVISIBLE_FRAMES_PACK = "fif-invisible-frames";

    private final List<ItemFrame> wallFrames = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private int baseX;
    private int baseY;
    private int baseZ;
    private int mapCounter;

    private boolean shaders;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.framerateLimit().set(260);
            client.options.enableVsync().set(false);
            client.options.cloudStatus().set(CloudStatus.OFF);
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        });
        if (FakeImageFrameServer.available()) {
            setImageFrameClientOption(context, "notifyWhenServerSupports", false);
        }
        String pack = System.getenv("SHADER_PACK");
        if (pack != null) {
            enableShaderPack(context, Path.of(pack));
            shaders = true;
        }
        if (System.getenv("FIF_VISUAL_ONLY") != null) {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                prepareWorld(world);
                context.waitTicks(200);
                waitForChunks(world);
                if (shaders) {
                    check(context.computeOnClient(client -> fastimageframes.render.Shaders.active()), "shader pack in use: " + Path.of(pack).getFileName());
                }
                visualChecks(context, world);
                logResults();
                context.waitTicks(100);
            }
            return;
        }
        if (System.getenv("FIF_ITEMS_ONLY") != null) {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                prepareWorld(world);
                context.waitTicks(200);
                waitForChunks(world);
                itemWallBenchmark(context, world);
                logResults();
                context.waitTicks(100);
            }
            return;
        }
        if (System.getenv("FIF_ANIM_ONLY") != null) {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                prepareWorld(world);
                context.waitTicks(200);
                waitForChunks(world);
                animatedWallBenchmark(context, world);
                logResults();
                context.waitTicks(100);
            }
            return;
        }
        if (System.getenv("FIF_OCCLUSION_ONLY") != null) {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                prepareWorld(world);
                context.waitTicks(200);
                waitForChunks(world);
                lightChecks(context, world);
                occlusionChecks(context, world);
                logResults();
                context.waitTicks(100);
            }
            return;
        }
        if (System.getenv("FIF_GPU_AB") != null) {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                prepareWorld(world);
                context.waitTicks(200);
                waitForChunks(world);
                visualChecks(context, world);
                gpuAb(context, world);
                logResults();
                context.waitTicks(100);
            }
            return;
        }
        if (System.getenv("FIF_BENCH_ONLY") != null) {
            benchmarkOnly(context);
            return;
        }
        if (System.getenv("FIF_SCREEN_ONLY") != null) {
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                prepareWorld(world);
                configScreenChecks(context);
                logResults();
                context.waitTicks(100);
            }
            return;
        }
        boolean cacheTests = FakeImageFrameServer.available() && IfcLink.isLinked();
        TestWorldSave save = null;
        int[] beforeRejoin = new int[3];
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            prepareWorld(world);
            if (cacheTests) {
                cacheChecks(context, world);
                save = world.getWorldSave();
                context.waitTicks(100);
                beforeRejoin[0] = FakeImageFrameServer.requests(cacheBig);
                beforeRejoin[1] = FakeImageFrameServer.requests(cacheDenied);
                beforeRejoin[2] = FakeImageFrameServer.ACKNOWLEDGED.get();
            } else {
                if (FakeImageFrameServer.available()) {
                    results.add("ImageFrame Client not recognized, HD cache checks skipped");
                }
                mainChecks(context, world);
            }
        }
        if (save != null) {
            try (TestSingleplayerContext world = save.open()) {
                waitForChunks(world);
                cacheRejoinChecks(context, world, beforeRejoin[0], beforeRejoin[1], beforeRejoin[2]);
                world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %d %d", baseX, baseY, baseZ));
                context.waitTicks(20);
                waitForChunks(world);
                mainChecks(context, world);
            }
        }
    }

    private void prepareWorld(TestSingleplayerContext world) {
        waitForChunks(world);
        world.getServer().runCommand(shaders ? "time set 10000" : "time set noon");
        world.getServer().runCommand("gamerule doDaylightCycle false");
        world.getServer().runCommand("gamerule advance_time false");
        world.getServer().runOnServer(server -> {
            BlockPos base = server.getPlayerList().getPlayers().get(0).blockPosition();
            baseX = base.getX();
            baseY = base.getY();
            baseZ = base.getZ();
        });
    }

    private void benchmarkOnly(ClientGameTestContext context) {
        List<String> mods = new ArrayList<>();
        for (String id : new String[]{"sodium", "iris", "lithium", "entityculling", "cloth-config", "imageframeclient"}) {
            if (FabricLoader.getInstance().isModLoaded(id)) {
                mods.add(id);
            }
        }
        results.add("benchmark only, other mods: " + (mods.isEmpty() ? "none" : String.join(", ", mods)));
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            prepareWorld(world);
            benchmark(context, world);
            if (FakeImageFrameServer.available() && IfcLink.isLinked()) {
                hdWallBenchmark(context, world);
            }
            logResults();
            context.waitTicks(100);
        }
    }

    private void mainChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        visualChecks(context, world);
        resourcePackChecks(context, world);
        if (FakeImageFrameServer.available()) {
            imageFrameClientChecks(context, world);
        } else {
            results.add("ImageFrame Client not loaded, compatibility checks skipped");
        }
        benchmark(context, world);
        animatedWallBenchmark(context, world);
        if (itemFramesBaked()) {
            itemWallBenchmark(context, world);
            lightChecks(context, world);
        }
        occlusionChecks(context, world);
        if (FakeImageFrameServer.available() && IfcLink.isLinked()) {
            hdWallBenchmark(context, world);
        }
        configScreenChecks(context);
        logResults();
        FakeImageFrameServer.ANIMATION = null;
        context.waitTicks(100);
    }

    private void configScreenChecks(ClientGameTestContext context) {
        String key = "fastimageframes.config.";
        context.setScreen(() -> new FifConfigScreen(null));
        context.waitTicks(10);
        context.takeScreenshot("config-main");
        context.clickScreenButton(key + "page.render");
        context.waitTicks(10);
        context.takeScreenshot("config-render");
        context.clickScreenButton(key + "back");
        context.clickScreenButton(key + "page.cache");
        context.waitTicks(10);
        context.takeScreenshot("config-cache");
        boolean disk = FifConfig.hdDiskCache;
        click(context, "hdDiskCache");
        context.clickScreenButton("gui.cancel");
        context.waitTicks(5);
        check(context.computeOnClient(client -> screen(client) == null) && FifConfig.hdDiskCache == disk, "config screen: Cancel keeps the saved settings");

        FifConfig.LodQuality quality = FifConfig.hdLodQuality;
        int distance = FifConfig.hdLodCustomDistance;
        context.setScreen(() -> new FifConfigScreen(null));
        context.waitTicks(5);
        context.clickScreenButton(key + "page.lod");
        context.waitTicks(10);
        hover(context, "hdLodQuality");
        context.waitTicks(30);
        context.takeScreenshot("config-lod-tooltip");
        check(!context.computeOnClient(client -> widget(client, "hdLodCustomDistance").active), "config screen: custom distance locked with a preset");
        for (int i = 0; i < 5 && context.computeOnClient(client -> !widget(client, "hdLodQuality").getMessage().getString().contains("Custom")); i++) {
            click(context, "hdLodQuality");
        }
        check(context.computeOnClient(client -> widget(client, "hdLodCustomDistance").active), "config screen: Custom preset unlocks the distance and the delay");
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(5);
        context.takeScreenshot("config-lod-custom");
        context.clickScreenButton("gui.done");
        context.waitTicks(5);
        check(FifConfig.hdLodQuality == FifConfig.LodQuality.CUSTOM, "config screen: Done saves the Custom preset (low quality from "
                + FifConfig.hdLodCustomDistance + " blocks)");
        context.runOnClient(client -> {
            ConfigValues values = ConfigValues.load();
            values.set("hdLodQuality", quality);
            values.set("hdLodCustomDistance", distance);
            values.apply();
        });
    }

    private static AbstractWidget widget(Minecraft client, String option) {
        String key = "fastimageframes.config." + option;
        for (GuiEventListener child : screen(client).children()) {
            if (child instanceof AbstractWidget widget && widget.getMessage().getContents() instanceof TranslatableContents message
                    && message.getArgs().length > 0 && message.getArgs()[0] instanceof Component name
                    && name.getContents() instanceof TranslatableContents nameKey && nameKey.getKey().equals(key)) {
                return widget;
            }
        }
        throw new AssertionError("no " + option + " on " + screen(client));
    }

    private static net.minecraft.client.gui.screens.Screen screen(Minecraft client) {
        //#if MC >= 26.2
        return client.gui.screen();
        //#else
        return client.screen;
        //#endif
    }

    private static void hover(ClientGameTestContext context, String option) {
        double[] position = context.computeOnClient(client -> {
            AbstractWidget widget = widget(client, option);
            double scale = client.getWindow().getGuiScale();
            return new double[]{(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(position[0], position[1]);
    }

    private static void click(ClientGameTestContext context, String option) {
        context.runOnClient(client -> {
            Button button = (Button) widget(client, option);
            //#if MC >= 1.21.9
            button.onPress(new KeyEvent(0, 0, 0));
            //#else
            button.onPress();
            //#endif
        });
        context.waitTicks(3);
    }

    private int cacheBig;
    private int cacheDenied;
    private int cacheVanilla;

    private void cacheChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        int sceneZ = baseZ + 120;
        List<Integer> ids = new ArrayList<>();
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            Random random = new Random(11);
            for (int i = 0; i < 3; i++) {
                MapId id = randomMap(level, random, false);
                ids.add(id.id());
                BlockPos pos = new BlockPos(baseX + (i - 1) * 2, baseY + 1, sceneZ);
                level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                spawnFrame(level, pos, Direction.SOUTH, id, false);
            }
        });
        cacheBig = ids.get(0);
        cacheDenied = ids.get(1);
        cacheVanilla = ids.get(2);
        byte[] big = noisePng(512);
        FakeImageFrameServer.HD_IMAGES.put(cacheBig, big);
        FakeImageFrameServer.HD_IMAGES.put(cacheDenied, png(0, 0, 1, 1, 256));
        FakeImageFrameServer.DENY_REMAINING.put(cacheDenied, new AtomicInteger(3));
        setFif(context, true);
        context.waitFor(client -> HdCache.INSTANCE.isServerSupported(), 400);

        standAtCache(world, baseX - 2, sceneZ, 1.3);
        awaitMap(context, "big HD map shown", cacheBig, client -> hdShown(cacheBig), 600);
        context.waitTicks(20);
        check(FakeImageFrameServer.requests(cacheBig) == 1, "HD map sent in " + (big.length + 31999) / 32000 + " packets downloaded once, "
                + FakeImageFrameServer.requests(cacheBig) + " request(s)");
        check(context.computeOnClient(client -> HdCache.INSTANCE.hasPngInRam(cacheBig) && HdCache.INSTANCE.isOnDisk(cacheBig)),
                "HD png kept in RAM and on disk");

        standAtCache(world, baseX, sceneZ, 3.0);
        awaitMap(context, "refused HD map shown", cacheDenied, client -> hdShown(cacheDenied), 800);
        int deniedRequests = FakeImageFrameServer.requests(cacheDenied);
        check(deniedRequests == 4, "HD map refused 3 times then sent: " + deniedRequests + " requests");

        context.waitTicks(100);
        check(FakeImageFrameServer.requests(cacheVanilla) == 1, "map without HD image asked once, " + FakeImageFrameServer.requests(cacheVanilla) + " request(s)");
        check(!context.computeOnClient(client -> HdMaps.INSTANCE.hasSource(cacheVanilla)), "map without HD image keeps its vanilla colors");

        long version = context.computeOnClient(client -> HdCache.INSTANCE.getSourceVersion(cacheBig));
        FakeImageFrameServer.HD_IMAGES.put(cacheBig, png(1, 1, 3, 3, 512));
        world.getServer().runOnServer(server -> FakeImageFrameServer.sendUpdated(server.getPlayerList().getPlayers().get(0), cacheBig));
        awaitMap(context, "download after the update signal", cacheBig, client -> FakeImageFrameServer.requests(cacheBig) == 2, 200);
        check(context.computeOnClient(client -> hdShown(cacheBig)), "old HD image kept on screen while the new one downloads");
        awaitMap(context, "updated HD image shown", cacheBig, client -> HdCache.INSTANCE.getSourceVersion(cacheBig) > version
                && HdMaps.INSTANCE.describe(cacheBig).startsWith("source v" + HdCache.INSTANCE.getSourceVersion(cacheBig) + ",") && hdShown(cacheBig), 200);
        check(true, "update signal: HD image downloaded again and shown");
        context.waitTicks(100);
        check(FakeImageFrameServer.requests(cacheBig) == 2 && context.computeOnClient(client -> HdCache.INSTANCE.isOnDisk(cacheBig)),
                "updated HD image cached, no other download");
    }

    private void cacheRejoinChecks(ClientGameTestContext context, TestSingleplayerContext world, int big, int denied, int acknowledged) {
        int sceneZ = baseZ + 120;
        standAtCache(world, baseX, sceneZ, 3.0);
        awaitMap(context, "HD maps shown after joining again", cacheBig, client -> hdShown(cacheBig) && hdShown(cacheDenied), 400);
        context.waitFor(client -> HdCache.INSTANCE.isServerSupported(), 400);
        context.waitTicks(100);
        check(FakeImageFrameServer.requests(cacheBig) == big && FakeImageFrameServer.requests(cacheDenied) == denied,
                "joining again: HD maps shown from the disk cache, no download");
        check(FakeImageFrameServer.ACKNOWLEDGED.get() > acknowledged, "ImageFrame Client still answers the handshake");
        context.takeScreenshot("cache-rejoin");
    }

    private void standAtCache(TestSingleplayerContext world, int x, int sceneZ, double distance) {
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f 180 0", x + 0.5, baseY + 1, sceneZ + 0.5 + distance));
    }

    private static void awaitMap(ClientGameTestContext context, String what, int mapId, Predicate<Minecraft> predicate, int timeout) {
        try {
            context.waitFor(predicate, timeout);
        } catch (AssertionError e) {
            String state = context.computeOnClient(client -> "cache: " + HdCache.INSTANCE.describe(mapId) + " | lod: " + HdMaps.INSTANCE.describe(mapId));
            LOGGER.error("[FIF] TIMEOUT {} (map {}, {} request(s), {})", what, mapId, FakeImageFrameServer.requests(mapId), state);
            throw e;
        }
    }

    private void logResults() {
        String mc = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        LOGGER.info("[FIF] ===== RESULTS {} =====", mc);
        for (String line : results) {
            LOGGER.info("[FIF] {}", line);
        }
    }

    private void visualChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        int sceneZ = baseZ + 60;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            Random random = new Random(7);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    BlockPos pos = new BlockPos(baseX - 1 + col, baseY + 1 + row, sceneZ);
                    level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                    spawnFrame(level, pos, Direction.SOUTH, randomMap(level, random, row == 1 && col == 1), false);
                }
            }
            BlockPos lone = new BlockPos(baseX + 4, baseY + 2, sceneZ);
            level.setBlockAndUpdate(lone.north(), Blocks.STONE.defaultBlockState());
            spawnFrame(level, lone, Direction.SOUTH, randomMap(level, random, false), false);
            BlockPos invisible = new BlockPos(baseX - 4, baseY + 2, sceneZ);
            level.setBlockAndUpdate(invisible.north(), Blocks.STONE.defaultBlockState());
            spawnFrame(level, invisible, Direction.SOUTH, randomMap(level, random, false), true);
            for (int i = 0; i < 2; i++) {
                BlockPos glass = new BlockPos(baseX + 7 + i, baseY + 2, sceneZ);
                level.setBlockAndUpdate(glass.north(), Blocks.GLASS.defaultBlockState());
                spawnFrame(level, glass, Direction.SOUTH, randomMap(level, random, false), false);
            }
            BlockPos floor = new BlockPos(baseX + 1, baseY, sceneZ + 3);
            level.setBlockAndUpdate(floor.below(), Blocks.STONE.defaultBlockState());
            spawnFrame(level, floor, Direction.UP, randomMap(level, random, false), false);
            ItemStack[] items = itemSamples();
            for (int i = 0; i < items.length; i++) {
                BlockPos pos = new BlockPos(baseX - 16 + i, baseY + 1 + i % 2, sceneZ);
                level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                spawnItemFrame(level, pos, Direction.SOUTH, items[i], i == 3, i % 4);
            }
            if (shaders) {
                for (int x = 0; x < 4; x++) {
                    for (int z = 0; z < 4; z++) {
                        BlockPos top = new BlockPos(baseX + 20 + x, baseY + 5, sceneZ + z);
                        level.setBlockAndUpdate(top.below(), Blocks.GLASS.defaultBlockState());
                        spawnFrame(level, top, Direction.UP, randomMap(level, random, false), false);
                    }
                }
            }
        });
        context.waitTicks(60);

        String[][] views = {
                {"front", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 165 10", baseX + 1.5, baseY + 1, sceneZ + 6.5)},
                {"grazing", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 120 5", baseX + 8.5, baseY + 2, sceneZ + 2.5)},
                {"behind", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 0", baseX + 7.5, baseY + 1, sceneZ - 4.5)},
                {"floor", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 60", baseX + 1.5, baseY + 1, sceneZ + 5.5)},
                {"items", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 5", baseX - 10.5, baseY + 1, sceneZ + 6.0)},
                {"items-close", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 200 15", baseX - 13.5, baseY + 1, sceneZ + 2.5)},
                {"shadow", String.format(Locale.ROOT, "tp @a %.1f %d %.1f 90 50", baseX + 30.5, baseY + 6, sceneZ + 2.0)},
        };
        if (!shaders) {
            views = java.util.Arrays.copyOf(views, views.length - 1);
        }
        for (String[] view : views) {
            world.getServer().runCommand(view[1]);
            setFif(context, false);
            context.waitTicks(30);
            Path vanilla = context.takeScreenshot("visual-" + view[0] + "-vanilla");
            double limit = MAX_VISUAL_DIFF;
            if (shaders) {
                context.waitTicks(10);
                double noise = differingPercent(vanilla, context.takeScreenshot("visual-" + view[0] + "-vanilla-again"));
                limit = Math.max(MAX_VISUAL_DIFF, noise * 2 + 0.5);
                LOGGER.info("[FIF] visual {}: {}% pixels differ between two vanilla frames", view[0], String.format(Locale.ROOT, "%.3f", noise));
            }
            setFif(context, true);
            context.waitTicks(30);
            Path fast = context.takeScreenshot("visual-" + view[0] + "-fast");
            double diff = differingPercent(vanilla, fast);
            if (System.getenv("FIF_DIAG") != null) {
                setBake(context, false);
                context.waitTicks(30);
                Path unbaked = context.takeScreenshot("visual-" + view[0] + "-not-baked");
                LOGGER.info("[FIF] visual {}: not baked differs from vanilla by {}%, from baked by {}%", view[0],
                        String.format(Locale.ROOT, "%.3f", differingPercent(vanilla, unbaked)), String.format(Locale.ROOT, "%.3f", differingPercent(fast, unbaked)));
                setBake(context, true);
                context.waitTicks(30);
                continue;
            }
            check(diff < limit, String.format(Locale.ROOT, "visual %s: %.3f%% pixels differ from vanilla (limit %.2f%%)", view[0], diff, limit));
        }
        int expected = itemFramesBaked() ? 12 + itemSamples().length - 2 : 12;
        int baked = 0;
        for (int i = 0; i < 20 && baked < expected; i++) {
            context.waitTicks(10);
            baked = context.computeOnClient(client -> BakedFrames.INSTANCE.bakedCount());
        }
        check(baked >= expected, "visual scene frames baked: " + baked + " (not baked since start: " + context.computeOnClient(client -> BakedFrames.INSTANCE.debugReasons()) + ")");
    }

    private void resourcePackChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        int sceneZ = baseZ + 60;
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 165 10", baseX + 1.5, baseY + 1, sceneZ + 6.5));
        setInvisibleFramesPack(context, true);
        check(!context.computeOnClient(client -> OptimizedFrameMesh.hasVanillaModel(false)), "resource pack replacing item_frame_map detected");
        check(context.computeOnClient(client -> OptimizedFrameMesh.hasVanillaModel(true)), "glow_item_frame_map left to the reduced model");
        setFif(context, false);
        context.waitTicks(30);
        Path vanilla = context.takeScreenshot("pack-invisible-vanilla");
        setFif(context, true);
        context.waitTicks(30);
        Path fast = context.takeScreenshot("pack-invisible-fast");
        int baked = context.computeOnClient(client -> BakedFrames.INSTANCE.bakedCount());
        check(baked >= 10, "frames baked with the resource pack: " + baked + " (not baked since start: "
                + context.computeOnClient(client -> BakedFrames.INSTANCE.debugReasons()) + ")");
        double diff = differingPercent(vanilla, fast);
        setInvisibleFramesPack(context, false);
        check(context.computeOnClient(client -> OptimizedFrameMesh.hasVanillaModel(false)), "reduced model used again without the resource pack");
        check(diff < MAX_VISUAL_DIFF, String.format(Locale.ROOT, "invisible frames resource pack: %.3f%% pixels differ from vanilla", diff));
    }

    private static void setInvisibleFramesPack(ClientGameTestContext context, boolean enabled) {
        CompletableFuture<Void> reload = context.computeOnClient(client -> {
            PackRepository repository = client.getResourcePackRepository();
            String id = "file/" + INVISIBLE_FRAMES_PACK;
            if (enabled) {
                writeInvisibleFramesPack(client.getResourcePackDirectory().resolve(INVISIBLE_FRAMES_PACK));
                repository.reload();
                if (!repository.getAvailableIds().contains(id)) {
                    throw new AssertionError("test resource pack not found, available: " + repository.getAvailableIds());
                }
            }
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            selected.remove(id);
            if (enabled) {
                selected.add(id);
            }
            repository.setSelected(selected);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone(), 1200);
        context.waitTicks(20);
    }

    private static void writeInvisibleFramesPack(Path pack) {
        //#if MC >= 1.21.9
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        String meta = "{\"pack\": {\"description\": \"\", \"min_format\": " + format + ", \"max_format\": " + format + "}}";
        //#else
        //#if MC >= 1.21.6
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        //#else
        int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
        //#endif
        String meta = "{\"pack\": {\"description\": \"\", \"pack_format\": " + format + "}}";
        //#endif
        try {
            Path model = pack.resolve("assets/minecraft/models/block/item_frame_map.json");
            Files.createDirectories(model.getParent());
            Files.writeString(pack.resolve("pack.mcmeta"), meta);
            Files.writeString(model, "{\"textures\": {\"particle\": \"block/birch_planks\"}, \"elements\": []}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void imageFrameClientChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        int sceneZ = baseZ + 90;
        List<Integer> ids = new ArrayList<>();
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            Random random = new Random(3);
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    MapId id = randomMap(level, random, false);
                    FakeImageFrameServer.HD_IMAGES.put(id.id(), png(col, 2 - row, 3, 3, 256));
                    ids.add(id.id());
                    BlockPos pos = new BlockPos(baseX - 1 + col, baseY + 1 + row, sceneZ);
                    level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                    spawnFrame(level, pos, Direction.SOUTH, id, false);
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -10", baseX + 0.5, baseY + 1, sceneZ + 4.5));
        setFif(context, true);
        context.waitFor(client -> ids.stream().allMatch(FifGameTest::hdShown), 1200);
        context.waitTicks(40);
        check(FakeImageFrameServer.REQUESTS.get() >= ids.size(), "ImageFrame Client loaded " + ids.size() + " HD maps");

        Path fast = context.takeScreenshot("ifc-hd-fast");
        int downloaded = ids.stream().mapToInt(FakeImageFrameServer::requests).sum();
        setFif(context, false);
        context.waitFor(client -> ids.stream().allMatch(id -> ifcTexture(id) != null), 600);
        context.waitTicks(30);
        if (IfcLink.isLinked()) {
            int again = ids.stream().mapToInt(FakeImageFrameServer::requests).sum() - downloaded;
            check(again == 0, "ImageFrame Client draws the cached HD images again without downloading them, " + again + " download(s)");
        }
        Path vanilla = context.takeScreenshot("ifc-hd-vanilla");
        double diff = differingPercent(vanilla, fast);

        setImageFrameClientHd(context, false);
        context.waitTicks(30);
        Path noHd = context.takeScreenshot("ifc-no-hd");
        setImageFrameClientHd(context, true);
        setFif(context, true);
        if (diff >= MAX_VISUAL_DIFF && FabricLoader.getInstance().isModLoaded("immediatelyfast") && differingPercent(vanilla, noHd) < MAX_VISUAL_DIFF) {
            String note = String.format(Locale.ROOT, "ImageFrame Client alone shows no HD image here: the map atlas of ImmediatelyFast replaces them (%.3f%% pixels differ from Fast ImageFrames)", diff);
            LOGGER.info("[FIF] {}", note);
            results.add(note);
        } else {
            check(diff < MAX_VISUAL_DIFF, String.format(Locale.ROOT, "HD maps of ImageFrame Client: %.3f%% pixels differ without Fast ImageFrames", diff));
        }
        double hdDiff = differingPercent(noHd, fast);
        check(hdDiff > 2.0, String.format(Locale.ROOT, "HD images really shown with Fast ImageFrames (%.1f%% pixels differ from the vanilla colors)", hdDiff));

        if (IfcLink.isLinked()) {
            hdLodChecks(context, world, ids, sceneZ);
        } else {
            results.add("ImageFrame Client draws its HD images itself, LOD checks skipped");
        }
    }

    private void hdLodChecks(ClientGameTestContext context, TestSingleplayerContext world, List<Integer> ids, int sceneZ) {
        int center = ids.get(4);
        int requests = ids.stream().mapToInt(FakeImageFrameServer::requests).sum();
        context.waitFor(client -> ids.stream().allMatch(HdMaps.INSTANCE::hasSource), 600);
        check(context.computeOnClient(client -> ids.stream().allMatch(id -> ifcTexture(id) == null)), "ImageFrame Client keeps no texture when Fast ImageFrames draws the HD maps");

        context.waitFor(client -> ids.stream().allMatch(HdMaps.INSTANCE::isInAtlas), 600);
        check(true, "HD maps at 4.5 blocks drawn from the map atlas");

        standBefore(world, sceneZ, 1.3);
        context.waitFor(client -> HdMaps.INSTANCE.getTextureSize(center) == 256 && !HdMaps.INSTANCE.isInAtlas(center), 400);
        check(true, "close HD map gets its own 256x256 texture");
        context.waitTicks(20);
        Path near = context.takeScreenshot("ifc-lod-near");
        setHdLod(context, false);
        context.waitFor(client -> ifcTexture(center) != null, 400);
        context.waitTicks(20);
        Path nearFull = context.takeScreenshot("ifc-lod-near-ifc");
        setHdLod(context, true);
        double nearDiff = differingPercent(nearFull, near);
        check(nearDiff < MAX_VISUAL_DIFF, String.format(Locale.ROOT, "close HD map: %.3f%% pixels differ from ImageFrame Client alone", nearDiff));

        standBefore(world, sceneZ, 40.0);
        context.waitFor(client -> ids.stream().allMatch(HdMaps.INSTANCE::isInAtlas) && HdMaps.INSTANCE.getTextureBytes() == 0, 600);
        check(true, "far HD maps back in the atlas, no HD texture in video memory");

        standBefore(world, sceneZ, 1.3);
        context.waitFor(client -> HdMaps.INSTANCE.getTextureSize(center) == 256, 400);
        int after = ids.stream().mapToInt(FakeImageFrameServer::requests).sum();
        check(after == requests, "LOD changes read the cached png, " + (after - requests) + " downloads");
    }

    private void standBefore(TestSingleplayerContext world, int sceneZ, double distance) {
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f 180 0", baseX + 0.5, baseY + 1, sceneZ + 0.5 + distance));
    }

    private void animatedWallBenchmark(ClientGameTestContext context, TestSingleplayerContext world) {
        int size = 20;
        int images = 7;
        int wallZ = baseZ - 40;
        List<ItemFrame> frames = new ArrayList<>();
        List<MapId[]> tiles = new ArrayList<>();
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            Random random = new Random(9);
            int half = size / 2;
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    MapId[] ids = new MapId[images];
                    for (int i = 0; i < images; i++) {
                        ids[i] = randomMap(level, random, false);
                    }
                    tiles.add(ids);
                    BlockPos pos = new BlockPos(baseX - half + col, baseY + row, wallZ);
                    level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                    frames.add(spawnFrame(level, pos, Direction.SOUTH, ids[0], false));
                }
            }
        });
        int[] tick = {0};
        int[] ticksPerImage = {20};
        FakeImageFrameServer.ANIMATION = server -> {
            tick[0]++;
            if (tick[0] % ticksPerImage[0] == 0) {
                int image = tick[0] / ticksPerImage[0] % images;
                for (int i = 0; i < frames.size(); i++) {
                    frames.get(i).setItem(mapItem(tiles.get(i)[image]), false);
                }
            }
        };
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -15", baseX + 0.5, baseY + 3, wallZ + 18.0));
        context.waitTicks(images * 20 + 40);
        world.getServer().runOnServer(server -> ticksPerImage[0] = 1);
        waitForChunks(world);
        int missing = context.computeOnClient(client -> {
            int count = 0;
            for (MapId[] ids : tiles) {
                for (MapId id : ids) {
                    if (client.level.getMapData(id) == null) {
                        count++;
                    }
                }
            }
            return count;
        });
        check(missing == 0, "all " + tiles.size() * images + " animation maps received, " + missing + " missing");

        setFif(context, false);
        results.add(measure(context, "animated 20x20 wall, 7 images | vanilla"));
        setFif(context, true);
        setBake(context, false);
        results.add(measure(context, "animated 20x20 wall, 7 images | Fast ImageFrames, not baked"));
        setBake(context, true);
        results.add(measure(context, "animated 20x20 wall, 7 images | Fast ImageFrames"));
        if (System.getenv("FIF_GPU_AB") != null) {
            gpuRounds(context, "animated 20x20 wall, 7 images");
            context.runOnClient(client -> BakedFrames.setGpu(true));
        }
        int baked = context.computeOnClient(client -> BakedFrames.INSTANCE.bakedCount());
        String reasons = context.computeOnClient(client -> BakedFrames.INSTANCE.debugReasons());
        check(baked >= size * size, "animated wall stays baked while it plays: " + baked + " frames (not baked since start: " + reasons + ")");

        world.getServer().runOnServer(server -> ticksPerImage[0] = Integer.MAX_VALUE);
        context.waitTicks(20);
        Path fast = context.takeScreenshot("animated-wall-fast");
        setFif(context, false);
        context.waitTicks(20);
        Path vanilla = context.takeScreenshot("animated-wall-vanilla");
        setFif(context, true);
        double diff = differingPercent(vanilla, fast);
        check(diff < MAX_VISUAL_DIFF, String.format(Locale.ROOT, "animated wall after the swaps: %.3f%% pixels differ from vanilla", diff));

        FakeImageFrameServer.ANIMATION = null;
        world.getServer().runOnServer(server -> frames.forEach(ItemFrame::discard));
        context.waitTicks(20);
    }

    private static ItemStack mapItem(MapId id) {
        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        stack.set(DataComponents.MAP_ID, id);
        return stack;
    }

    private void hdWallBenchmark(ClientGameTestContext context, TestSingleplayerContext world) {
        int wallZ = baseZ - 4;
        List<Integer> ids = new ArrayList<>();
        context.runOnClient(client -> FifConfig.hdMaxConcurrentRequests = 64);
        world.getServer().runOnServer(server -> {
            wallFrames.forEach(ItemFrame::discard);
            wallFrames.clear();
            ServerLevel level = server.overworld();
            int half = WALL / 2;
            Random random = new Random(5);
            for (int row = 0; row < WALL; row++) {
                for (int col = 0; col < WALL; col++) {
                    MapId id = randomMap(level, random, false);
                    FakeImageFrameServer.HD_IMAGES.put(id.id(), png(col, row, WALL, WALL, 300));
                    ids.add(id.id());
                    wallFrames.add(spawnFrame(level, new BlockPos(baseX - half + col, baseY + WALL - 1 - row, wallZ), Direction.SOUTH, id, false));
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -25", baseX + 0.5, baseY, wallZ + VIEW_DISTANCE));
        setFif(context, true);
        context.waitFor(client -> ids.stream().allMatch(HdMaps.INSTANCE::isInAtlas), 2400);
        context.waitTicks(40);
        results.add(measure(context, "1600 HD maps | ImageFrame Client + Fast ImageFrames"));
        context.takeScreenshot("bench-hd-wall");

        setFif(context, false);
        context.waitFor(client -> ids.stream().allMatch(id -> ifcTexture(id) != null), 2400);
        results.add(measure(context, "1600 HD maps | ImageFrame Client alone"));
        setFif(context, true);
    }

    private void benchmark(ClientGameTestContext context, TestSingleplayerContext world) {
        int wallZ = baseZ - 4;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            int half = WALL / 2;
            for (int row = 0; row < WALL; row++) {
                for (int col = 0; col < WALL; col++) {
                    level.setBlockAndUpdate(new BlockPos(baseX - half + col, baseY + row, wallZ - 1), Blocks.STONE.defaultBlockState());
                }
            }
            Random random = new Random(1);
            for (int row = 0; row < WALL; row++) {
                for (int col = 0; col < WALL; col++) {
                    wallFrames.add(spawnFrame(level, new BlockPos(baseX - half + col, baseY + row, wallZ), Direction.SOUTH, randomMap(level, random, false, true), false));
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -25", baseX + 0.5, baseY, wallZ + VIEW_DISTANCE));
        context.waitTicks(40);
        if (BakedFrames.supported()) {
            int bakedSoon = context.computeOnClient(client -> BakedFrames.INSTANCE.bakedCount());
            check(bakedSoon >= WALL * WALL, "new wall baked 2 s after it appeared: " + bakedSoon + " frames");
        }
        context.waitTicks(60);
        waitForChunks(world);

        setFif(context, false);
        results.add(measure(context, "1600 maps, visible frames   | vanilla"));
        setFif(context, true);
        if (BakedFrames.supported()) {
            setBake(context, false);
            results.add(measure(context, "1600 maps, visible frames   | Fast ImageFrames, not baked"));
            setBake(context, true);
            context.waitTicks(20);
            int marked = context.computeOnClient(client -> {
                int count = 0;
                for (ItemFrame frame : wallFrames) {
                    MapId id = frame.getItem().get(DataComponents.MAP_ID);
                    MapItemSavedData data = id == null ? null : client.level.getMapData(id);
                    if (data != null && data.getDecorations().iterator().hasNext()) {
                        count++;
                    }
                }
                return count;
            });
            check(marked >= WALL * WALL / 2, marked + " wall maps carry a frame marker, like on servers");
            int baked = context.computeOnClient(client -> BakedFrames.INSTANCE.bakedCount());
            String reasons = context.computeOnClient(client -> BakedFrames.INSTANCE.debugReasons());
            LOGGER.info("[FIF] not baked since start: {}", reasons);
            check(baked >= WALL * WALL, "map wall baked: " + baked + " frames (not baked since start: " + reasons + ")");
            results.add("baked vertices per texture: " + context.computeOnClient(client -> BakedFrames.INSTANCE.describeLayers()));
        }
        results.add(measure(context, "1600 maps, visible frames   | Fast ImageFrames"));
        context.takeScreenshot("bench-wall");

        world.getServer().runOnServer(server -> wallFrames.forEach(frame -> frame.setInvisible(true)));
        context.waitTicks(40);
        setFif(context, false);
        results.add(measure(context, "1600 maps, invisible frames | vanilla"));
        setFif(context, true);
        results.add(measure(context, "1600 maps, invisible frames | Fast ImageFrames"));

        world.getServer().runOnServer(server -> {
            wallFrames.forEach(ItemFrame::discard);
            wallFrames.clear();
        });
        context.waitTicks(40);
        results.add(measure(context, "same view, no frames         | Fast ImageFrames"));
    }

    private void gpuAb(ClientGameTestContext context, TestSingleplayerContext world) {
        int wallZ = baseZ - 4;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            int half = WALL / 2;
            Random random = new Random(1);
            for (int row = 0; row < WALL; row++) {
                for (int col = 0; col < WALL; col++) {
                    level.setBlockAndUpdate(new BlockPos(baseX - half + col, baseY + row, wallZ - 1), Blocks.STONE.defaultBlockState());
                    wallFrames.add(spawnFrame(level, new BlockPos(baseX - half + col, baseY + row, wallZ), Direction.SOUTH, randomMap(level, random, false, true), false));
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -25", baseX + 0.5, baseY, wallZ + VIEW_DISTANCE));
        context.waitTicks(100);
        waitForChunks(world);
        gpuRounds(context, "1600 maps, visible frames  ");
        world.getServer().runOnServer(server -> wallFrames.forEach(frame -> frame.setInvisible(true)));
        context.waitTicks(40);
        gpuRounds(context, "1600 maps, invisible frames");
        world.getServer().runOnServer(server -> {
            wallFrames.forEach(ItemFrame::discard);
            wallFrames.clear();
        });
        context.waitTicks(40);
        int itemZ = baseZ - 40;
        int size = 20;
        List<ItemFrame> frames = new ArrayList<>();
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            ItemStack[] items = itemSamples();
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    BlockPos pos = new BlockPos(baseX - size / 2 + col, baseY + row, itemZ);
                    level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                    frames.add(spawnItemFrame(level, pos, Direction.SOUTH, items[(row * size + col) % items.length], false, (row + col) % 4));
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -15", baseX + 0.5, baseY + 2, itemZ + 18.5));
        context.waitTicks(60);
        waitForChunks(world);
        gpuRounds(context, "400 item frames with items  ");
        context.runOnClient(client -> BakedFrames.setGpu(true));
        world.getServer().runOnServer(server -> frames.forEach(ItemFrame::discard));
    }

    private void gpuRounds(ClientGameTestContext context, String scene) {
        int rounds = Integer.parseInt(System.getenv().getOrDefault("FIF_ROUNDS", "3"));
        for (int round = 0; round < rounds; round++) {
            for (int step = 0; step < 2; step++) {
                boolean gpu = (round + step) % 2 == 0;
                context.runOnClient(client -> BakedFrames.setGpu(gpu));
                results.add(measure(context, scene + " | " + (gpu ? "GPU" : "CPU")));
            }
        }
    }

    private void lightChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        int wallZ = baseZ - 70;
        int size = 10;
        BlockPos lamp = new BlockPos(baseX, baseY - 1, wallZ + 1);
        AABB area = new AABB(baseX - size, baseY - 2, wallZ - 2, baseX + size, baseY + size + 2, wallZ + 2);
        List<ItemFrame> frames = new ArrayList<>();
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : itemSamples()) {
                if (!item.is(Items.COMPASS) && !item.is(Items.CLOCK)) {
                    items.add(item);
                }
            }
            Random random = new Random(11);
            level.setBlockAndUpdate(lamp, Blocks.STONE.defaultBlockState());
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    BlockPos pos = new BlockPos(baseX - size / 2 + col, baseY + row, wallZ);
                    level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                    boolean glow = col % 2 == 1;
                    ItemStack stack = row % 2 == 0 ? mapItem(randomMap(level, random, false)) : items.get((row * size + col) % items.size());
                    frames.add(spawnItemFrame(level, pos, Direction.SOUTH, stack, glow, row % 2 == 0 ? 0 : col % 4));
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 10", baseX + 0.5, baseY + 6, wallZ + 12.5));
        context.waitTicks(80);
        waitForChunks(world);
        context.waitTicks(60);
        int expected = itemFramesBaked() ? size * size : size * size / 2;
        int[] counts = bakedIn(context, area);
        check(counts[0] == expected / 2 && counts[1] == expected / 2,
                "normal and glow item frames both baked: " + counts[0] + " normal, " + counts[1] + " glow of " + expected);
        int bakes = context.computeOnClient(client -> BakedFrames.INSTANCE.sectionBakes());
        int relit = context.computeOnClient(client -> BakedFrames.INSTANCE.relitCount());
        for (int i = 0; i < 7; i++) {
            setBlock(world, lamp, i % 2 == 0 ? Blocks.GLOWSTONE : Blocks.STONE);
            context.waitTicks(10);
        }
        context.waitTicks(10);
        int bakesAfter = context.computeOnClient(client -> BakedFrames.INSTANCE.sectionBakes());
        int relitAfter = context.computeOnClient(client -> BakedFrames.INSTANCE.relitCount());
        counts = bakedIn(context, area);
        check(counts[0] + counts[1] == expected && relitAfter > relit && bakesAfter == bakes, "7 light changes applied to the baked frames: "
                + (relitAfter - relit) + " frames relit, " + (bakesAfter - bakes) + " sections baked again, " + (counts[0] + counts[1]) + " still baked");
        lightCompare(context, "light-on-after-bake");

        context.waitTicks(60);
        setBlock(world, lamp, Blocks.STONE);
        context.waitTicks(20);
        lightCompare(context, "light-off-after-bake");

        world.getServer().runOnServer(server -> {
            frames.forEach(ItemFrame::discard);
            server.overworld().setBlockAndUpdate(lamp, Blocks.STONE.defaultBlockState());
        });
        context.waitTicks(20);
    }

    private void lightCompare(ClientGameTestContext context, String name) {
        Path fast = context.takeScreenshot(name + "-fast");
        setFif(context, false);
        context.waitTicks(20);
        Path vanilla = context.takeScreenshot(name + "-vanilla");
        setFif(context, true);
        double diff = differingPercent(vanilla, fast);
        check(diff < MAX_VISUAL_DIFF, String.format(Locale.ROOT, "%s: %.3f%% pixels differ from vanilla", name, diff));
    }

    private static int[] bakedIn(ClientGameTestContext context, AABB area) {
        return context.computeOnClient(client -> {
            int[] counts = new int[2];
            for (ItemFrame frame : client.level.getEntitiesOfClass(ItemFrame.class, area)) {
                if (((BakeableFrame) frame).fif$isBaked()) {
                    counts[frame instanceof GlowItemFrame ? 1 : 0]++;
                }
            }
            return counts;
        });
    }

    private static void setBlock(TestSingleplayerContext world, BlockPos pos, net.minecraft.world.level.block.Block block) {
        world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(pos, block.defaultBlockState()));
    }

    private void occlusionChecks(ClientGameTestContext context, TestSingleplayerContext world) {
        int wallZ = baseZ - 4;
        int coverZ = wallZ + 8;
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            wallFrames.forEach(ItemFrame::discard);
            wallFrames.clear();
            int half = WALL / 2;
            Random random = new Random(1);
            for (int row = 0; row < WALL; row++) {
                for (int col = 0; col < WALL; col++) {
                    level.setBlockAndUpdate(new BlockPos(baseX - half + col, baseY + row, wallZ - 1), Blocks.STONE.defaultBlockState());
                    wallFrames.add(spawnFrame(level, new BlockPos(baseX - half + col, baseY + row, wallZ), Direction.SOUTH, randomMap(level, random, false, true), false));
                }
            }
            for (int x = -45; x <= 45; x++) {
                for (int y = -1; y <= 55; y++) {
                    level.setBlockAndUpdate(new BlockPos(baseX + x, baseY + y, coverZ), Blocks.STONE.defaultBlockState());
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -25", baseX + 0.5, baseY, wallZ + VIEW_DISTANCE));
        context.waitTicks(100);
        waitForChunks(world);
        occlusionRounds(context, "1600 maps behind a wall, baked    ");
        setOcclusion(context, true);
        context.waitTicks(20);
        List<Integer> windows = new ArrayList<>();
        for (int i = 0; i < 5 && (windows.isEmpty() || windows.get(windows.size() - 1) != 0); i++) {
            int before = context.computeOnClient(client -> BakedFrames.INSTANCE.gpuDraws());
            context.waitTicks(40);
            windows.add(context.computeOnClient(client -> BakedFrames.INSTANCE.gpuDraws()) - before);
        }
        check(windows.get(windows.size() - 1) == 0, "baked wall hidden behind blocks is not drawn: GPU draws per 2 s " + windows);
        setBake(context, false);
        occlusionRounds(context, "1600 maps behind a wall, not baked");
        setBake(context, true);

        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            for (int window = 0; window < 4; window++) {
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 4; y++) {
                        level.setBlockAndUpdate(new BlockPos(baseX - 14 + window * 8 + x, baseY + 6 + window * 3 + y, coverZ), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        });
        context.waitTicks(60);
        for (int bake = 1; bake >= 0; bake--) {
            setBake(context, bake == 1);
            setOcclusion(context, false);
            context.waitTicks(40);
            Path off = context.takeScreenshot("occlusion-windows-off-" + bake);
            setOcclusion(context, true);
            context.waitTicks(40);
            Path on = context.takeScreenshot("occlusion-windows-on-" + bake);
            double diff = differingPercent(off, on);
            check(diff < 0.05, String.format(Locale.ROOT, "wall with windows, %s: %.3f%% pixels differ with occlusion", bake == 1 ? "baked" : "not baked", diff));
        }
        setBake(context, true);
        results.add(measure(context, "1600 maps behind windows | occlusion on"));

        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            wallFrames.forEach(ItemFrame::discard);
            wallFrames.clear();
            for (int x = -45; x <= 45; x++) {
                for (int y = -1; y <= 55; y++) {
                    level.setBlockAndUpdate(new BlockPos(baseX + x, baseY + y, coverZ), Blocks.AIR.defaultBlockState());
                }
            }
        });
        context.waitTicks(40);
    }

    private void occlusionRounds(ClientGameTestContext context, String scene) {
        int rounds = Integer.parseInt(System.getenv().getOrDefault("FIF_ROUNDS", "3"));
        for (int round = 0; round < rounds; round++) {
            for (int step = 0; step < 2; step++) {
                boolean on = (round + step) % 2 == 0;
                setOcclusion(context, on);
                results.add(measure(context, scene + " | occlusion " + (on ? "on " : "off")));
            }
        }
    }

    private static void setOcclusion(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(client -> FifConfig.occlusionCulling = enabled);
        context.waitTicks(5);
    }

    private static void waitForChunks(TestSingleplayerContext world) {
        //#if MC >= 26.2
        world.getConnection().waitForChunksRender(1200);
        //#elif MC >= 26.1
        world.getClientLevel().waitForChunksRender(1200);
        //#else
        world.getClientWorld().waitForChunksRender(1200);
        //#endif
    }

    private static Object ifcTexture(int mapId) {
        try {
            Class<?> type = Class.forName("com.loohp.imageframe.ImageFrameClient");
            Object mod = type.getField("MOD").get(null);
            return type.getMethod("getOrRequestLoadedHdMap", int.class).invoke(mod, mapId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hdShown(int mapId) {
        return ifcTexture(mapId) != null || HdMaps.INSTANCE.getTextureSize(mapId) > 0;
    }

    private static void setImageFrameClientHd(ClientGameTestContext context, boolean enabled) {
        setImageFrameClientOption(context, "useNativeResMapImages", enabled);
    }

    private static void setImageFrameClientOption(ClientGameTestContext context, String option, Object value) {
        context.runOnClient(client -> {
            try {
                Class.forName("com.loohp.imageframe.configuration.Configuration").getField(option).set(null, value);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void setFif(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(client -> {
            FifConfig.enabled = enabled;
            MapAtlas.INSTANCE.onConfigChanged();
            HdMaps.INSTANCE.onConfigChanged();
        });
        context.waitTicks(5);
    }

    private static void setBake(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(client -> FifConfig.bakeFrames = enabled);
        context.waitTicks(5);
    }

    private static void setHdLod(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(client -> {
            FifConfig.hdLod = enabled;
            HdMaps.INSTANCE.onConfigChanged();
        });
        context.waitTicks(5);
    }

    private String measure(ClientGameTestContext context, String name) {
        context.waitTicks(120);
        int samples = 6;
        int total = 0;
        for (int i = 0; i < samples; i++) {
            context.waitTicks(20);
            total += context.computeOnClient(Minecraft::getFps);
        }
        double avg = total / (double) samples;
        String line = String.format(Locale.ROOT, "%-46s %7.1f FPS (%6.2f ms/frame)", name, avg, 1000.0 / avg);
        if (BakedFrames.supported()) {
            line += " baked frames: " + context.computeOnClient(client -> BakedFrames.INSTANCE.bakedCount()) + ", GPU draws: "
                    + context.computeOnClient(client -> BakedFrames.INSTANCE.gpuDraws());
        }
        LOGGER.info("[FIF] {}", line);
        return line;
    }

    static boolean enableShaderPack(ClientGameTestContext context, Path pack) {
        boolean[] ok = new boolean[1];
        context.runOnClient(client -> {
            try {
                Path folder = client.gameDirectory.toPath().resolve("shaderpacks");
                Files.createDirectories(folder);
                Path target = folder.resolve(pack.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.copy(pack, target);
                }
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object config = iris.getMethod("getIrisConfig").invoke(null);
                config.getClass().getMethod("setShaderPackName", String.class).invoke(config, target.getFileName().toString());
                config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, true);
                config.getClass().getMethod("save").invoke(config);
                iris.getMethod("reload").invoke(null);
                ok[0] = (boolean) iris.getMethod("isPackInUseQuick").invoke(null);
            } catch (ReflectiveOperationException | IOException e) {
                LOGGER.warn("Could not enable the shader pack", e);
            }
        });
        context.waitTicks(40);
        return ok[0];
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        LOGGER.info("[FIF] OK {}", message);
        results.add("OK " + message);
    }

    private static double differingPercent(Path a, Path b) {
        try {
            BufferedImage imageA = ImageIO.read(a.toFile());
            BufferedImage imageB = ImageIO.read(b.toFile());
            int differing = 0;
            for (int x = 0; x < imageA.getWidth(); x++) {
                for (int y = 0; y < imageA.getHeight(); y++) {
                    int pa = imageA.getRGB(x, y);
                    int pb = imageB.getRGB(x, y);
                    int diff = Math.max(Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF)),
                            Math.max(Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF)), Math.abs((pa & 0xFF) - (pb & 0xFF))));
                    if (diff > 16) {
                        differing++;
                    }
                }
            }
            return differing * 100.0 / (imageA.getWidth() * imageA.getHeight());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private MapId randomMap(ServerLevel level, Random random, boolean transparentHole) {
        return randomMap(level, random, transparentHole, false);
    }

    private MapId randomMap(ServerLevel level, Random random, boolean transparentHole, boolean tracked) {
        MapId id = level.getFreeMapId();
        MapItemSavedData data = tracked
                ? MapItemSavedData.createFresh(baseX, baseZ, (byte) 0, true, false, Level.OVERWORLD)
                : MapItemSavedData.createForClient((byte) 0, true, Level.OVERWORLD);
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                boolean hole = transparentHole && x > 32 && x < 96 && z > 32 && z < 96;
                data.colors[x + z * 128] = hole ? 0 : (byte) (((x / 16 + z / 16 + mapCounter) % 60 + 1) * 4 + random.nextInt(3));
            }
        }
        mapCounter++;
        level.setMapData(id, data);
        return id;
    }

    private static boolean itemFramesBaked() {
        //#if MC >= 26.1
        return false;
        //#else
        return true;
        //#endif
    }

    private static ItemStack[] itemSamples() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        //#if MC >= 26.2
        return new ItemStack[]{sword, new ItemStack(Items.GRASS_BLOCK), new ItemStack(Items.SHIELD), new ItemStack(Items.BANNER.red()), new ItemStack(Items.CHEST),
        //#else
        return new ItemStack[]{sword, new ItemStack(Items.GRASS_BLOCK), new ItemStack(Items.SHIELD), new ItemStack(Items.RED_BANNER), new ItemStack(Items.CHEST),
        //#endif
                new ItemStack(Items.COMPASS), new ItemStack(Items.CLOCK), new ItemStack(Items.SKELETON_SKULL), new ItemStack(Items.TORCH), ItemStack.EMPTY,
                new ItemStack(Items.GLASS), new ItemStack(Items.APPLE)};
    }

    private static ItemFrame spawnItemFrame(ServerLevel level, BlockPos pos, Direction facing, ItemStack stack, boolean glow, int rotation) {
        ItemFrame frame = glow ? new net.minecraft.world.entity.decoration.GlowItemFrame(level, pos, facing) : new ItemFrame(level, pos, facing);
        frame.setItem(stack.copy(), false);
        frame.setRotation(rotation);
        level.addFreshEntity(frame);
        return frame;
    }

    private void itemWallBenchmark(ClientGameTestContext context, TestSingleplayerContext world) {
        int wallZ = baseZ - 40;
        int size = 20;
        List<ItemFrame> frames = new ArrayList<>();
        world.getServer().runOnServer(server -> {
            ServerLevel level = server.overworld();
            ItemStack[] items = itemSamples();
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    BlockPos pos = new BlockPos(baseX - size / 2 + col, baseY + row, wallZ);
                    level.setBlockAndUpdate(pos.north(), Blocks.STONE.defaultBlockState());
                    frames.add(spawnItemFrame(level, pos, Direction.SOUTH, items[(row * size + col) % items.length], false, (row + col) % 4));
                }
            }
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 -15", baseX + 0.5, baseY + 2, wallZ + 18.5));
        context.waitTicks(60);
        waitForChunks(world);
        int rounds = Integer.parseInt(System.getenv().getOrDefault("FIF_ROUNDS", "3"));
        for (int round = 0; round < rounds; round++) {
            boolean reverse = round % 2 == 1;
            for (int step = 0; step < 3; step++) {
                int mode = reverse ? 2 - step : step;
                setFif(context, mode != 0);
                setBake(context, mode == 2);
                String label = mode == 0 ? "vanilla" : mode == 1 ? "Fast ImageFrames, not baked" : "Fast ImageFrames";
                results.add(measure(context, "400 item frames with items    | " + label));
            }
        }
        setBake(context, true);
        world.getServer().runOnServer(server -> frames.forEach(ItemFrame::discard));
        context.waitTicks(20);
    }

    private static ItemFrame spawnFrame(ServerLevel level, BlockPos pos, Direction facing, MapId id, boolean invisible) {
        ItemFrame frame = new ItemFrame(level, pos, facing);
        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        stack.set(DataComponents.MAP_ID, id);
        frame.setItem(stack, false);
        frame.setInvisible(invisible);
        level.addFreshEntity(frame);
        return frame;
    }

    private static byte[] noisePng(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(size);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, 0xFF000000 | random.nextInt(0xFFFFFF));
            }
        }
        return encode(image);
    }

    private static byte[] png(int col, int row, int cols, int rows, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int r = (col * size + x) * 255 / (cols * size);
                int g = ((rows - 1 - row) * size + (size - 1 - y)) * 255 / (rows * size);
                image.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | 0x60);
            }
        }
        return encode(image);
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
