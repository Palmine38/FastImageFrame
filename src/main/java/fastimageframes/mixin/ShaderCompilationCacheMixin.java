package fastimageframes.mixin;

//#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.ShaderType;
import fastimageframes.render.FogShader;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.Configs.class)
public abstract class ShaderCompilationCacheMixin {

    @Shadow
    public abstract String getShader(Identifier id, ShaderType type);

    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void fif$fogShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        Identifier original = FogShader.original(id);
        if (original != null) {
            cir.setReturnValue(type == ShaderType.VERTEX ? FogShader.patch(getShader(original, type)) : null);
        }
    }
}
//#elif MC >= 1.21.6
import com.mojang.blaze3d.shaders.ShaderType;
import fastimageframes.render.FogShader;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public abstract class ShaderCompilationCacheMixin {

    @Shadow
    public abstract String getShaderSource(Identifier id, ShaderType type);

    @Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
    private void fif$fogShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        Identifier original = FogShader.original(id);
        if (original != null) {
            cir.setReturnValue(type == ShaderType.VERTEX ? FogShader.patch(getShaderSource(original, type)) : null);
        }
    }
}
//#endif
