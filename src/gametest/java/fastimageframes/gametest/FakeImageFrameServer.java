package fastimageframes.gametest;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class FakeImageFrameServer implements ModInitializer {

    private static final String PAYLOADS = "com.loohp.imageframe.payload.";
    private static final int CHUNK_SIZE = 32000;

    public static final Map<Integer, byte[]> HD_IMAGES = new ConcurrentHashMap<>();
    public static final Map<Integer, int[]> IMAGE_MAPS = new ConcurrentHashMap<>();
    public static final AtomicInteger REQUESTS = new AtomicInteger();
    public static final AtomicInteger ACKNOWLEDGED = new AtomicInteger();
    public static final Map<Integer, AtomicInteger> DENY_REMAINING = new ConcurrentHashMap<>();
    public static volatile Consumer<MinecraftServer> ANIMATION;
    private static final Map<Integer, AtomicInteger> REQUESTS_PER_MAP = new ConcurrentHashMap<>();

    private static final Map<ServerPlayer, Integer> pendingAcks = new ConcurrentHashMap<>();
    private static Constructor<?> ackConstructor;
    private static Constructor<?> responseConstructor;
    private static Constructor<?> multipartConstructor;
    private static Constructor<?> updatedConstructor;
    private static Constructor<?> detailsConstructor;

    public static int requests(int mapId) {
        AtomicInteger count = REQUESTS_PER_MAP.get(mapId);
        return count == null ? 0 : count.get();
    }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("imageframeclient");
    }

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Consumer<MinecraftServer> animation = ANIMATION;
            if (animation != null) {
                animation.accept(server);
            }
        });
        if (!available()) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTING.register(server -> registerReceivers());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> pendingAcks.put(handler.player, 40));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> pendingAcks.remove(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> pendingAcks.replaceAll((player, ticks) -> {
            if (ticks == 0) {
                try {
                    ServerPlayNetworking.send(player, (CustomPacketPayload) ackConstructor.newInstance(ThreadLocalRandom.current().nextLong()));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            return ticks - 1;
        }));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerReceivers() {
        if (ackConstructor != null) {
            return;
        }
        try {
            ackConstructor = Class.forName(PAYLOADS + "ClientboundAcknowledgement").getConstructor(long.class);
            responseConstructor = Class.forName(PAYLOADS + "ClientboundHdImageResponse").getConstructor(int.class, boolean.class, byte[].class, Optional.class);
            multipartConstructor = Class.forName(PAYLOADS + "ClientboundHdImageMultipartResponse").getConstructor(int.class, int.class, int.class, byte[].class, boolean.class);
            updatedConstructor = Class.forName(PAYLOADS + "ClientboundImageUpdatedSignal").getConstructor(IntSet.class, IntSet.class);
            CustomPacketPayload.Type ackType = (CustomPacketPayload.Type) Class.forName(PAYLOADS + "ServerboundAcknowledgement").getField("ID").get(null);
            detailsConstructor = Class.forName(PAYLOADS + "ClientboundImageMapDetailsResponse").getConstructor(int.class, int.class, int.class, IntList.class);
            CustomPacketPayload.Type detailsType = (CustomPacketPayload.Type) Class.forName(PAYLOADS + "ServerboundImageMapDetailsRequest").getField("ID").get(null);
            ServerPlayNetworking.registerGlobalReceiver(detailsType, (ServerPlayNetworking.PlayPayloadHandler) (payload, context) -> {
                try {
                    int index = (int) payload.getClass().getMethod("index").invoke(payload);
                    int[] image = IMAGE_MAPS.get(index);
                    if (image != null) {
                        send(context.player(), detailsConstructor.newInstance(index, image[0], image[1], new IntArrayList(Arrays.copyOfRange(image, 2, image.length))));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
            CustomPacketPayload.Type requestType = (CustomPacketPayload.Type) Class.forName(PAYLOADS + "ServerboundHdImageRequest").getField("ID").get(null);

            ServerPlayNetworking.registerGlobalReceiver(ackType, (ServerPlayNetworking.PlayPayloadHandler) (payload, context) -> {
                pendingAcks.remove(context.player());
                ACKNOWLEDGED.incrementAndGet();
            });
            ServerPlayNetworking.registerGlobalReceiver(requestType, (ServerPlayNetworking.PlayPayloadHandler) (payload, context) -> {
                try {
                    int mapId = (int) payload.getClass().getMethod("mapId").invoke(payload);
                    REQUESTS.incrementAndGet();
                    REQUESTS_PER_MAP.computeIfAbsent(mapId, id -> new AtomicInteger()).incrementAndGet();
                    ServerPlayer player = context.player();
                    AtomicInteger deny = DENY_REMAINING.get(mapId);
                    if (deny != null && deny.getAndDecrement() > 0) {
                        send(player, responseConstructor.newInstance(mapId, false, new byte[0], Optional.empty()));
                        return;
                    }
                    byte[] png = HD_IMAGES.get(mapId);
                    if (png == null || png.length <= CHUNK_SIZE) {
                        send(player, responseConstructor.newInstance(mapId, true, png == null ? new byte[0] : png, Optional.empty()));
                        return;
                    }
                    int multipartId = ThreadLocalRandom.current().nextInt();
                    int parts = (png.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
                    send(player, responseConstructor.newInstance(mapId, true, Arrays.copyOf(png, CHUNK_SIZE), Optional.of(multipartId)));
                    for (int i = 1; i < parts; i++) {
                        byte[] part = Arrays.copyOfRange(png, i * CHUNK_SIZE, Math.min(png.length, (i + 1) * CHUNK_SIZE));
                        send(player, multipartConstructor.newInstance(mapId, multipartId, i, part, i + 1 >= parts));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("ImageFrame Client payloads not found", e);
        }
    }

    public static void sendUpdated(ServerPlayer player, int mapId) {
        IntOpenHashSet mapIds = new IntOpenHashSet();
        mapIds.add(mapId);
        try {
            send(player, updatedConstructor.newInstance(new IntOpenHashSet(), mapIds));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void send(ServerPlayer player, Object payload) {
        ServerPlayNetworking.send(player, (CustomPacketPayload) payload);
    }

}
