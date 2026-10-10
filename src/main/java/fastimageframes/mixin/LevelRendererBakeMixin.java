package fastimageframes.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fastimageframes.bake.BakedFrames;
import net.minecraft.client.renderer.LevelRenderer;
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
import net.minecraft.world.entity.Entity;

import java.util.List;
//#endif
//#if MC >= 26.1
import net.minecraft.client.renderer.state.level.LevelRenderState;
//#elif MC >= 1.21.9
import net.minecraft.client.renderer.state.LevelRenderState;
//#endif

@Mixin(LevelRenderer.class)
public class LevelRendererBakeMixin {

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

}
