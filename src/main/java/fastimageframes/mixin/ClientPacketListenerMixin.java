package fastimageframes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @WrapOperation(method = "handleMapItemData", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/MapTextureManager;update(Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)V"))
    private void fif$onlyWhenColorsChanged(MapTextureManager manager, MapId mapId, MapItemSavedData data, Operation<Void> original,
                                           @Local(argsOnly = true) ClientboundMapItemDataPacket packet) {
        if (!FifConfig.enabled || !FifConfig.skipRedundantMapUpdates || packet.colorPatch().isPresent()) {
            original.call(manager, mapId, data);
        }
        if (packet.colorPatch().isPresent()) {
            MapAtlas.INSTANCE.onColorsChanged(mapId.id());
        }
    }

}
