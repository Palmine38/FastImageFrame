package fastimageframes.mixin;

//#if MC >= 26.2
import fastimageframes.bake.BakedFrames;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Inject(method = "extract", at = @At("HEAD"))
    private void fif$beginFrame(CallbackInfo ci) {
        BakedFrames.beginGpuFrame();
    }
}
//#endif
