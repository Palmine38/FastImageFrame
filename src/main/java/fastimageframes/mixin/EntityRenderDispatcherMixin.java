package fastimageframes.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import fastimageframes.bake.BakedFrames;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#if MC >= 1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//#else
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
//#if MC >= 26.1
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#elif MC >= 1.21.9
import net.minecraft.client.renderer.state.CameraRenderState;
//#endif

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    //#if MC >= 26.3
    private <E extends Entity> void fif$skipBaked(E entity, Frustum frustum, double x, double y, double z, float partialTick, CallbackInfoReturnable<Boolean> cir) {
    //#else
    private <E extends Entity> void fif$skipBaked(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
    //#endif
        int cull = BakedFrames.cull(entity, frustum);
        if (cull != 0) {
            cir.setReturnValue(cull == 2);
        }
    }

    //#if MC >= 1.21.9
    @Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void fif$shadowTrigger(E entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = BakedFrames.INSTANCE.shadowState(entity);
        if (state != null) {
            cir.setReturnValue(state);
        }
    }

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private <S extends EntityRenderState> void fif$submitShadow(S state, CameraRenderState camera, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
        if (state instanceof BakedFrames.BakedFrameState baked) {
            BakedFrames.INSTANCE.submitShadow(baked, x, y, z, poseStack, collector);
            ci.cancel();
        }
    }
    //#else
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void fif$renderShadow(E entity, double x, double y, double z, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (BakedFrames.INSTANCE.renderShadow(entity, x, y, z, poseStack, buffers)) {
            ci.cancel();
        }
    }
    //#endif

}
