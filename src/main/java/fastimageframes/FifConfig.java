package fastimageframes;

import eu.midnightdust.lib.config.MidnightConfig;
import fastimageframes.atlas.MapAtlas;
import fastimageframes.hd.HdCache;
import fastimageframes.hd.HdMaps;

public class FifConfig extends MidnightConfig {

    public enum AtlasSize {
        SIZE_2048(2048),
        SIZE_4096(4096),
        SIZE_8192(8192);

        private final int pixels;

        AtlasSize(int pixels) {
            this.pixels = pixels;
        }

        public int getPixels() {
            return pixels;
        }
    }

    public enum LodQuality {
        PERFORMANCE(0.6),
        BALANCED(1.0),
        QUALITY(1.6),
        ULTRA(2.5),
        CUSTOM(1.0);

        private final double factor;

        LodQuality(double factor) {
            this.factor = factor;
        }

        public double getFactor() {
            return factor;
        }
    }

    public enum MinResolution {
        SIZE_128(128),
        SIZE_256(256),
        SIZE_512(512),
        SIZE_1024(1024),
        NATIVE(Integer.MAX_VALUE);

        private final int pixels;

        MinResolution(int pixels) {
            this.pixels = pixels;
        }

        public int getPixels() {
            return pixels;
        }
    }

    @Entry
    public static boolean enabled = true;

    @Entry
    public static boolean mapAtlas = true;

    @Entry
    public static AtlasSize atlasSize = AtlasSize.SIZE_4096;

    @Entry
    public static boolean batchFrames = true;

    @Entry
    public static boolean optimizedFrameModel = true;

    @Entry
    public static boolean skipRedundantMapUpdates = true;

    @Entry
    public static boolean bakeFrames = true;

    @Entry
    public static boolean bakeItemFrames = true;

    @Entry
    public static boolean occlusionCulling = true;

    @Entry
    public static boolean hdLod = true;

    @Entry
    public static LodQuality hdLodQuality = LodQuality.BALANCED;

    @Entry(min = 2, max = 64)
    public static int hdLodCustomDistance = 8;

    @Entry(min = 0, max = 30)
    public static int hdLodCustomDowngradeSeconds = 5;

    @Entry
    public static MinResolution hdLodMinResolution = MinResolution.SIZE_128;

    @Entry(min = 64, max = 16384)
    public static int hdTextureMemoryMB = 512;

    @Entry(min = 5, max = 3600)
    public static int hdUnloadUnusedAfterSeconds = 120;

    @Comment(centered = true)
    public static Comment hdCacheSection;

    @Entry(min = 16, max = 4096)
    public static int hdRamCacheMB = 256;

    @Entry
    public static boolean hdDiskCache = true;

    @Entry(min = 32, max = 65536)
    public static int hdDiskCacheMaxMB = 1024;

    @Entry
    public static boolean hdTrustValidatedDiskCache = true;

    @Entry(min = 1, max = 64)
    public static int hdMaxConcurrentRequests = 8;

    public static boolean atlasActive() {
        return enabled && mapAtlas;
    }

    public static boolean optimizedFramesActive() {
        return enabled && optimizedFrameModel;
    }

    public static boolean bakeActive() {
        return enabled && bakeFrames && atlasActive();
    }

    public static boolean occlusionActive() {
        return enabled && occlusionCulling;
    }

    public static boolean hdActive() {
        return enabled && hdLod;
    }

    @Override
    public void writeChanges(String modid) {
        super.writeChanges(modid);
        onChanged();
    }

    public static void onChanged() {
        MapAtlas.INSTANCE.onConfigChanged();
        HdMaps.INSTANCE.onConfigChanged();
        HdCache.INSTANCE.onConfigChanged();
    }

}
