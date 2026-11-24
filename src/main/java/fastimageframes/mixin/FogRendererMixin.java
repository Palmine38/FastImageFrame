package fastimageframes.mixin;

//#if MC >= 1.21.6
import fastimageframes.render.FogRange;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"))
    private void fif$range(ByteBuffer buffer, int offset, Vector4f color, float environmentalStart, float environmentalEnd, float renderDistanceStart,
                           float renderDistanceEnd, float skyEnd, float cloudEnd, CallbackInfo ci) {
        if (environmentalStart != Float.MAX_VALUE) {
            FogRange.set(environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd);
        }
    }
}
//#endif
