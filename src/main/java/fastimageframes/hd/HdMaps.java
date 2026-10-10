package fastimageframes.hd;

import com.mojang.blaze3d.platform.NativeImage;
import fastimageframes.FastImageFrames;
import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class HdMaps {

    public static final HdMaps INSTANCE = new HdMaps();

    private static final long DOWNGRADE_DELAY_TICKS = 5 * 20;
    private static final double DOWNGRADE_HYSTERESIS = 1.5;
    private static final long GUI_ATLAS_DELAY_TICKS = 20;
    private static final long UPLOAD_PIXEL_BUDGET_PER_TICK = 4L * 1024L * 1024L;
    private static final long MB = 1024L * 1024L;

    private static final class Entry {
        final int mapId;
        final Identifier textureId;
        final String textureLabel;

        Supplier<byte[]> png;
        long version;
        boolean sourceMissing;
        long failedVersion = -1;

        HdMapTexture texture;
        int textureSize;
        int nativeSize;
        boolean needsRefresh = true;
        long downgradeSinceTick = -1;

        boolean inAtlas;
        int atlasSize;
        long atlasVersion;
        long guiUsedTick = Long.MIN_VALUE / 2;

        long generation;
        boolean decoding;

        long lastUsedTick = Long.MIN_VALUE / 2;
        int neededThisTick;
        double priorityThisTick = Double.MAX_VALUE;
        int needed = HdLod.UNBOUNDED;
        double priority;

        Entry(int mapId) {
            this.mapId = mapId;
            this.textureId = Identifier.fromNamespaceAndPath("fastimageframes", "hd_map/" + mapId);
            this.textureLabel = textureId.toString();
        }
    }

    private record DecodeResult(Entry entry, long generation, long version, NativeImage image, int nativeSize, int size,
                                boolean missing, Throwable error) {

        void close() {
            if (image != null) {
                image.close();
            }
        }
    }

    private final Int2ObjectOpenHashMap<Entry> entries = new Int2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<DecodeResult> decoded = new ConcurrentLinkedQueue<>();
    private final List<Entry> decodeCandidates = new ArrayList<>();
    private final List<Entry> evictionCandidates = new ArrayList<>();
    private ExecutorService decoder;
    private int maxDecodeJobs;
    private int decodeJobs;
    private long tick;
    private long textureBytes;
    private FifConfig.LodQuality lastQuality = FifConfig.hdLodQuality;
    private FifConfig.MinResolution lastMinResolution = FifConfig.hdLodMinResolution;

    private HdMaps() {
    }

    public void setSource(int mapId, long version, Supplier<byte[]> png) {
        Entry entry = entries.get(mapId);
        if (entry == null) {
            entry = new Entry(mapId);
            entries.put(mapId, entry);
        }
        boolean changed = entry.png == null || entry.version != version;
        entry.png = png;
        entry.version = version;
        entry.sourceMissing = false;
        if (changed) {
            cancelDecode(entry);
            entry.needsRefresh = true;
        }
    }

    public void clearSource(int mapId) {
        Entry entry = entries.remove(mapId);
        if (entry != null) {
            cancelDecode(entry);
            releaseTexture(entry);
            releaseAtlas(entry);
        }
    }

    public Identifier onExtract(int mapId) {
        if (entries.isEmpty()) {
            return null;
        }
        Entry entry = entries.get(mapId);
        if (entry == null || !FifConfig.hdActive()) {
            return null;
        }
        if (entry.lastUsedTick != tick) {
            entry.lastUsedTick = tick;
            entry.neededThisTick = 0;
            entry.priorityThisTick = Double.MAX_VALUE;
        }
        int needed = HdLod.neededPixels();
        if (needed > entry.neededThisTick) {
            entry.neededThisTick = needed;
        }
        double priority = HdLod.priority();
        if (priority < entry.priorityThisTick) {
            entry.priorityThisTick = priority;
        }
        if (!HdLod.inWorld()) {
            entry.guiUsedTick = tick;
        }
        return entry.texture != null ? entry.textureId : null;
    }

    public Identifier loadedTexture(int mapId) {
        Entry entry = entries.get(mapId);
        return entry != null && entry.texture != null && FifConfig.hdActive() ? entry.textureId : null;
    }

    public int getTextureSize(int mapId) {
        Entry entry = entries.get(mapId);
        if (entry == null) {
            return 0;
        }
        return entry.texture != null ? entry.textureSize : isAtlasCurrent(entry) ? entry.atlasSize : 0;
    }

    public boolean isInAtlas(int mapId) {
        Entry entry = entries.get(mapId);
        return entry != null && entry.texture == null && isAtlasCurrent(entry);
    }

    public String describe(int mapId) {
        Entry entry = entries.get(mapId);
        if (entry == null) {
            return "no source";
        }
        return "source v" + entry.version + (entry.sourceMissing ? " missing" : "") + ", texture " + entry.textureSize
                + ", atlas " + (isAtlasCurrent(entry) ? entry.atlasSize : 0) + ", native " + entry.nativeSize + ", needed " + entry.needed;
    }

    public boolean hasSources() {
        return !entries.isEmpty();
    }

    public boolean hasSource(int mapId) {
        return entries.containsKey(mapId);
    }

    public long getTextureBytes() {
        return textureBytes;
    }

    public void tick() {
        try {
            if (!FifConfig.hdActive()) {
                if (!entries.isEmpty()) {
                    clearAll();
                }
                drainStale();
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            HdLod.refreshProjection(minecraft);
            drainDecoded(minecraft);
            updateEntries();
            dispatchDecodes();
            enforceBudget();
        } finally {
            tick++;
        }
    }

    private void updateEntries() {
        int minRes = FifConfig.hdLodMinResolution.getPixels();
        decodeCandidates.clear();
        for (Entry entry : entries.values()) {
            if (entry.lastUsedTick == tick) {
                entry.needed = entry.neededThisTick;
                entry.priority = entry.priorityThisTick;
            }
            if (tick - entry.lastUsedTick <= 2) {
                planTexture(entry, minRes);
            } else if (entry.texture != null && tick - entry.lastUsedTick > FifConfig.hdUnloadUnusedAfterSeconds * 20L) {
                releaseTexture(entry);
            }
        }
    }

    private void planTexture(Entry entry, int minRes) {
        if (entry.decoding || entry.sourceMissing || entry.failedVersion == entry.version) {
            return;
        }
        boolean atlasCurrent = isAtlasCurrent(entry);
        if ((entry.texture == null && !atlasCurrent) || entry.needsRefresh) {
            decodeCandidates.add(entry);
            return;
        }
        int desired = HdLod.levelSize(entry.nativeSize, entry.needed, minRes);
        if (desired <= MapAtlas.TILE && atlasAllowed(entry)) {
            if (entry.texture == null) {
                if (desired > entry.atlasSize) {
                    decodeCandidates.add(entry);
                }
                return;
            }
            if (entry.textureSize <= MapAtlas.TILE || shouldShrink(entry, minRes, entry.textureSize)) {
                if (atlasCurrent && entry.atlasSize >= desired) {
                    releaseTexture(entry);
                } else {
                    decodeCandidates.add(entry);
                }
            }
            return;
        }
        if (entry.texture == null) {
            decodeCandidates.add(entry);
            return;
        }
        if (desired > entry.textureSize) {
            entry.downgradeSinceTick = -1;
            decodeCandidates.add(entry);
        } else if (desired < entry.textureSize) {
            if (shouldShrink(entry, minRes, entry.textureSize)) {
                decodeCandidates.add(entry);
            }
        } else {
            entry.downgradeSinceTick = -1;
        }
    }

    private boolean shouldShrink(Entry entry, int minRes, int currentSize) {
        int keep = HdLod.levelSize(entry.nativeSize, (long) Math.ceil(entry.needed * DOWNGRADE_HYSTERESIS), minRes);
        if (keep >= currentSize) {
            entry.downgradeSinceTick = -1;
            return false;
        }
        if (entry.downgradeSinceTick < 0) {
            entry.downgradeSinceTick = tick;
            return false;
        }
        long delay = FifConfig.hdLodQuality == FifConfig.LodQuality.CUSTOM ? FifConfig.hdLodCustomDowngradeSeconds * 20L : DOWNGRADE_DELAY_TICKS;
        return tick - entry.downgradeSinceTick >= delay;
    }

    private boolean atlasAllowed(Entry entry) {
        return tick - entry.guiUsedTick > GUI_ATLAS_DELAY_TICKS && FifConfig.atlasActive();
    }

    private static boolean isAtlasCurrent(Entry entry) {
        return entry.inAtlas && entry.atlasVersion == entry.version && MapAtlas.INSTANCE.hasOverride(entry.mapId);
    }

    private void dispatchDecodes() {
        if (decodeCandidates.isEmpty()) {
            return;
        }
        if (decoder == null) {
            int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
            AtomicInteger counter = new AtomicInteger();
            decoder = Executors.newFixedThreadPool(threads, r -> {
                Thread thread = new Thread(r, "FastImageFrames-HD-Decoder-" + counter.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
            maxDecodeJobs = threads * 2;
        }
        decodeCandidates.sort(Comparator.comparingDouble(e -> e.priority));
        int minRes = FifConfig.hdLodMinResolution.getPixels();
        for (Entry entry : decodeCandidates) {
            if (decodeJobs >= maxDecodeJobs) {
                break;
            }
            Supplier<byte[]> png = entry.png;
            long generation = entry.generation;
            long version = entry.version;
            int needed = entry.needed;
            entry.decoding = true;
            decodeJobs++;
            decoder.execute(() -> decoded.add(decode(entry, generation, version, png, needed, minRes)));
        }
        decodeCandidates.clear();
    }

    private static DecodeResult decode(Entry entry, long generation, long version, Supplier<byte[]> png, int needed, int minRes) {
        NativeImage image = null;
        try {
            byte[] bytes = png.get();
            if (bytes == null) {
                return new DecodeResult(entry, generation, version, null, 0, 0, true, null);
            }
            image = NativeImage.read(bytes);
            int width = image.getWidth();
            int height = image.getHeight();
            int nativeSize = Math.max(width, height);
            int size = HdLod.levelSize(nativeSize, needed, minRes);
            if (size < nativeSize) {
                double scale = size / (double) nativeSize;
                NativeImage resized = new NativeImage(Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)), false);
                image.resizeSubRectTo(0, 0, width, height, resized);
                image.close();
                image = resized;
            }
            return new DecodeResult(entry, generation, version, image, nativeSize, size, false, null);
        } catch (Throwable e) {
            if (image != null) {
                image.close();
            }
            return new DecodeResult(entry, generation, version, null, 0, 0, false, e);
        }
    }

    private void drainDecoded(Minecraft minecraft) {
        long pixels = 0;
        DecodeResult result;
        while ((result = decoded.peek()) != null) {
            if (pixels >= UPLOAD_PIXEL_BUDGET_PER_TICK) {
                break;
            }
            decoded.poll();
            decodeJobs--;
            Entry entry = result.entry();
            if (entries.get(entry.mapId) != entry || result.generation() != entry.generation) {
                result.close();
                continue;
            }
            entry.decoding = false;
            if (result.missing()) {
                entry.sourceMissing = true;
                continue;
            }
            if (result.error() != null) {
                FastImageFrames.LOGGER.warn("Unable to decode the HD image of map {}", entry.mapId, result.error());
                entry.failedVersion = result.version();
                continue;
            }
            entry.nativeSize = result.nativeSize();
            entry.needsRefresh = false;
            entry.downgradeSinceTick = -1;
            pixels += (long) result.image().getWidth() * result.image().getHeight();
            if (result.size() <= MapAtlas.TILE && atlasAllowed(entry)) {
                try {
                    MapAtlas.INSTANCE.setOverride(entry.mapId, result.image());
                } finally {
                    result.close();
                }
                entry.inAtlas = true;
                entry.atlasSize = result.size();
                entry.atlasVersion = result.version();
                releaseTexture(entry);
                continue;
            }
            HdMapTexture texture;
            try {
                texture = new HdMapTexture(entry.textureLabel, result.image());
            } catch (Throwable e) {
                result.close();
                FastImageFrames.LOGGER.warn("Unable to upload the HD image of map {}", entry.mapId, e);
                entry.failedVersion = result.version();
                continue;
            }
            long previousBytes = entry.texture == null ? 0 : entry.texture.getSizeBytes();
            minecraft.getTextureManager().register(entry.textureId, texture);
            textureBytes += texture.getSizeBytes() - previousBytes;
            entry.texture = texture;
            entry.textureSize = result.size();
        }
    }

    private void drainStale() {
        DecodeResult result;
        while ((result = decoded.poll()) != null) {
            decodeJobs--;
            result.close();
        }
    }

    private void cancelDecode(Entry entry) {
        entry.generation++;
        entry.decoding = false;
    }

    private void enforceBudget() {
        long budget = FifConfig.hdTextureMemoryMB * MB;
        if (textureBytes <= budget) {
            return;
        }
        evictionCandidates.clear();
        for (Entry entry : entries.values()) {
            if (entry.texture != null && tick - entry.lastUsedTick > 2) {
                evictionCandidates.add(entry);
            }
        }
        evictionCandidates.sort(Comparator.comparingLong(e -> e.lastUsedTick));
        for (Entry entry : evictionCandidates) {
            if (textureBytes <= budget) {
                break;
            }
            releaseTexture(entry);
        }
        evictionCandidates.clear();
    }

    public void onConfigChanged() {
        if (!FifConfig.hdActive()) {
            clearAll();
            return;
        }
        if (FifConfig.hdLodQuality != lastQuality || FifConfig.hdLodMinResolution != lastMinResolution) {
            lastQuality = FifConfig.hdLodQuality;
            lastMinResolution = FifConfig.hdLodMinResolution;
            for (Entry entry : entries.values()) {
                cancelDecode(entry);
                entry.needsRefresh = true;
            }
        }
        enforceBudget();
    }

    private void clearAll() {
        for (Entry entry : entries.values()) {
            cancelDecode(entry);
            releaseTexture(entry);
            releaseAtlas(entry);
        }
        entries.clear();
    }

    private void releaseTexture(Entry entry) {
        if (entry.texture == null) {
            return;
        }
        textureBytes -= entry.texture.getSizeBytes();
        Minecraft.getInstance().getTextureManager().release(entry.textureId);
        entry.texture = null;
        entry.textureSize = 0;
        entry.downgradeSinceTick = -1;
    }

    private void releaseAtlas(Entry entry) {
        if (entry.inAtlas) {
            entry.inAtlas = false;
            MapAtlas.INSTANCE.clearOverride(entry.mapId);
        }
    }

}
