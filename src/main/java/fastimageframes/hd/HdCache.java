package fastimageframes.hd;

import fastimageframes.FifConfig;
import fastimageframes.ifc.IfcLink;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class HdCache {

    public static final HdCache INSTANCE = new HdCache();

    private static final long REQUEST_TIMEOUT_TICKS = 30 * 20;
    private static final long QUEUE_DROP_AFTER_TICKS = 5 * 20;
    private static final long DISK_INDEX_WAIT_TICKS = 5 * 20;
    private static final long FORGET_AFTER_TICKS = 10 * 60 * 20;
    private static final long MAX_DENY_BACKOFF_TICKS = 30 * 20;
    private static final long MULTIPART_TIMEOUT_TICKS = 10 * 20;
    private static final long MB = 1024L * 1024L;

    private enum NetState {
        NONE,
        QUEUED,
        IN_FLIGHT,
        DONE,
        DENIED,
        EMPTY,
        FAILED
    }

    private static final class Entry {
        final int mapId;

        NetState net = NetState.NONE;
        long queuedTick;
        long deadlineTick;
        long retryAtTick;
        int denyCount;
        boolean refetchAfterResponse;

        volatile byte[] png;
        long pngDigest;
        volatile HdDiskCache.Record diskRecord;
        boolean diskChecked;
        boolean revalidate;
        long hashRecheckTick = Long.MAX_VALUE;
        int hashRechecks;
        long sourceVersion;
        long sentVersion;

        long lastUsedTick = Long.MIN_VALUE / 2;

        Entry(int mapId) {
            this.mapId = mapId;
        }

        boolean hasSource() {
            return png != null || diskRecord != null;
        }
    }

    private static final class Multipart {
        final int mapId;
        final Int2ObjectOpenHashMap<byte[]> parts = new Int2ObjectOpenHashMap<>();
        int lastIndex;
        long totalBytes;
        long lastAccessTick;

        Multipart(int mapId, long tick) {
            this.mapId = mapId;
            this.lastAccessTick = tick;
        }

        void put(int index, byte[] data) {
            byte[] previous = parts.put(index, data);
            totalBytes += data.length - (previous == null ? 0 : previous.length);
        }

        boolean isCompleted() {
            if (lastIndex <= 0 || parts.size() < lastIndex + 1) {
                return false;
            }
            for (int i = 0; i <= lastIndex; i++) {
                if (!parts.containsKey(i)) {
                    return false;
                }
            }
            return true;
        }

        byte[] complete() {
            byte[] out = new byte[Math.toIntExact(totalBytes)];
            int position = 0;
            for (int i = 0; i <= lastIndex; i++) {
                byte[] part = parts.get(i);
                if (part != null) {
                    System.arraycopy(part, 0, out, position, part.length);
                    position += part.length;
                }
            }
            return position == out.length ? out : Arrays.copyOf(out, position);
        }
    }

    private record DiskLoad(Entry entry, long version, byte[] png) {
    }

    private final Int2ObjectOpenHashMap<Entry> entries = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Multipart> pendingMultipart = new Int2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<DiskLoad> diskLoads = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Entry> lostSources = new ConcurrentLinkedQueue<>();
    private final HdDiskCache disk = new HdDiskCache(FabricLoader.getInstance().getGameDir().resolve("fastimageframes").resolve("cache"));
    private final List<Entry> requestCandidates = new ArrayList<>();
    private final List<Entry> evictionCandidates = new ArrayList<>();
    private final IntOpenHashSet fedToIfc = new IntOpenHashSet();

    private String serverKey;
    private boolean serverSupported;
    private boolean active;
    private long tick;
    private long sessionStartTick;
    private int inFlight;
    private long pngBytes;
    private long nextSourceVersion = 1;
    private int lastDiskCacheMaxMB = FifConfig.hdDiskCacheMaxMB;

    private HdCache() {
    }

    public boolean isActive() {
        return active;
    }

    public boolean beforeIfcLookup(int mapId) {
        if (active) {
            return true;
        }
        if (!IfcLink.isLinked() || fedToIfc.contains(mapId)) {
            return false;
        }
        if (FifConfig.hdDiskCache && disk.isOpen() && !disk.isReady() && tick - sessionStartTick < DISK_INDEX_WAIT_TICKS) {
            return true;
        }
        fedToIfc.add(mapId);
        byte[] png = cachedPngForIfc(mapId);
        if (png != null) {
            IfcLink.giveToIfc(mapId, png);
        }
        return false;
    }

    private byte[] cachedPngForIfc(int mapId) {
        Entry entry = entries.get(mapId);
        if (entry != null && entry.png != null) {
            return entry.png;
        }
        if (!FifConfig.hdDiskCache || !FifConfig.hdTrustValidatedDiskCache || !disk.isReady()) {
            return null;
        }
        HdDiskCache.Record record = entry != null && entry.diskRecord != null ? entry.diskRecord : validatedDiskRecord(mapId, Minecraft.getInstance().level);
        if (record == null) {
            return null;
        }
        try {
            byte[] png = HdDiskCache.read(record.file());
            entry = getOrCreate(mapId);
            entry.diskRecord = record;
            entry.diskChecked = true;
            setPng(entry, png);
            entry.pngDigest = HdDiskCache.digest(png);
            if (entry.net == NetState.NONE) {
                entry.sourceVersion = nextSourceVersion++;
                setNet(entry, NetState.DONE);
            }
            return png;
        } catch (IOException e) {
            disk.remove(mapId);
            if (entry != null) {
                entry.diskRecord = null;
            }
            return null;
        }
    }

    public void onExtract(int mapId) {
        if (!active || !(serverSupported || disk.hasRecords())) {
            return;
        }
        Entry entry = entries.get(mapId);
        if (entry == null) {
            entry = new Entry(mapId);
            entries.put(mapId, entry);
        }
        entry.lastUsedTick = tick;
    }

    public void startSession(String serverKey) {
        clearAll();
        this.serverKey = serverKey;
        this.serverSupported = false;
        this.sessionStartTick = tick;
        if (serverKey != null && FifConfig.hdDiskCache) {
            disk.openSession(serverKey, FifConfig.hdDiskCacheMaxMB * MB);
        }
    }

    public void endSession() {
        clearAll();
        this.serverKey = null;
        this.serverSupported = false;
    }

    public void setServerSupported(boolean serverSupported) {
        this.serverSupported = serverSupported;
    }

    private void clearAll() {
        for (Entry entry : entries.values()) {
            clearSent(entry);
        }
        entries.clear();
        pendingMultipart.clear();
        fedToIfc.clear();
        diskLoads.clear();
        lostSources.clear();
        inFlight = 0;
        pngBytes = 0;
        disk.closeSession();
    }

    public void onHdResponse(int mapId, boolean accepted, byte[] data, Optional<Integer> multipart) {
        Entry entry = getOrCreate(mapId);
        if (!accepted) {
            entry.refetchAfterResponse = false;
            setNet(entry, NetState.DENIED);
            entry.denyCount++;
            entry.retryAtTick = tick + Math.min(MAX_DENY_BACKOFF_TICKS, 20L << Math.min(entry.denyCount - 1, 5));
            clearSent(entry);
            return;
        }
        if (multipart.isPresent()) {
            Multipart info = new Multipart(mapId, tick);
            info.put(0, data);
            pendingMultipart.put(multipart.get().intValue(), info);
            setNet(entry, NetState.IN_FLIGHT);
            entry.deadlineTick = tick + REQUEST_TIMEOUT_TICKS;
            return;
        }
        if (data.length == 0) {
            markEmpty(entry);
            return;
        }
        acceptPng(entry, data);
    }

    public void onHdMultipart(int multipartId, int index, byte[] data, boolean end) {
        Multipart info = pendingMultipart.get(multipartId);
        if (info == null) {
            return;
        }
        info.lastAccessTick = tick;
        if (data.length > 0) {
            info.put(index, data);
        }
        if (end) {
            info.lastIndex = index;
        }
        Entry entry = getOrCreate(info.mapId);
        if (entry.net == NetState.IN_FLIGHT) {
            entry.deadlineTick = tick + REQUEST_TIMEOUT_TICKS;
        }
        if (info.isCompleted()) {
            pendingMultipart.remove(multipartId);
            acceptPng(entry, info.complete());
        }
    }

    public void onImageUpdated(Iterable<Integer> mapIds) {
        for (int mapId : mapIds) {
            disk.remove(mapId);
            fedToIfc.remove(mapId);
            Entry entry = entries.get(mapId);
            if (entry == null) {
                continue;
            }
            setPng(entry, null);
            entry.pngDigest = 0;
            entry.diskRecord = null;
            entry.diskChecked = true;
            entry.revalidate = false;
            entry.denyCount = 0;
            entry.hashRecheckTick = Long.MAX_VALUE;
            entry.refetchAfterResponse = entry.net == NetState.IN_FLIGHT;
            if (!entry.refetchAfterResponse) {
                setNet(entry, NetState.NONE);
            }
        }
    }

    private void acceptPng(Entry entry, byte[] data) {
        setNet(entry, NetState.DONE);
        entry.denyCount = 0;
        entry.revalidate = false;
        long digest = HdDiskCache.digest(data);
        boolean unchanged = entry.pngDigest != 0 && entry.pngDigest == digest;
        setPng(entry, data);
        entry.pngDigest = digest;
        if (!unchanged) {
            entry.sourceVersion = nextSourceVersion++;
        }
        if (!active) {
            fedToIfc.add(entry.mapId);
        }
        if (entry.refetchAfterResponse) {
            entry.refetchAfterResponse = false;
            setNet(entry, NetState.NONE);
            return;
        }
        if (FifConfig.hdDiskCache && disk.isOpen() && !(unchanged && entry.diskRecord != null)) {
            entry.diskRecord = disk.write(entry.mapId, currentColorsHash(entry.mapId), data, FifConfig.hdDiskCacheMaxMB * MB);
            entry.hashRecheckTick = tick + 60;
            entry.hashRechecks = 0;
        }
    }

    private void markEmpty(Entry entry) {
        if (entry.refetchAfterResponse) {
            entry.refetchAfterResponse = false;
            setNet(entry, NetState.NONE);
            return;
        }
        setNet(entry, NetState.EMPTY);
        clearSent(entry);
        setPng(entry, null);
        entry.pngDigest = 0;
        entry.revalidate = false;
        if (entry.diskRecord != null || disk.get(entry.mapId) != null) {
            disk.remove(entry.mapId);
        }
        entry.diskRecord = null;
    }

    public void tick() {
        try {
            syncActive();
            syncDiskSession();
            disk.poll();
            drainDiskReads();
            if (active) {
                updateEntries();
                dispatchRequests();
            }
            expireMultiparts();
            enforceRamBudget();
        } finally {
            tick++;
        }
    }

    private void syncActive() {
        boolean nowActive = IfcLink.isLinked() && FifConfig.hdActive() && IfcLink.isIfcHdEnabled();
        if (nowActive == active) {
            return;
        }
        active = nowActive;
        fedToIfc.clear();
        if (active) {
            IfcLink.releaseIfcTextures();
        } else {
            pendingMultipart.clear();
            for (Entry entry : entries.values()) {
                clearSent(entry);
                entry.refetchAfterResponse = false;
                if (entry.net == NetState.QUEUED || entry.net == NetState.IN_FLIGHT || entry.net == NetState.DENIED) {
                    setNet(entry, NetState.NONE);
                }
            }
        }
    }

    private void updateEntries() {
        ClientLevel level = Minecraft.getInstance().level;
        boolean diskActive = FifConfig.hdDiskCache && disk.isOpen();
        requestCandidates.clear();

        ObjectIterator<Entry> itr = entries.values().iterator();
        while (itr.hasNext()) {
            Entry entry = itr.next();
            boolean recentlyUsed = tick - entry.lastUsedTick <= 2;
            switch (entry.net) {
                case NONE -> {
                    if (recentlyUsed) {
                        resolveSource(entry, level, diskActive);
                    }
                }
                case QUEUED -> {
                    if (tick - entry.lastUsedTick > QUEUE_DROP_AFTER_TICKS) {
                        setNet(entry, NetState.NONE);
                    } else {
                        requestCandidates.add(entry);
                    }
                }
                case IN_FLIGHT -> {
                    if (tick >= entry.deadlineTick) {
                        entry.refetchAfterResponse = false;
                        setNet(entry, NetState.NONE);
                    }
                }
                case DENIED -> {
                    if (recentlyUsed && serverSupported && tick >= entry.retryAtTick) {
                        queue(entry);
                    }
                }
                case DONE -> {
                    if (recentlyUsed) {
                        if (entry.revalidate && serverSupported) {
                            requestCandidates.add(entry);
                        }
                        sendSource(entry);
                        recheckHash(entry, level);
                    }
                }
                default -> {
                }
            }
            if (entry.net == NetState.NONE && entry.png == null && tick - entry.lastUsedTick > FORGET_AFTER_TICKS) {
                clearSent(entry);
                itr.remove();
            }
        }
    }

    private void resolveSource(Entry entry, ClientLevel level, boolean diskActive) {
        if (diskActive && !entry.diskChecked) {
            if (!disk.isReady()) {
                if (tick - sessionStartTick < DISK_INDEX_WAIT_TICKS) {
                    return;
                }
                entry.diskChecked = true;
            } else {
                entry.diskChecked = true;
                HdDiskCache.Record record = validatedDiskRecord(entry.mapId, level);
                if (record != null) {
                    entry.diskRecord = record;
                    entry.revalidate = !FifConfig.hdTrustValidatedDiskCache;
                    entry.sourceVersion = nextSourceVersion++;
                    setNet(entry, NetState.DONE);
                    return;
                }
            }
        }
        if (serverSupported) {
            queue(entry);
        }
    }

    private HdDiskCache.Record validatedDiskRecord(int mapId, ClientLevel level) {
        HdDiskCache.Record record = disk.get(mapId);
        if (record == null || record.colorsHash() == HdDiskCache.UNKNOWN_HASH || level == null) {
            return null;
        }
        MapItemSavedData data = level.getMapData(new MapId(mapId));
        return data != null && HdDiskCache.hashColors(data.colors) == record.colorsHash() ? record : null;
    }

    private void queue(Entry entry) {
        setNet(entry, NetState.QUEUED);
        entry.queuedTick = tick;
        requestCandidates.add(entry);
    }

    private void recheckHash(Entry entry, ClientLevel level) {
        if (entry.diskRecord == null || tick < entry.hashRecheckTick || level == null) {
            return;
        }
        MapItemSavedData data = level.getMapData(new MapId(entry.mapId));
        if (data != null) {
            long hash = HdDiskCache.hashColors(data.colors);
            if (hash != entry.diskRecord.colorsHash()) {
                entry.diskRecord = disk.updateHash(entry.diskRecord, hash);
            }
        }
        entry.hashRechecks++;
        entry.hashRecheckTick = entry.hashRechecks == 1 ? tick + 60 * 20 : Long.MAX_VALUE;
    }

    private void dispatchRequests() {
        if (requestCandidates.isEmpty() || !serverSupported || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        int free = FifConfig.hdMaxConcurrentRequests - inFlight;
        if (free <= 0) {
            return;
        }
        requestCandidates.sort(Comparator.comparingLong(e -> e.queuedTick));
        for (Entry entry : requestCandidates) {
            if (free-- <= 0) {
                break;
            }
            if (!IfcLink.sendHdRequest(entry.mapId)) {
                break;
            }
            setNet(entry, NetState.IN_FLIGHT);
            entry.revalidate = false;
            entry.deadlineTick = tick + REQUEST_TIMEOUT_TICKS;
        }
    }

    private void sendSource(Entry entry) {
        if (entry.sentVersion == entry.sourceVersion && HdMaps.INSTANCE.hasSource(entry.mapId)) {
            return;
        }
        if (!entry.hasSource()) {
            setNet(entry, NetState.NONE);
            entry.diskChecked = true;
            return;
        }
        long version = entry.sourceVersion;
        HdMaps.INSTANCE.setSource(entry.mapId, version, () -> readSource(entry, version));
        entry.sentVersion = version;
    }

    private byte[] readSource(Entry entry, long version) {
        byte[] png = entry.png;
        if (png != null) {
            return png;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HdDiskCache.Record record = entry.diskRecord;
            if (record == null) {
                break;
            }
            try {
                png = HdDiskCache.read(record.file());
                diskLoads.add(new DiskLoad(entry, version, png));
                return png;
            } catch (IOException e) {
                if (entry.diskRecord == record) {
                    break;
                }
            }
        }
        lostSources.add(entry);
        return null;
    }

    private void drainDiskReads() {
        DiskLoad load;
        while ((load = diskLoads.poll()) != null) {
            Entry entry = load.entry();
            if (entries.get(entry.mapId) == entry && entry.png == null && entry.sourceVersion == load.version()) {
                setPng(entry, load.png());
                entry.pngDigest = HdDiskCache.digest(load.png());
            }
        }
        Entry entry;
        while ((entry = lostSources.poll()) != null) {
            if (entries.get(entry.mapId) != entry || entry.net != NetState.DONE || entry.png != null) {
                entry.sentVersion = 0;
                continue;
            }
            if (entry.diskRecord != null) {
                disk.remove(entry.mapId);
                entry.diskRecord = null;
            }
            entry.diskChecked = true;
            entry.sentVersion = 0;
            setNet(entry, NetState.NONE);
        }
    }

    private void clearSent(Entry entry) {
        if (entry.sentVersion != 0) {
            entry.sentVersion = 0;
            HdMaps.INSTANCE.clearSource(entry.mapId);
        }
    }

    private void expireMultiparts() {
        if (pendingMultipart.isEmpty()) {
            return;
        }
        ObjectIterator<Multipart> itr = pendingMultipart.values().iterator();
        while (itr.hasNext()) {
            Multipart info = itr.next();
            if (tick - info.lastAccessTick > MULTIPART_TIMEOUT_TICKS) {
                itr.remove();
                Entry entry = entries.get(info.mapId);
                if (entry != null && entry.net == NetState.IN_FLIGHT) {
                    setNet(entry, NetState.NONE);
                }
            }
        }
    }

    private void enforceRamBudget() {
        long budget = FifConfig.hdRamCacheMB * MB;
        if (pngBytes <= budget) {
            return;
        }
        evictionCandidates.clear();
        for (Entry entry : entries.values()) {
            if (entry.png != null) {
                evictionCandidates.add(entry);
            }
        }
        evictionCandidates.sort(Comparator.<Entry>comparingInt(e -> e.diskRecord != null ? 0 : 1).thenComparingLong(e -> e.lastUsedTick));
        for (Entry entry : evictionCandidates) {
            if (pngBytes <= budget) {
                break;
            }
            setPng(entry, null);
        }
        evictionCandidates.clear();
    }

    public void onConfigChanged() {
        syncDiskSession();
        if (FifConfig.hdDiskCacheMaxMB != lastDiskCacheMaxMB) {
            lastDiskCacheMaxMB = FifConfig.hdDiskCacheMaxMB;
            disk.requestCleanup(FifConfig.hdDiskCacheMaxMB * MB);
        }
        enforceRamBudget();
    }

    private void syncDiskSession() {
        if (FifConfig.hdDiskCache) {
            if (!disk.isOpen() && serverKey != null) {
                disk.openSession(serverKey, FifConfig.hdDiskCacheMaxMB * MB);
            }
        } else if (disk.isOpen()) {
            disk.closeSession();
            for (Entry entry : entries.values()) {
                entry.diskRecord = null;
            }
        }
    }

    public boolean isServerSupported() {
        return serverSupported;
    }

    public boolean hasPngInRam(int mapId) {
        Entry entry = entries.get(mapId);
        return entry != null && entry.png != null;
    }

    public boolean isOnDisk(int mapId) {
        Entry entry = entries.get(mapId);
        return entry != null && entry.diskRecord != null;
    }

    public long getSourceVersion(int mapId) {
        Entry entry = entries.get(mapId);
        return entry == null ? 0 : entry.sourceVersion;
    }

    public long getPngBytes() {
        return pngBytes;
    }

    public String describe(int mapId) {
        Entry entry = entries.get(mapId);
        if (entry == null) {
            return "unknown";
        }
        return entry.net + ", v" + entry.sourceVersion + " sent v" + entry.sentVersion + (entry.png != null ? ", in RAM" : "")
                + (entry.diskRecord != null ? ", on disk" : "") + (entry.denyCount > 0 ? ", refused " + entry.denyCount + "x" : "");
    }

    private Entry getOrCreate(int mapId) {
        Entry entry = entries.get(mapId);
        if (entry == null) {
            entry = new Entry(mapId);
            entries.put(mapId, entry);
        }
        return entry;
    }

    private void setNet(Entry entry, NetState state) {
        if (entry.net == NetState.IN_FLIGHT && state != NetState.IN_FLIGHT) {
            inFlight--;
        } else if (entry.net != NetState.IN_FLIGHT && state == NetState.IN_FLIGHT) {
            inFlight++;
        }
        entry.net = state;
    }

    private void setPng(Entry entry, byte[] png) {
        pngBytes += (png == null ? 0 : png.length) - (entry.png == null ? 0 : entry.png.length);
        entry.png = png;
    }

    private static long currentColorsHash(int mapId) {
        ClientLevel level = Minecraft.getInstance().level;
        MapItemSavedData data = level == null ? null : level.getMapData(new MapId(mapId));
        return data == null ? HdDiskCache.UNKNOWN_HASH : HdDiskCache.hashColors(data.colors);
    }

}
