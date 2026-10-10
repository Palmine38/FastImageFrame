package fastimageframes.mixin;

import fastimageframes.atlas.MapAtlas;
import fastimageframes.atlas.MapInstanceData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.resources.MapTextureManager$MapInstance")
public class MapInstanceMixin implements MapInstanceData {

    @Shadow
    private MapItemSavedData data;

    @Shadow
    @Final
    private DynamicTexture texture;

    @Shadow
    private boolean requiresUpload;

    @Shadow
    @Final
    //#if MC >= 26.1
    private Identifier location;
    //#else
    Identifier location;
    //#endif

    @Unique
    private boolean fif$updating;

    @Override
    public MapItemSavedData fif$data() {
        return data;
    }

    @Inject(method = "updateTextureIfNeeded", at = @At("HEAD"))
    private void fif$beforeUpdate(CallbackInfo ci) {
        fif$updating = requiresUpload;
    }

    @Inject(method = "updateTextureIfNeeded", at = @At("TAIL"))
    private void fif$afterUpdate(CallbackInfo ci) {
        if (fif$updating && texture != null) {
            fif$updating = false;
            String path = location.getPath();
            int mapId = Integer.parseInt(path, path.lastIndexOf('/') + 1, path.length(), 10);
            MapAtlas.INSTANCE.onVanillaTextureUpdated(location, mapId, texture.getPixels(), data.colors);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void fif$close(CallbackInfo ci) {
        MapAtlas.INSTANCE.onVanillaTextureClosed(location);
    }

}
