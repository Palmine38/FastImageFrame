package fastimageframes.api;

import com.mojang.blaze3d.platform.NativeImage;
import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import fastimageframes.hd.HdMaps;

import java.util.function.Supplier;

public final class FastImageFramesApi {

    private FastImageFramesApi() {
    }

    public static boolean isHdActive() {
        return FifConfig.hdActive();
    }

    public static void setHdSource(int mapId, long version, Supplier<byte[]> png) {
        HdMaps.INSTANCE.setSource(mapId, version, png);
    }

    public static void clearHdSource(int mapId) {
        HdMaps.INSTANCE.clearSource(mapId);
    }

    public static boolean isAtlasActive() {
        return FifConfig.atlasActive();
    }

    public static void setMapImage(int mapId, NativeImage image) {
        MapAtlas.INSTANCE.setOverride(mapId, image);
    }

    public static void clearMapImage(int mapId) {
        MapAtlas.INSTANCE.clearOverride(mapId);
    }

    public static int getAtlasGeneration() {
        return MapAtlas.INSTANCE.getGeneration();
    }

}
