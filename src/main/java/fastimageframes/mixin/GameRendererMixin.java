package fastimageframes.mixin;

import fastimageframes.FifConfig;
import fastimageframes.hd.HdLod;
import fastimageframes.bake.BakedFrames;
import fastimageframes.render.FifLayers;
import fastimageframes.render.FrameNeighbors;
import fastimageframes.render.OptimizedFrameMesh;
import net.minecraft.client.renderer.GameRenderer;
//#if MC < 26.1
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void fif$frameStart(CallbackInfo ci) {
        FrameNeighbors.onFrameStart();
        BakedFrames.INSTANCE.onFrameStart();
        OptimizedFrameMesh.onFrameStart();
        if (FifConfig.enabled && FifConfig.batchFrames) {
            FifLayers.registerBatched(FifLayers.FRAME_LAYER, 256 * 1024);
        } else if (FifLayers.isBatched(FifLayers.FRAME_LAYER)) {
            FifLayers.unregisterBatched(FifLayers.FRAME_LAYER);
        }
    }

    //#if MC < 26.1
    @Inject(method = "getFov", at = @At("RETURN"), require = 0)
    private void fif$captureFov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (useFovSetting) {
            HdLod.onWorldFov(cir.getReturnValueF());
        }
    }
    //#endif

}
