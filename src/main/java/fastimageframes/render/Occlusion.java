package fastimageframes.render;

import fastimageframes.FifConfig;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class Occlusion {

    private static final int MAX_SLABS = 24;
    private static final int MAX_FOOTPRINT = 36;
    private static final byte VISIBLE = 1;
    private static final byte HIDDEN = 2;
    private static final long SETTLE_MILLIS = 1000L;
    private static final long PRUNE_MILLIS = 250L;
    private static final int NEAR_SECTIONS = 5;
    private static final int TESTS_PER_FRAME = 4096;

    private static final Long2ByteOpenHashMap BLOCKS = new Long2ByteOpenHashMap();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final double[] BOX = new double[6];
    private static final Long2LongOpenHashMap CHANGED = new Long2LongOpenHashMap();

    private static ClientLevel level;
    private static int cellX;
    private static int cellY;
    private static int cellZ;
    private static boolean enabled;
    private static boolean dirty;
    private static boolean unsettled;
    private static long now;
    private static long pruned;
    private static int budget;
    private static int generation;

    private Occlusion() {
    }

    public static void beginFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        enabled = FifConfig.occlusionActive() && minecraft.level != null;
        if (!enabled) {
            clear();
            level = null;
            return;
        }
        now = System.nanoTime() / 1000000L;
        budget = TESTS_PER_FRAME;
        if (!CHANGED.isEmpty() && now - pruned >= PRUNE_MILLIS) {
            pruned = now;
            CHANGED.values().removeIf(time -> now - time >= SETTLE_MILLIS);
        }
        Vec3 camera = Cameras.position();
        int x = Mth.floor(camera.x);
        int y = Mth.floor(camera.y);
        int z = Mth.floor(camera.z);
        if (minecraft.level != level || x != cellX || y != cellY || z != cellZ || dirty) {
            clear();
            level = minecraft.level;
            cellX = x;
            cellY = y;
            cellZ = z;
            dirty = false;
            enabled = !level.getBlockState(POS.set(x, y, z)).isSolidRender();
        }
    }

    public static void blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int y = minY >> 4; y <= maxY >> 4; y++) {
                for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                    blocksChanged(x, y, z);
                }
            }
        }
    }

    public static void blocksChanged(int x, int y, int z) {
        if (Math.abs(x - (cellX >> 4)) > NEAR_SECTIONS || Math.abs(y - (cellY >> 4)) > NEAR_SECTIONS || Math.abs(z - (cellZ >> 4)) > NEAR_SECTIONS) {
            return;
        }
        dirty = true;
        CHANGED.put(SectionPos.asLong(x, y, z), System.nanoTime() / 1000000L);
    }

    private static void clear() {
        generation++;
        BLOCKS.clear();
    }

    public static boolean active() {
        return enabled;
    }

    public static int generation() {
        return generation;
    }

    public static boolean settled() {
        return !unsettled;
    }

    public static boolean blockHidden(BlockPos pos, double margin) {
        return blockHidden(pos, -margin, -margin, -margin, 1 + margin, 1 + margin, 1 + margin);
    }

    public static boolean blockHidden(BlockPos pos, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        long key = pos.asLong();
        byte cached = BLOCKS.get(key);
        unsettled = false;
        if (cached == 0) {
            cached = test(pos.getX() + minX, pos.getY() + minY, pos.getZ() + minZ, pos.getX() + maxX, pos.getY() + maxY, pos.getZ() + maxZ);
            if (!unsettled) {
                BLOCKS.put(key, cached);
            }
        }
        return cached == HIDDEN;
    }

    private static byte test(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (budget <= 0) {
            unsettled = true;
            return VISIBLE;
        }
        budget--;
        BOX[0] = minX;
        BOX[1] = minY;
        BOX[2] = minZ;
        BOX[3] = maxX;
        BOX[4] = maxY;
        BOX[5] = maxZ;
        int[] cell = {cellX, cellY, cellZ};
        for (int axis = 0; axis < 3; axis++) {
            if (slabs(axis, cell)) {
                return HIDDEN;
            }
        }
        return VISIBLE;
    }

    private static boolean slabs(int d, int[] cell) {
        double cMin = cell[d];
        double cMax = cell[d] + 1;
        double bMin = BOX[d];
        double bMax = BOX[d + 3];
        int first;
        int last;
        int step;
        if (cMax <= bMin) {
            first = Mth.floor(bMin) - 1;
            last = Mth.ceil(cMax);
            step = -1;
            if (first < last) {
                return false;
            }
        } else if (bMax <= cMin) {
            first = Mth.ceil(bMax);
            last = Mth.floor(cMin) - 1;
            step = 1;
            if (first > last) {
                return false;
            }
        } else {
            return false;
        }
        int u = (d + 1) % 3;
        int v = (d + 2) % 3;
        int count = 0;
        for (int k = first; count < MAX_SLABS; k += step) {
            count++;
            double uMin = Double.MAX_VALUE;
            double uMax = -Double.MAX_VALUE;
            double vMin = Double.MAX_VALUE;
            double vMax = -Double.MAX_VALUE;
            for (int plane = k; plane <= k + 1; plane++) {
                for (int ci = 0; ci < 2; ci++) {
                    double cd = ci == 0 ? cMin : cMax;
                    for (int bi = 0; bi < 2; bi++) {
                        double bd = bi == 0 ? bMin : bMax;
                        double t = (plane - cd) / (bd - cd);
                        double s = 1.0 - t;
                        uMin = Math.min(uMin, s * cell[u] + t * BOX[u]);
                        uMax = Math.max(uMax, s * (cell[u] + 1) + t * BOX[u + 3]);
                        vMin = Math.min(vMin, s * cell[v] + t * BOX[v]);
                        vMax = Math.max(vMax, s * (cell[v] + 1) + t * BOX[v + 3]);
                    }
                }
            }
            int u0 = Mth.floor(uMin - 1.0E-4);
            int u1 = Mth.floor(uMax + 1.0E-4);
            int v0 = Mth.floor(vMin - 1.0E-4);
            int v1 = Mth.floor(vMax + 1.0E-4);
            if ((u1 - u0 + 1) * (v1 - v0 + 1) <= MAX_FOOTPRINT && opaque(d, k, u, u0, u1, v, v0, v1)) {
                return true;
            }
            if (k == last) {
                break;
            }
        }
        return false;
    }

    private static boolean opaque(int d, int k, int u, int u0, int u1, int v, int v0, int v1) {
        int[] p = new int[3];
        p[d] = k;
        for (int a = u0; a <= u1; a++) {
            p[u] = a;
            for (int b = v0; b <= v1; b++) {
                p[v] = b;
                if (!CHANGED.isEmpty() && CHANGED.containsKey(SectionPos.asLong(p[0] >> 4, p[1] >> 4, p[2] >> 4))) {
                    unsettled = true;
                    return false;
                }
                if (!level.getBlockState(POS.set(p[0], p[1], p[2])).isSolidRender()) {
                    return false;
                }
            }
        }
        return true;
    }
}
