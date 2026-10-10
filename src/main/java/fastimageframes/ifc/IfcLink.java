package fastimageframes.ifc;

import fastimageframes.FastImageFrames;
import fastimageframes.hd.HdCache;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class IfcLink {

    private static final String ROOT = "com.loohp.imageframe.";
    private static final String PAYLOADS = ROOT + "payload.";

    private static boolean linked;
    private static Object ifcClient;
    private static Method loadedHdMap;
    private static Field useNativeResMapImages;
    private static Constructor<?> hdRequest;
    private static Constructor<?> responseConstructor;
    private static ClientPlayNetworking.PlayPayloadHandler<CustomPacketPayload> ifcResponseHandler;

    private static Method responseMapId;
    private static Method responseAccepted;
    private static Method responseData;
    private static Method responseMultipart;

    private static Method multipartId;
    private static Method multipartIndex;
    private static Method multipartData;
    private static Method multipartEnd;

    private static Method updatedMapIds;

    private IfcLink() {
    }

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded("imageframeclient")) {
            return;
        }
        try {
            Class<?> client = Class.forName(ROOT + "ImageFrameClient");
            loadedHdMap = client.getMethod("getOrRequestLoadedHdMap", int.class);
            ifcClient = client.getField("MOD").get(null);
            useNativeResMapImages = Class.forName(ROOT + "configuration.Configuration").getField("useNativeResMapImages");
            useNativeResMapImages.getBoolean(null);

            Class<?> request = Class.forName(PAYLOADS + "ServerboundHdImageRequest");
            hdRequest = request.getConstructor(int.class);

            Class<?> response = Class.forName(PAYLOADS + "ClientboundHdImageResponse");
            responseMapId = response.getMethod("mapId");
            responseAccepted = response.getMethod("requestAccepted");
            responseData = response.getMethod("data");
            responseMultipart = response.getMethod("multipart");

            Class<?> multipart = Class.forName(PAYLOADS + "ClientboundHdImageMultipartResponse");
            multipartId = multipart.getMethod("multipart");
            multipartIndex = multipart.getMethod("index");
            multipartData = multipart.getMethod("data");
            multipartEnd = multipart.getMethod("end");

            Class<?> updated = Class.forName(PAYLOADS + "ClientboundImageUpdatedSignal");
            updatedMapIds = updated.getMethod("mapIds");

            Class<?> ack = Class.forName(PAYLOADS + "ClientboundAcknowledgement");

            List<Wrapped> wrapped = new ArrayList<>();
            responseConstructor = response.getConstructor(int.class, boolean.class, byte[].class, Optional.class);
            wrapped.add(new Wrapped(type(response), (original, payload, context) -> {
                boolean active = HdCache.INSTANCE.isActive();
                HdCache.INSTANCE.onHdResponse((int) responseMapId.invoke(payload), (boolean) responseAccepted.invoke(payload),
                        (byte[]) responseData.invoke(payload), castOptional(responseMultipart.invoke(payload)));
                if (!active) {
                    original.receive(payload, context);
                }
            }));
            wrapped.add(new Wrapped(type(multipart), (original, payload, context) -> {
                boolean active = HdCache.INSTANCE.isActive();
                HdCache.INSTANCE.onHdMultipart((int) multipartId.invoke(payload), (int) multipartIndex.invoke(payload),
                        (byte[]) multipartData.invoke(payload), (boolean) multipartEnd.invoke(payload));
                if (!active) {
                    original.receive(payload, context);
                }
            }));
            wrapped.add(new Wrapped(type(updated), (original, payload, context) -> {
                original.receive(payload, context);
                HdCache.INSTANCE.onImageUpdated((IntSet) updatedMapIds.invoke(payload));
            }));
            wrapped.add(new Wrapped(type(ack), (original, payload, context) -> {
                original.receive(payload, context);
                HdCache.INSTANCE.setServerSupported(true);
            }));
            install(wrapped);
            linked = true;
            FastImageFrames.LOGGER.info("ImageFrame Client found: its HD images are downloaded, cached and drawn by Fast ImageFrames");
        } catch (Throwable e) {
            FastImageFrames.LOGGER.warn("ImageFrame Client found but not recognized, it keeps loading and drawing its HD images itself", e);
        }
    }

    public static boolean isLinked() {
        return linked;
    }

    public static Identifier loadedHdTexture(int mapId) {
        if (!linked || !isIfcHdEnabled()) {
            return null;
        }
        try {
            return loadedHdMap.invoke(ifcClient, mapId) instanceof Identifier texture ? texture : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static boolean isIfcHdEnabled() {
        try {
            return useNativeResMapImages.getBoolean(null);
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    public static boolean sendHdRequest(int mapId) {
        try {
            ClientPlayNetworking.send((CustomPacketPayload) hdRequest.newInstance(mapId));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            FastImageFrames.LOGGER.warn("Unable to request the HD image of map {}", mapId, e);
            return false;
        }
    }

    public static void giveToIfc(int mapId, byte[] png) {
        try {
            ifcResponseHandler.receive((CustomPacketPayload) responseConstructor.newInstance(mapId, true, png, Optional.empty()), null);
        } catch (Throwable e) {
            FastImageFrames.LOGGER.warn("Unable to give the cached HD image of map {} to ImageFrame Client", mapId, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static void releaseIfcTextures() {
        try {
            Class<?> client = Class.forName(ROOT + "ImageFrameClient");
            Object mod = client.getField("MOD").get(null);
            Field field = client.getDeclaredField("loadedHdImages");
            field.setAccessible(true);
            Map<Integer, Optional<?>> loaded = (Map<Integer, Optional<?>>) field.get(mod);
            for (Optional<?> id : loaded.values()) {
                if (id.isPresent() && id.get() instanceof Identifier texture) {
                    Minecraft.getInstance().getTextureManager().release(texture);
                }
            }
            loaded.clear();
        } catch (Throwable e) {
            FastImageFrames.LOGGER.debug("Unable to release the HD textures of ImageFrame Client", e);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void receive(ClientPlayNetworking.PlayPayloadHandler<CustomPacketPayload> original, CustomPacketPayload payload,
                     ClientPlayNetworking.Context context) throws Throwable;
    }

    private record Wrapped(CustomPacketPayload.Type<CustomPacketPayload> type, Handler handler) {
    }

    @SuppressWarnings("unchecked")
    private static CustomPacketPayload.Type<CustomPacketPayload> type(Class<?> payload) throws ReflectiveOperationException {
        return (CustomPacketPayload.Type<CustomPacketPayload>) payload.getField("ID").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Integer> castOptional(Object value) {
        return (Optional<Integer>) value;
    }

    @SuppressWarnings("unchecked")
    private static void install(List<Wrapped> wrapped) {
        List<ClientPlayNetworking.PlayPayloadHandler<CustomPacketPayload>> originals = new ArrayList<>();
        for (Wrapped w : wrapped) {
            ClientPlayNetworking.PlayPayloadHandler<?> original = ClientPlayNetworking.unregisterGlobalReceiver(w.type().id());
            originals.add((ClientPlayNetworking.PlayPayloadHandler<CustomPacketPayload>) original);
        }
        if (originals.contains(null)) {
            for (int i = 0; i < wrapped.size(); i++) {
                if (originals.get(i) != null) {
                    ClientPlayNetworking.registerGlobalReceiver(wrapped.get(i).type(), originals.get(i));
                }
            }
            throw new IllegalStateException("ImageFrame Client receivers not registered");
        }
        for (int i = 0; i < wrapped.size(); i++) {
            Wrapped w = wrapped.get(i);
            ClientPlayNetworking.PlayPayloadHandler<CustomPacketPayload> original = originals.get(i);
            if (i == 0) {
                ifcResponseHandler = original;
            }
            ClientPlayNetworking.registerGlobalReceiver(w.type(), (payload, context) -> {
                try {
                    w.handler().receive(original, payload, context);
                } catch (Throwable e) {
                    FastImageFrames.LOGGER.warn("Unable to handle {} from ImageFrame", w.type().id(), e);
                }
            });
        }
    }

}
