package fastimageframes.mixin;

//#if MC >= 26.2
import fastimageframes.bake.BakedFrames;
import fastimageframes.render.Occlusion;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    @Inject(method = "extract", at = @At("HEAD"))
    private void fif$beginFrame(CallbackInfo ci) {
        BakedFrames.beginGpuFrame();
        Occlusion.beginFrame();
        BakedFrames.INSTANCE.bakePending();
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V", at = @At("HEAD"))
    private void fif$blockDirty(BlockPos pos, boolean important, CallbackInfo ci) {
        Occlusion.blocksChanged(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"))
    private void fif$blocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        Occlusion.blocksChanged(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
//#endif
