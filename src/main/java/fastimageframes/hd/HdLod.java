package fastimageframes.hd;

import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import net.minecraft.client.Minecraft;

public final class HdLod {

    public static final int UNBOUNDED = Integer.MAX_VALUE;

    private static int neededPixels = UNBOUNDED;
    private static double priority;
    private static boolean inWorld;

    private static double pixelsPerBlockAtUnitDistance = 800.0;
    private static double referencePixelsPerBlock = 800.0;
    private static double factor = 1.0;
    //#if MC < 26.1
    private static float lastWorldFov = -1F;
    //#endif

    private HdLod() {
    }

    public static void setDistance(double distance) {
        inWorld = true;
        priority = distance;
        double pixels = factor * pixelsPerBlockAtUnitDistance / Math.max(distance, 0.25);
        neededPixels = pixels >= UNBOUNDED ? UNBOUNDED : (int) Math.ceil(pixels);
    }

    public static void clear() {
        inWorld = false;
        neededPixels = UNBOUNDED;
        priority = 0.0;
    }

    static boolean inWorld() {
        return inWorld;
    }

    static int neededPixels() {
        return neededPixels;
    }

    static double priority() {
        return priority;
    }

    //#if MC < 26.1
    public static void onWorldFov(float fov) {
        lastWorldFov = fov;
    }
    //#endif

    static void refreshProjection(Minecraft minecraft) {
        //#if MC >= 26.2
        float fov = minecraft.gameRenderer.mainCamera().getFov();
        //#elif MC >= 26.1
        float fov = minecraft.gameRenderer.getMainCamera().getFov();
        //#else
        float fov = lastWorldFov;
        //#endif
        if (!(fov > 0F)) {
            fov = minecraft.options.fov().get().floatValue();
        }
        pixelsPerBlockAtUnitDistance = pixelsPerBlock(minecraft, fov);
        referencePixelsPerBlock = pixelsPerBlock(minecraft, minecraft.options.fov().get().floatValue());
        factor = FifConfig.hdLodQuality == FifConfig.LodQuality.CUSTOM
                ? MapAtlas.TILE * FifConfig.hdLodCustomDistance / referencePixelsPerBlock
                : FifConfig.hdLodQuality.getFactor();
    }

    public static double lowQualityDistance(Minecraft minecraft, FifConfig.LodQuality quality, int customDistance) {
        if (quality == FifConfig.LodQuality.CUSTOM) {
            return customDistance;
        }
        return quality.getFactor() * pixelsPerBlock(minecraft, minecraft.options.fov().get().floatValue()) / MapAtlas.TILE;
    }

    private static double pixelsPerBlock(Minecraft minecraft, float fov) {
        fov = Math.max(1F, Math.min(fov, 170F));
        int height = Math.max(1, minecraft.getWindow().getHeight());
        return height / (2.0 * Math.tan(Math.toRadians(fov) / 2.0));
    }

    static int levelSize(int nativeSize, long needed, int minSize) {
        int floor = Math.min(nativeSize, minSize);
        int size = nativeSize;
        while ((size >> 1) >= needed && (size >> 1) >= floor) {
            size >>= 1;
        }
        if (size > floor && needed <= floor) {
            size = floor;
        }
        return size;
    }

}
