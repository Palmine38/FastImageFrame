package fastimageframes.hd;

import fastimageframes.FastImageFrames;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

final class HdDiskCache {

    public static final long UNKNOWN_HASH = 0L;

    private static final String SUFFIX = ".png";
    private static final long CLEANUP_EVERY_BYTES = 64L * 1024L * 1024L;

    public record Record(int mapId, long colorsHash, Path file) {
    }

    private record ScanResult(long session, Int2ObjectMap<Record> records) {
    }

    private final Path root;
    private final ExecutorService io;
    private final AtomicReference<ScanResult> pendingScan = new AtomicReference<>();

    private Int2ObjectMap<Record> index = new Int2ObjectOpenHashMap<>();
    private Path serverDir;
    private String serverKey;
    private long session;
    private boolean ready;
    private long bytesSinceCleanup;

    HdDiskCache(Path root) {
        this.root = root;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "FastImageFrames-HD-DiskCache");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    public void openSession(String serverKey, long maxBytes) {
        closeSession();
        if (serverKey == null) {
            return;
        }
        long session = this.session;
        Path dir = root.resolve(sha256(serverKey).substring(0, 16));
        this.serverDir = dir;
        this.serverKey = serverKey;
        io.execute(() -> {
            Int2ObjectMap<Record> records = new Int2ObjectOpenHashMap<>();
            try {
                if (!Files.isDirectory(dir)) {
                    pendingScan.set(new ScanResult(session, records));
                    enforceLimit(maxBytes);
                    return;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
                    for (Path file : stream) {
                        Record record = parse(file);
                        if (record == null) {
                            continue;
                        }
                        Record previous = records.put(record.mapId(), record);
                        if (previous != null) {
                            if (lastModified(previous.file()) > lastModified(record.file())) {
                                records.put(previous.mapId(), previous);
                                deleteQuietly(record.file());
                            } else {
                                deleteQuietly(previous.file());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                FastImageFrames.LOGGER.warn("Unable to read the HD disk cache at {}", dir, e);
            }
            pendingScan.set(new ScanResult(session, records));
            enforceLimit(maxBytes);
        });
    }

    public void closeSession() {
        session++;
        serverDir = null;
        serverKey = null;
        ready = false;
        index = new Int2ObjectOpenHashMap<>();
        pendingScan.set(null);
    }

    public void poll() {
        if (ready) {
            return;
        }
        ScanResult result = pendingScan.getAndSet(null);
        if (result != null && result.session() == session) {
            result.records().putAll(index);
            index = result.records();
            ready = true;
        }
    }

    public boolean isOpen() {
        return serverDir != null;
    }

    public boolean isReady() {
        return ready && serverDir != null;
    }

    public Record get(int mapId) {
        return ready ? index.get(mapId) : null;
    }

    public boolean hasRecords() {
        return ready && !index.isEmpty();
    }

    public Record write(int mapId, long colorsHash, byte[] png, long maxBytes) {
        Path dir = serverDir;
        if (dir == null) {
            return null;
        }
        String serverKey = this.serverKey;
        Record record = new Record(mapId, colorsHash, dir.resolve(fileName(mapId, colorsHash)));
        Record previous = index.put(mapId, record);
        Path previousFile = previous == null ? null : previous.file();
        io.execute(() -> {
            try {
                if (!Files.isDirectory(dir)) {
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("_server.txt"), serverKey, StandardCharsets.UTF_8);
                }
                Path tmp = record.file().resolveSibling(record.file().getFileName() + ".tmp");
                Files.write(tmp, png);
                move(tmp, record.file());
                if (previousFile != null && !previousFile.equals(record.file())) {
                    deleteQuietly(previousFile);
                }
            } catch (IOException e) {
                FastImageFrames.LOGGER.warn("Unable to write {} to the HD disk cache", record.file(), e);
            }
        });
        bytesSinceCleanup += png.length;
        if (bytesSinceCleanup >= CLEANUP_EVERY_BYTES) {
            bytesSinceCleanup = 0;
            io.execute(() -> enforceLimit(maxBytes));
        }
        return record;
    }

    public Record updateHash(Record record, long colorsHash) {
        if (serverDir == null || index.get(record.mapId()) != record) {
            return record;
        }
        Record updated = new Record(record.mapId(), colorsHash, record.file().resolveSibling(fileName(record.mapId(), colorsHash)));
        index.put(record.mapId(), updated);
        io.execute(() -> {
            try {
                move(record.file(), updated.file());
            } catch (IOException e) {
                FastImageFrames.LOGGER.debug("Unable to rename {} in the HD disk cache", record.file(), e);
            }
        });
        return updated;
    }

    public void remove(int mapId) {
        if (!ready) {
            return;
        }
        Record record = index.remove(mapId);
        if (record != null) {
            io.execute(() -> deleteQuietly(record.file()));
        }
    }

    public void requestCleanup(long maxBytes) {
        io.execute(() -> enforceLimit(maxBytes));
    }

    public static byte[] read(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignore) {
        }
        return data;
    }

    public static long hashColors(byte[] colors) {
        CRC32C crc = new CRC32C();
        crc.update(colors, 0, colors.length);
        long hash = (crc.getValue() << 32) | (Arrays.hashCode(colors) & 0xFFFFFFFFL);
        return hash == UNKNOWN_HASH ? 1L : hash;
    }

    public static long digest(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return ((long) data.length << 32) ^ crc.getValue();
    }

    private void enforceLimit(long maxBytes) {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 2)) {
            stream.filter(p -> p.getFileName().toString().endsWith(SUFFIX) && Files.isRegularFile(p)).forEach(files::add);
        } catch (IOException e) {
            return;
        }
        long[] sizes = new long[files.size()];
        long[] times = new long[files.size()];
        long total = 0;
        for (int i = 0; i < files.size(); i++) {
            try {
                sizes[i] = Files.size(files.get(i));
                times[i] = Files.getLastModifiedTime(files.get(i)).toMillis();
            } catch (IOException e) {
                sizes[i] = 0;
                times[i] = Long.MAX_VALUE;
            }
            total += sizes[i];
        }
        if (total <= maxBytes) {
            return;
        }
        Integer[] order = new Integer[files.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(times[a], times[b]));
        for (int i : order) {
            if (total <= maxBytes) {
                break;
            }
            deleteQuietly(files.get(i));
            total -= sizes[i];
        }
    }

    private static Record parse(Path file) {
        String name = file.getFileName().toString();
        int dash = name.indexOf('-');
        if (dash <= 0 || !name.endsWith(SUFFIX)) {
            return null;
        }
        try {
            int mapId = Integer.parseInt(name.substring(0, dash));
            long hash = Long.parseUnsignedLong(name.substring(dash + 1, name.length() - SUFFIX.length()), 16);
            return new Record(mapId, hash, file);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fileName(int mapId, long colorsHash) {
        return mapId + "-" + HexFormat.of().toHexDigits(colorsHash) + SUFFIX;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignore) {
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
