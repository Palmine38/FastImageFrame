package fastimageframes.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import fastimageframes.bake.BakedFrames;
import fastimageframes.render.Occlusion;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC >= 1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
//#else
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.List;
//#endif
//#if MC >= 26.1
import net.minecraft.client.renderer.state.level.LevelRenderState;
//#elif MC >= 1.21.9
import net.minecraft.client.renderer.state.LevelRenderState;
//#endif

@Mixin(LevelRenderer.class)
public class LevelRendererBakeMixin {

    //#if MC < 26.2
    //#if MC >= 26.1
    @Inject(method = "extractLevel", at = @At("HEAD"))
    //#else
    @Inject(method = "renderLevel", at = @At("HEAD"))
    //#endif
    private void fif$beginOcclusion(CallbackInfo ci) {
        Occlusion.beginFrame();
    }

    @ModifyExpressionValue(method = {"collectVisibleEntities", "extractVisibleEntities"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> fif$skipBakedFrames(Iterable<Entity> entities, @Local(argsOnly = true) Frustum frustum) {
        return BakedFrames.visibleEntities(entities, frustum);
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void fif$sectionDirty(int x, int y, int z, boolean important, CallbackInfo ci) {
        Occlusion.blocksChanged(x, y, z);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void fif$blockDirty(BlockPos pos, boolean important, CallbackInfo ci) {
        Occlusion.blocksChanged(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void fif$blocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        Occlusion.blocksChanged(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"))
    private void fif$sectionDirtyWithNeighbors(int x, int y, int z, CallbackInfo ci) {
        Occlusion.blocksChanged((x - 1) << 4, (y - 1) << 4, (z - 1) << 4, (x + 2 << 4) - 1, (y + 2 << 4) - 1, (z + 2 << 4) - 1);
    }
    //#endif

    //#if MC >= 1.21.9
    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void fif$submitBaked(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector collector, CallbackInfo ci) {
        BakedFrames.INSTANCE.submit(collector, poseStack);
    }
    //#else
    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void fif$renderBaked(PoseStack poseStack, MultiBufferSource.BufferSource buffers, Camera camera, DeltaTracker deltaTracker, List<Entity> entities, CallbackInfo ci) {
        BakedFrames.INSTANCE.render(buffers, camera.getPosition());
    }
    //#endif

    //#if MC >= 26.3
    @Inject(method = "prepareTranslucents", at = @At("HEAD"))
    private void fif$prepareGpu(CallbackInfo ci) {
        BakedFrames.prepareGpu();
    }
    //#endif

}
