package fastimageframes.render;

import com.mojang.blaze3d.platform.NativeImage;
import fastimageframes.FastImageFrames;
import fastimageframes.FifConfig;
import fastimageframes.hd.HdCache;
import fastimageframes.hd.HdMaps;
import fastimageframes.ifc.IfcLink;
import fastimageframes.mixin.MapItemSavedDataAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.GuiGraphics;
//#endif
//#if MC >= 1.21.6
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2fStack;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
//#endif

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class MapPreviews {

    public static final MapPreviews INSTANCE = new MapPreviews();

    private static final String FILLED = "com.loohp.imageframe.object.FilledMapTooltipComponent";
    private static final String IMAGE = "com.loohp.imageframe.object.ImageMapTooltipComponent";
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/map/map_background.png");
    private static final int TILE = 128;
    private static final long CHECK_NANOS = 250_000_000L;
    private static final long EXPIRE_NANOS = 10_000_000_000L;

    private record Key(boolean image, int id, int width, int height) {
    }

    private record Result(long generation, int[] pixels, long[] stamps) {
    }

    private record Job(long generation, int[] mapIds, byte[][] colors, Supplier<byte[]>[] hd, long[] stamps, int cols, int rows, int width, int height) {
    }

    private final class Preview {
        final Key key;
        final Identifier textureId;
        long lastUsed;
        long lastCheck;
        boolean checked;
        long generation;
        long[] built;
        boolean registered;
        boolean building;
        final AtomicReference<Result> result = new AtomicReference<>();

        Preview(Key key) {
            this.key = key;
            this.textureId = Identifier.fromNamespaceAndPath("fastimageframes", "map_preview/" + (++counter));
        }
    }

    private final Map<Key, Preview> previews = new HashMap<>();
    private ExecutorService worker;
    private int counter;
    private int drawn;
    private long lastExpire = System.nanoTime();

    private boolean reflectionFailed;
    private Field filledId;
    private Field imageIndex;
    private Object ifc;
    private Method imageData;
    private Method dataWidth;
    private Method dataHeight;
    private Method dataMapIds;

    private MapPreviews() {
    }

    //#if MC >= 26.1
    public boolean draw(Object component, int x, int y, GuiGraphicsExtractor graphics) {
    //#else
    public boolean draw(Object component, int x, int y, GuiGraphics graphics) {
    //#endif
        if (!FifConfig.mapPreviewsActive() || reflectionFailed) {
            return false;
        }
        String name = component.getClass().getName();
        boolean image = IMAGE.equals(name);
        if (!image && !FILLED.equals(name)) {
            return false;
        }
        if (!HdCache.INSTANCE.isActive() && IfcLink.isIfcHdEnabled()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return false;
        }
        try {
            if (filledId == null) {
                lookup(component.getClass().getClassLoader());
            }
            int[] mapIds;
            int cols;
            int rows;
            int id;
            int backgroundWidth;
            float mapX;
            float mapY;
            float mapWidth;
            float mapHeight;
            if (image) {
                id = imageIndex.getInt(component);
                Object data = imageData.invoke(ifc, id);
                if (data == null) {
                    return false;
                }
                cols = (int) dataWidth.invoke(data);
                rows = (int) dataHeight.invoke(data);
                IntList ids = (IntList) dataMapIds.invoke(data);
                if (cols <= 0 || rows <= 0) {
                    return false;
                }
                mapIds = ids.toIntArray();
                int tile = Math.max(1, (int) Math.floor(58F / rows));
                backgroundWidth = (int) Math.ceil(cols * tile + 6F);
                mapX = Math.round((backgroundWidth - (cols * tile + 4F)) * 0.5F) + 2F;
                mapY = Math.round((64F - (rows * tile + 4F)) * 0.5F) + 2F;
                mapWidth = cols * tile;
                mapHeight = rows * tile;
            } else {
                id = ((MapId) filledId.get(component)).id();
                mapIds = new int[]{id};
                cols = 1;
                rows = 1;
                backgroundWidth = 64;
                mapX = 3.2F;
                mapY = 3.2F;
                mapWidth = 57.6F;
                mapHeight = 57.6F;
            }
            for (int mapId : mapIds) {
                MapItemSavedData data = level.getMapData(new MapId(mapId));
                if (data != null && shownDecorations(data)) {
                    return false;
                }
                HdCache.INSTANCE.onExtract(mapId);
            }
            float scale = (float) minecraft.getWindow().getGuiScale() * poseScale(graphics);
            int width = Math.round(mapWidth * scale);
            int height = Math.round(mapHeight * scale);
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                return false;
            }
            long now = System.nanoTime();
            Preview preview = previews.computeIfAbsent(new Key(image, id, width, height), Preview::new);
            preview.lastUsed = now;
            update(preview, level, mapIds, cols, rows, now);
            expire(now);
            if (!preview.registered) {
                return false;
            }
            blit(graphics, BACKGROUND, x, y, backgroundWidth, 64, backgroundWidth, 64);
            float left = Math.round((x + mapX) * scale) / scale;
            float top = Math.round((y + mapY) * scale) / scale;
            //#if MC >= 1.21.6
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(left, top);
            pose.scale(1F / scale, 1F / scale);
            blit(graphics, preview.textureId, 0, 0, width, height, width, height);
            pose.popMatrix();
            //#else
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(left, top, 0F);
            pose.scale(1F / scale, 1F / scale, 1F);
            blit(graphics, preview.textureId, 0, 0, width, height, width, height);
            pose.popPose();
            //#endif
            drawn++;
            return true;
        } catch (Throwable e) {
            reflectionFailed = true;
            FastImageFrames.LOGGER.warn("Unable to draw map previews, leaving them to ImageFrameClient", e);
            return false;
        }
    }

    public int drawn() {
        return drawn;
    }

    public void clear() {
        for (Preview preview : previews.values()) {
            release(preview);
        }
        previews.clear();
    }

    private static boolean shownDecorations(MapItemSavedData data) {
        for (MapDecoration decoration : ((MapItemSavedDataAccessor) data).fif$getDecorations().values()) {
            if (decoration.renderOnFrame()) {
                return true;
            }
        }
        return false;
    }

    private void lookup(ClassLoader loader) throws ReflectiveOperationException {
        filledId = Class.forName(FILLED, false, loader).getDeclaredField("id");
        filledId.setAccessible(true);
        imageIndex = Class.forName(IMAGE, false, loader).getDeclaredField("index");
        imageIndex.setAccessible(true);
        Class<?> client = Class.forName("com.loohp.imageframe.ImageFrameClient", false, loader);
        ifc = client.getField("MOD").get(null);
        imageData = client.getMethod("getOrRequestImageMapData", int.class);
        Class<?> data = Class.forName("com.loohp.imageframe.object.ImageMapData", false, loader);
        dataWidth = data.getMethod("width");
        dataHeight = data.getMethod("height");
        dataMapIds = data.getMethod("mapIds");
    }

    private void update(Preview preview, ClientLevel level, int[] mapIds, int cols, int rows, long now) {
        Result result = preview.result.getAndSet(null);
        if (result != null && result.generation() == preview.generation) {
            upload(preview, result);
        }
        if (preview.checked && now - preview.lastCheck < CHECK_NANOS) {
            return;
        }
        preview.checked = true;
        preview.lastCheck = now;
        long[] stamps = new long[mapIds.length];
        byte[][] colors = new byte[mapIds.length][];
        for (int i = 0; i < mapIds.length; i++) {
            MapItemSavedData data = level.getMapData(new MapId(mapIds[i]));
            if (data != null && data.colors != null && data.colors.length >= TILE * TILE) {
                colors[i] = data.colors;
                stamps[i] = Arrays.hashCode(data.colors) * 31L + 1;
            }
            stamps[i] = stamps[i] * 1_000_003L + HdMaps.INSTANCE.sourceVersion(mapIds[i]);
        }
        if (preview.building || Arrays.equals(stamps, preview.built)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Supplier<byte[]>[] hd = new Supplier[mapIds.length];
        for (int i = 0; i < mapIds.length; i++) {
            if (colors[i] != null) {
                colors[i] = colors[i].clone();
            }
            if (HdMaps.INSTANCE.sourceVersion(mapIds[i]) != 0) {
                hd[i] = HdMaps.INSTANCE.source(mapIds[i]);
            }
        }
        Job job = new Job(++preview.generation, mapIds, colors, hd, stamps, cols, rows, preview.key.width(), preview.key.height());
        preview.building = true;
        boolean draft = !preview.registered;
        executor().execute(() -> build(preview, job, draft));
    }

    private void build(Preview preview, Job job, boolean draft) {
        try {
            boolean anyHd = false;
            for (Supplier<byte[]> source : job.hd()) {
                anyHd |= source != null;
            }
            if (draft && anyHd) {
                preview.result.set(new Result(job.generation(), compose(job, false), null));
            }
            preview.result.set(new Result(job.generation(), compose(job, true), job.stamps()));
        } catch (Throwable e) {
            FastImageFrames.LOGGER.warn("Unable to build a map preview", e);
            preview.result.set(new Result(job.generation(), null, job.stamps()));
        }
    }

    private void upload(Preview preview, Result result) {
        if (result.stamps() != null) {
            preview.building = false;
            preview.built = result.stamps();
        }
        int[] pixels = result.pixels();
        if (pixels == null) {
            return;
        }
        int width = preview.key.width();
        int height = preview.key.height();
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixel(x, y, pixels[x + y * width]);
            }
        }
        MipmappedTexture texture = new MipmappedTexture(preview.textureId.toString(), image, null);
        texture.setPixels(new NativeImage(1, 1, false));
        Minecraft.getInstance().getTextureManager().register(preview.textureId, texture);
        preview.registered = true;
    }

    private void expire(long now) {
        if (now - lastExpire < CHECK_NANOS) {
            return;
        }
        lastExpire = now;
        Iterator<Preview> itr = previews.values().iterator();
        while (itr.hasNext()) {
            Preview preview = itr.next();
            if (now - preview.lastUsed > EXPIRE_NANOS) {
                release(preview);
                preview.generation++;
                itr.remove();
            }
        }
    }

    private void release(Preview preview) {
        if (preview.registered) {
            preview.registered = false;
            Minecraft.getInstance().getTextureManager().release(preview.textureId);
        }
    }

    private ExecutorService executor() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "FastImageFrames-Map-Previews");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }
        return worker;
    }

    private static int[] compose(Job job, boolean hd) {
        int width = job.width();
        int height = job.height();
        int[] out = new int[width * height];
        int[] colorPixels = new int[TILE * TILE];
        for (int i = 0; i < job.mapIds().length; i++) {
            int col = i % job.cols();
            int row = i / job.cols();
            if (row >= job.rows()) {
                break;
            }
            int x0 = Math.round(col * width / (float) job.cols());
            int x1 = Math.round((col + 1) * width / (float) job.cols());
            int y0 = Math.round(row * height / (float) job.rows());
            int y1 = Math.round((row + 1) * height / (float) job.rows());
            if (hd && job.hd()[i] != null && resampleHd(job.hd()[i], out, width, x0, y0, x1 - x0, y1 - y0)) {
                continue;
            }
            byte[] colors = job.colors()[i];
            if (colors == null) {
                continue;
            }
            for (int p = 0; p < TILE * TILE; p++) {
                colorPixels[p] = MapColor.getColorFromPackedId(colors[p] & 0xFF);
            }
            resample(colorPixels, TILE, TILE, out, width, x0, y0, x1 - x0, y1 - y0);
        }
        return out;
    }

    private static boolean resampleHd(Supplier<byte[]> source, int[] out, int outWidth, int x0, int y0, int width, int height) {
        byte[] png = source.get();
        if (png == null) {
            return false;
        }
        try (NativeImage image = NativeImage.read(png)) {
            resample(image.getPixels(), image.getWidth(), image.getHeight(), out, outWidth, x0, y0, width, height);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void resample(int[] src, int srcWidth, int srcHeight, int[] out, int outWidth, int x0, int y0, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int[] xStart = new int[width];
        float[][] xWeights = new float[width][];
        spans(srcWidth, width, xStart, xWeights);
        int[] yStart = new int[height];
        float[][] yWeights = new float[height][];
        spans(srcHeight, height, yStart, yWeights);
        float[] rows = new float[srcHeight * width * 4];
        for (int sy = 0; sy < srcHeight; sy++) {
            int base = sy * srcWidth;
            for (int x = 0; x < width; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                float[] weights = xWeights[x];
                for (int k = 0; k < weights.length; k++) {
                    int pixel = src[base + xStart[x] + k];
                    float w = weights[k] * (pixel >>> 24);
                    a += w;
                    r += w * (pixel >> 16 & 0xFF);
                    g += w * (pixel >> 8 & 0xFF);
                    b += w * (pixel & 0xFF);
                }
                int index = (sy * width + x) * 4;
                rows[index] = a;
                rows[index + 1] = r;
                rows[index + 2] = g;
                rows[index + 3] = b;
            }
        }
        for (int y = 0; y < height; y++) {
            float[] weights = yWeights[y];
            for (int x = 0; x < width; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                for (int k = 0; k < weights.length; k++) {
                    int index = ((yStart[y] + k) * width + x) * 4;
                    float w = weights[k];
                    a += w * rows[index];
                    r += w * rows[index + 1];
                    g += w * rows[index + 2];
                    b += w * rows[index + 3];
                }
                int pixel = 0;
                if (a > 0) {
                    int alpha = Math.min(255, Math.round(a));
                    pixel = alpha << 24 | Math.min(255, Math.round(r / a)) << 16 | Math.min(255, Math.round(g / a)) << 8 | Math.min(255, Math.round(b / a));
                }
                out[(y0 + y) * outWidth + x0 + x] = pixel;
            }
        }
    }

    private static void spans(int srcSize, int size, int[] start, float[][] weights) {
        double step = srcSize / (double) size;
        for (int i = 0; i < size; i++) {
            double from = i * step;
            double to = Math.min(srcSize, (i + 1) * step);
            int first = (int) Math.floor(from);
            int last = Math.min(srcSize - 1, (int) Math.ceil(to) - 1);
            float[] w = new float[last - first + 1];
            for (int k = first; k <= last; k++) {
                w[k - first] = (float) ((Math.min(to, k + 1) - Math.max(from, k)) / step);
            }
            start[i] = first;
            weights[i] = w;
        }
    }

    //#if MC >= 26.1
    private static float poseScale(GuiGraphicsExtractor graphics) {
    //#else
    private static float poseScale(GuiGraphics graphics) {
    //#endif
        //#if MC >= 1.21.6
        Matrix3x2fStack pose = graphics.pose();
        float scale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        //#else
        org.joml.Matrix4f pose = graphics.pose().last().pose();
        float scale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        //#endif
        return scale > 0 ? scale : 1F;
    }

    //#if MC >= 26.1
    private static void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
    //#else
    private static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
    //#endif
        //#if MC >= 1.21.6
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0F, 0F, width, height, textureWidth, textureHeight);
        //#else
        graphics.blit(RenderType::guiTextured, texture, x, y, 0F, 0F, width, height, textureWidth, textureHeight);
        //#endif
    }

}
