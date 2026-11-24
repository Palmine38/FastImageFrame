package fastimageframes.mixin;

//#if MC >= 1.21.9
import fastimageframes.bake.BakedFrames;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif

@Mixin(FeatureRenderDispatcher.class)
public class FeatureRenderDispatcherShadowMixin {

    //#if MC >= 26.3
    @Inject(method = "prepareFrame", at = @At("RETURN"))
    private void fif$prepareShadow(CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
        BakedFrames.prepareShadow();
    }

    @Inject(method = "renderAllFeatures", at = @At("HEAD"))
    private static void fif$drawShadow(RenderPass pass, FeatureRenderDispatcher.PreparedFrame frame, CallbackInfo ci) {
        BakedFrames.drawShadow(pass);
    }
    //#else
    @Inject(method = "renderAllFeatures", at = @At("HEAD"))
    private void fif$drawShadow(CallbackInfo ci) {
        BakedFrames.drawShadow();
    }
    //#endif
}
//#endif
