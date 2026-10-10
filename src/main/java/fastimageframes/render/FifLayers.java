package fastimageframes.render;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
//#if MC < 1.21.9
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import fastimageframes.FastImageFrames;
import fastimageframes.mixin.BufferSourceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.SequencedMap;
//#endif

public final class FifLayers {

    public static final RenderType FRAME_LAYER = RenderTypes.entitySolidZOffsetForward(TextureAtlas.LOCATION_BLOCKS);

    //#if MC < 1.21.9
    private static boolean unsupported;
    //#endif

    private FifLayers() {
    }

    public static void registerBatched(RenderType layer, int initialSize) {
        //#if MC < 1.21.9
        SequencedMap<RenderType, ByteBufferBuilder> buffers = buffers();
        if (buffers == null || buffers.containsKey(layer)) {
            return;
        }
        ByteBufferBuilder builder = new ByteBufferBuilder(initialSize);
        try {
            buffers.put(layer, builder);
        } catch (RuntimeException e) {
            builder.close();
            disable(e);
        }
        //#endif
    }

    public static void unregisterBatched(RenderType layer) {
        //#if MC < 1.21.9
        SequencedMap<RenderType, ByteBufferBuilder> buffers = buffers();
        if (buffers == null || !buffers.containsKey(layer)) {
            return;
        }
        try {
            Minecraft.getInstance().renderBuffers().bufferSource().endBatch(layer);
            buffers.remove(layer).close();
        } catch (RuntimeException e) {
            disable(e);
        }
        //#endif
    }

    public static boolean isBatched(RenderType layer) {
        //#if MC < 1.21.9
        SequencedMap<RenderType, ByteBufferBuilder> buffers = buffers();
        return buffers != null && buffers.containsKey(layer);
        //#else
        return false;
        //#endif
    }

    //#if MC < 1.21.9
    private static SequencedMap<RenderType, ByteBufferBuilder> buffers() {
        if (unsupported) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.renderBuffers() == null) {
            return null;
        }
        MultiBufferSource.BufferSource source = minecraft.renderBuffers().bufferSource();
        if (source.getClass() != MultiBufferSource.BufferSource.class) {
            disable(null);
            return null;
        }
        return ((BufferSourceAccessor) source).fif$getFixedBuffers();
    }

    private static void disable(RuntimeException cause) {
        unsupported = true;
        FastImageFrames.LOGGER.info("Entity buffers are managed by another mod, frame batching left to it", cause);
    }
    //#endif

}
