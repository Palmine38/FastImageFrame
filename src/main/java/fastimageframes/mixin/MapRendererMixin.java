package fastimageframes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import fastimageframes.atlas.MapRenderStateData;
import fastimageframes.hd.HdCache;
import fastimageframes.hd.HdMaps;
import fastimageframes.render.UvRemapConsumer;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC >= 1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//#else
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
//#endif

import java.util.Collections;

@Mixin(MapRenderer.class)
public class MapRendererMixin {

    //#if MC >= 1.21.11
    @Unique
    private static final String TEXT = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;text(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;";
    @Unique
    private static final String SUBMIT = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V";
    //#elif MC >= 1.21.9
    @Unique
    private static final String TEXT = "Lnet/minecraft/client/renderer/RenderType;text(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;";
    @Unique
    private static final String SUBMIT = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V";
    //#else
    @Unique
    private static final String TEXT = "Lnet/minecraft/client/renderer/RenderType;text(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;";
    @Unique
    private static final String GET_BUFFER = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    //#endif

    @Unique
    private static MapAtlas.Slot fif$slot;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void fif$hdTexture(MapId mapId, MapItemSavedData data, MapRenderState state, CallbackInfo ci) {
        ((MapRenderStateData) state).fif$setMapId(mapId.id());
        if (state.texture == null) {
            return;
        }
        HdCache.INSTANCE.onExtract(mapId.id());
        Identifier hd = HdMaps.INSTANCE.onExtract(mapId.id());
        if (hd != null) {
            state.texture = hd;
        }
    }

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;getDecorations()Ljava/lang/Iterable;"))
    private Iterable<MapDecoration> fif$skipEmptyDecorations(MapItemSavedData data, Operation<Iterable<MapDecoration>> original) {
        if (FifConfig.enabled && ((MapItemSavedDataAccessor) data).fif$getDecorations().isEmpty()) {
            return Collections.emptyList();
        }
        return original.call(data);
    }

    @Inject(method = "render", at = @At("HEAD"))
    //#if MC >= 1.21.9
    private void fif$pickSlot(MapRenderState state, PoseStack poseStack, SubmitNodeCollector collector, boolean active, int light, CallbackInfo ci) {
    //#else
    private void fif$pickSlot(MapRenderState state, PoseStack poseStack, MultiBufferSource buffers, boolean active, int light, CallbackInfo ci) {
    //#endif
        if (FifConfig.atlasActive()) {
            fif$slot = MapAtlas.INSTANCE.slotFor(state);
        } else {
            fif$slot = null;
            MapAtlas.releaseImmediatelyFast(state);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = TEXT, ordinal = 0))
    private RenderType fif$atlasLayer(Identifier texture, Operation<RenderType> original) {
        MapAtlas.Slot slot = fif$slot;
        return slot != null ? slot.layer() : original.call(texture);
    }

    //#if MC >= 1.21.9
    @ModifyArg(method = "render", at = @At(value = "INVOKE", target = SUBMIT, ordinal = 0), index = 2)
    private SubmitNodeCollector.CustomGeometryRenderer fif$remapUvs(SubmitNodeCollector.CustomGeometryRenderer renderer) {
        MapAtlas.Slot slot = fif$slot;
        if (slot == null) {
            return renderer;
        }
        return (pose, consumer) -> renderer.render(pose, UvRemapConsumer.INSTANCE.wrap(consumer, slot));
    }
    //#else
    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = GET_BUFFER, ordinal = 0))
    private VertexConsumer fif$remapUvs(VertexConsumer consumer) {
        MapAtlas.Slot slot = fif$slot;
        return slot != null ? UvRemapConsumer.INSTANCE.wrap(consumer, slot) : consumer;
    }
    //#endif

}
