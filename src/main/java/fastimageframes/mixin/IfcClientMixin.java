package fastimageframes.mixin;

import fastimageframes.hd.HdCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.loohp.imageframe.ImageFrameClient", remap = false)
public class IfcClientMixin {

    @Inject(method = "getOrRequestLoadedHdMap", at = @At("HEAD"), cancellable = true, require = 0)
    private void fif$hdByFastImageFrames(int mapId, CallbackInfoReturnable<Object> cir) {
        if (HdCache.INSTANCE.beforeIfcLookup(mapId)) {
            cir.setReturnValue(null);
        }
    }

}
