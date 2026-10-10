package fastimageframes.mixin;

import fastimageframes.atlas.MapRenderStateData;
import net.minecraft.client.renderer.state.MapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MapRenderState.class)
public class MapRenderStateMixin implements MapRenderStateData {

    @Unique
    private int fif$mapId = -1;

    @Override
    public int fif$mapId() {
        return fif$mapId;
    }

    @Override
    public void fif$setMapId(int mapId) {
        fif$mapId = mapId;
    }

}
