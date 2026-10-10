package fastimageframes.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fastimageframes.FifConfig;
import fastimageframes.hd.HdLod;
import fastimageframes.hd.HdMaps;
import fastimageframes.render.FifLayers;
import fastimageframes.render.FrameNeighbors;
import fastimageframes.render.OptimizedFrameMesh;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC >= 26.1
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#elif MC >= 1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
//#else
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
import com.llamalad7.mixinextras.sugar.Local;
//#if MC >= 26.1
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
//#else
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
//#endif

@Mixin(ItemFrameRenderer.class)
public class ItemFrameRendererMixin {

    @Shadow
    @Final
    private ItemModelResolver itemModelResolver;

    @Unique
    private static final String EXTRACT = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V";

    @Unique
    private static boolean fif$replaces(ItemFrameRenderState state) {
        //#if MC >= 1.21.9
        return state.mapId != null && !state.isInvisible && state.outlineColor == 0 && FifConfig.optimizedFramesActive()
                && OptimizedFrameMesh.hasVanillaModel(state.isGlowFrame);
        //#else
        return state.mapId != null && !state.isInvisible && FifConfig.optimizedFramesActive()
                && OptimizedFrameMesh.hasVanillaModel(state.isGlowFrame);
        //#endif
    }

    @Unique
    private static final String MAP_EXTRACT = "Lnet/minecraft/client/renderer/MapRenderer;extractRenderState(Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;Lnet/minecraft/client/renderer/state/MapRenderState;)V";

    @WrapWithCondition(method = EXTRACT, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemModelResolver;updateForNonLiving(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/entity/Entity;)V"))
    private boolean fif$skipMapItemModel(ItemModelResolver resolver, ItemStackRenderState renderState, ItemStack stack, ItemDisplayContext context, Entity entity) {
        if (FifConfig.enabled && stack.has(DataComponents.MAP_ID)) {
            renderState.clear();
            return false;
        }
        return true;
    }

    @Inject(method = EXTRACT, at = @At(value = "INVOKE", target = MAP_EXTRACT))
    private void fif$beforeMapExtract(ItemFrame entity, ItemFrameRenderState state, float partialTick, CallbackInfo ci) {
        if (HdMaps.INSTANCE.hasSources()) {
            HdLod.setDistance(Math.sqrt(state.distanceToCameraSq));
        }
    }

    @Inject(method = EXTRACT, at = @At(value = "INVOKE", target = MAP_EXTRACT, shift = At.Shift.AFTER))
    private void fif$afterMapExtract(ItemFrame entity, ItemFrameRenderState state, float partialTick, CallbackInfo ci) {
        HdLod.clear();
    }

    @Inject(method = EXTRACT, at = @At("TAIL"))
    private void fif$trackMapFrame(ItemFrame entity, ItemFrameRenderState state, float partialTick, CallbackInfo ci) {
        if (state.mapId == null) {
            ItemStack stack = entity.getItem();
            if (FifConfig.enabled && stack.has(DataComponents.MAP_ID)) {
                itemModelResolver.updateForNonLiving(state.item, stack, ItemDisplayContext.FIXED, entity);
            }
        } else if (fif$replaces(state)) {
            FrameNeighbors.add(entity.blockPosition(), state.direction);
        }
    }

    //#if MC >= 26.1
    @Unique
    private static final String FRAME_MODEL = "Lnet/minecraft/client/renderer/block/BlockModelResolver;updateForItemFrame(Lnet/minecraft/client/renderer/block/BlockModelRenderState;ZZ)V";

    @Unique
    private static boolean fif$skipFrameModel;

    @Inject(method = EXTRACT, at = @At(value = "INVOKE", target = FRAME_MODEL))
    private void fif$decideFrameModel(ItemFrame entity, ItemFrameRenderState state, float partialTick, CallbackInfo ci) {
        fif$skipFrameModel = fif$replaces(state);
    }

    @WrapWithCondition(method = EXTRACT, at = @At(value = "INVOKE", target = FRAME_MODEL))
    private boolean fif$skipFrameModel(BlockModelResolver resolver, BlockModelRenderState frameModel, boolean glow, boolean map) {
        if (fif$skipFrameModel) {
            frameModel.clear();
            return false;
        }
        return true;
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;isInvisible:Z", ordinal = 0))
    private void fif$submitFrame(ItemFrameRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (fif$replaces(state)) {
            fif$submit(state, poseStack, collector);
        }
    }
    //#elif MC >= 1.21.9
    @ModifyExpressionValue(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;isInvisible:Z", ordinal = 0))
    private boolean fif$replaceFrame(boolean invisible, @Local(argsOnly = true) ItemFrameRenderState state,
                                     @Local(argsOnly = true) PoseStack poseStack, @Local(argsOnly = true) SubmitNodeCollector collector) {
        if (invisible || !fif$replaces(state)) {
            return invisible;
        }
        fif$submit(state, poseStack, collector);
        return true;
    }
    //#else
    @ModifyExpressionValue(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;isInvisible:Z", ordinal = 0))
    private boolean fif$replaceFrame(boolean invisible, @Local(argsOnly = true) ItemFrameRenderState state,
                                     @Local(argsOnly = true) PoseStack poseStack, @Local(argsOnly = true) MultiBufferSource buffers,
                                     @Local(argsOnly = true) int light) {
        if (invisible || !fif$replaces(state)) {
            return invisible;
        }
        int faces = OptimizedFrameMesh.visibleFaces(state);
        if (faces != 0) {
            poseStack.pushPose();
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            OptimizedFrameMesh.emit(poseStack.last(), buffers.getBuffer(FifLayers.FRAME_LAYER), faces, state.isGlowFrame, light);
            poseStack.popPose();
        }
        return true;
    }
    //#endif

    //#if MC >= 1.21.9
    @Unique
    private static void fif$submit(ItemFrameRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        int faces = OptimizedFrameMesh.visibleFaces(state);
        if (faces == 0) {
            return;
        }
        boolean glow = state.isGlowFrame;
        int light = state.lightCoords;
        poseStack.pushPose();
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        collector.submitCustomGeometry(poseStack, FifLayers.FRAME_LAYER, (pose, consumer) -> OptimizedFrameMesh.emit(pose, consumer, faces, glow, light));
        poseStack.popPose();
    }
    //#endif

}
