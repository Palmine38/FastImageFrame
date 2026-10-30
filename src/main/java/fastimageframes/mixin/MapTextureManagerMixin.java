package fastimageframes.mixin;

import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import fastimageframes.atlas.MapInstanceData;
import fastimageframes.render.MapPreviews;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MapTextureManager.class)
public class MapTextureManagerMixin {

    @Shadow
    @Final
    private Int2ObjectMap<?> maps;

    @Inject(method = "resetData", at = @At("TAIL"))
    private void fif$clearAtlas(CallbackInfo ci) {
        MapAtlas.INSTANCE.clear();
        MapPreviews.INSTANCE.clear();
    }

    @Inject(method = "getOrCreateMapInstance", at = @At("HEAD"), cancellable = true)
    private void fif$knownInstance(MapId id, MapItemSavedData data, CallbackInfoReturnable<Object> cir) {
        if (!FifConfig.enabled) {
            return;
        }
        Object instance = maps.get(id.id());
        if (instance != null && ((MapInstanceData) instance).fif$data() == data) {
            cir.setReturnValue(instance);
        }
    }

}
