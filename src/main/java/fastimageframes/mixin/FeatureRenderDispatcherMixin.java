package fastimageframes.mixin;

//#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import fastimageframes.bake.BakedFrames;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class FeatureRenderDispatcherMixin {

    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void fif$drawOpaque(RenderPass pass, CallbackInfo ci) {
        BakedFrames.drawGpu(pass, false, null, true);
    }

    @Inject(method = "executeTranslucent", at = @At("HEAD"))
    private void fif$drawSorted(RenderPass pass, CallbackInfo ci) {
        BakedFrames.drawGpu(pass, true, null, true);
    }

    @Inject(method = "executeOit", at = @At("HEAD"))
    private void fif$drawOit(OitStage stage, RenderPass pass, CallbackInfo ci) {
        BakedFrames.drawGpu(pass, true, stage, stage == OitStage.ACCUMULATE);
    }
}
//#elif MC >= 26.2
import fastimageframes.bake.BakedFrames;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class FeatureRenderDispatcherMixin {

    @Inject(method = "executeSolid", at = @At("HEAD"))
    private void fif$drawBaked(CallbackInfo ci) {
        BakedFrames.drawGpu();
    }
}
//#endif
