package fastimageframes.render;

import fastimageframes.FifConfig;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class Occlusion {

    private static final int MAX_SLABS = 24;
    private static final int MAX_FOOTPRINT = 36;
    private static final int MAX_SECTION_FOOTPRINT = 256;
    private static final byte VISIBLE = 1;
    private static final byte HIDDEN = 2;
    private static final long SETTLE_MILLIS = 1000L;
    private static final long PRUNE_MILLIS = 250L;
    private static final int NEAR_SECTIONS = 5;
    private static final int TESTS_PER_FRAME = 1024;
    private static final long NANOS_PER_FRAME = 400_000L;
    private static final int MAX_CACHED = 1 << 16;

    private static final Long2IntOpenHashMap BLOCKS = new Long2IntOpenHashMap();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final double[] BOX = new double[6];
    private static final int[] CELL = new int[3];
    private static final int[] PROBE = new int[3];
    private static final double[] INV = new double[4];
    private static final Long2LongOpenHashMap CHANGED = new Long2LongOpenHashMap();

    private static ClientLevel level;
    private static int cellX;
    private static int cellY;
    private static int cellZ;
    private static boolean enabled;
    private static boolean dirty;
    private static boolean unsettled;
    private static int footprint = MAX_FOOTPRINT;
    private static double pUMin;
    private static double pUMax;
    private static double pVMin;
    private static double pVMax;
    private static LevelChunk chunk;
    private static int chunkX;
    private static int chunkZ;
    private static LevelChunkSection section;
    private static int sectionIndex;
    private static long now;
    private static long pruned;
    private static int budget;
    private static long spent;
    private static int generation = 1;
    private static boolean shared;

    private Occlusion() {
    }

    private static final class Voltum {

        static final MethodHandle ACTIVE;
        static final MethodHandle GENERATION;
        static final MethodHandle SETTLED;
        static final MethodHandle SECTION_HIDDEN;
        static final MethodHandle BOX_HIDDEN;

        static {
            MethodHandle active = null;
            MethodHandle generation = null;
            MethodHandle settled = null;
            MethodHandle sectionHidden = null;
            MethodHandle boxHidden = null;
            if (FabricLoader.getInstance().isModLoaded("voltum")) {
                try {
                    Class<?> occlusion = Class.forName("voltum.render.Occlusion");
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    MethodType box = MethodType.methodType(boolean.class, double.class, double.class, double.class, double.class, double.class, double.class);
                    active = lookup.findStatic(occlusion, "active", MethodType.methodType(boolean.class));
                    generation = lookup.findStatic(occlusion, "generation", MethodType.methodType(int.class));
                    settled = lookup.findStatic(occlusion, "settled", MethodType.methodType(boolean.class));
                    sectionHidden = lookup.findStatic(occlusion, "sectionHidden", box);
                    boxHidden = lookup.findStatic(occlusion, "boxHidden", box);
                } catch (ReflectiveOperationException | LinkageError e) {
                    active = null;
                }
            }
            ACTIVE = active;
            GENERATION = generation;
            SETTLED = settled;
            SECTION_HIDDEN = sectionHidden;
            BOX_HIDDEN = boxHidden;
        }

        static boolean active() {
            try {
                return ACTIVE != null && (boolean) ACTIVE.invokeExact();
            } catch (Throwable e) {
                return false;
            }
        }
    }

    public static boolean shared() {
        return shared;
    }

    public static void beginFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        enabled = FifConfig.occlusionActive() && minecraft.level != null;
        boolean wasShared = shared;
        shared = enabled && Voltum.active();
        if (shared) {
            return;
        }
        if (wasShared) {
            clear();
            level = null;
        }
        if (!enabled) {
            clear();
            level = null;
            return;
        }
        long nanos = System.nanoTime();
        now = nanos / 1000000L;
        budget = TESTS_PER_FRAME;
        spent = 0L;
        if (!CHANGED.isEmpty() && now - pruned >= PRUNE_MILLIS) {
            pruned = now;
            if (CHANGED.values().removeIf(time -> now - time >= SETTLE_MILLIS)) {
                dirty = true;
            }
        }
        Vec3 camera = Cameras.position();
        int x = Mth.floor(camera.x);
        int y = Mth.floor(camera.y);
        int z = Mth.floor(camera.z);
        if (minecraft.level != level) {
            clear();
            level = minecraft.level;
            dirty = true;
        }
        if (x != cellX || y != cellY || z != cellZ || dirty) {
            generation++;
            if (BLOCKS.size() > MAX_CACHED) {
                BLOCKS.clear();
            }
            cellX = x;
            cellY = y;
            cellZ = z;
            dirty = false;
            CELL[0] = x;
            CELL[1] = y;
            CELL[2] = z;
        }
        enabled = !level.getBlockState(POS.set(x, y, z)).isSolidRender();
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
        CHANGED.clear();
    }

    public static boolean active() {
        return enabled;
    }

    public static int generation() {
        if (shared) {
            try {
                return (int) Voltum.GENERATION.invokeExact() + (1 << 30);
            } catch (Throwable e) {
                return 0;
            }
        }
        return generation;
    }

    public static boolean settled() {
        if (shared) {
            try {
                return (boolean) Voltum.SETTLED.invokeExact();
            } catch (Throwable e) {
                return false;
            }
        }
        return !unsettled;
    }

    public static boolean sectionHidden(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (shared) {
            try {
                return (boolean) Voltum.SECTION_HIDDEN.invokeExact(minX, minY, minZ, maxX, maxY, maxZ);
            } catch (Throwable e) {
                return false;
            }
        }
        unsettled = false;
        if (!spend()) {
            unsettled = true;
            return false;
        }
        footprint = MAX_SECTION_FOOTPRINT;
        byte result = test(minX, minY, minZ, maxX, maxY, maxZ);
        footprint = MAX_FOOTPRINT;
        return result == HIDDEN;
    }

    public static boolean blockHidden(BlockPos pos, double margin) {
        return blockHidden(pos, -margin, -margin, -margin, 1 + margin, 1 + margin, 1 + margin);
    }

    public static boolean blockHidden(BlockPos pos, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (shared) {
            try {
                return (boolean) Voltum.BOX_HIDDEN.invokeExact(pos.getX() + minX, pos.getY() + minY, pos.getZ() + minZ, pos.getX() + maxX, pos.getY() + maxY,
                        pos.getZ() + maxZ);
            } catch (Throwable e) {
                return false;
            }
        }
        long key = pos.asLong();
        int cached = BLOCKS.get(key);
        unsettled = false;
        if (cached != 0 && cached >>> 2 == generation) {
            return (cached & 3) == HIDDEN;
        }
        if (cached != 0 && (cached & 3) == VISIBLE && budget < TESTS_PER_FRAME / 2 || !spend()) {
            unsettled = true;
            return false;
        }
        byte result = test(pos.getX() + minX, pos.getY() + minY, pos.getZ() + minZ, pos.getX() + maxX, pos.getY() + maxY, pos.getZ() + maxZ);
        BLOCKS.put(key, generation << 2 | (unsettled ? VISIBLE : result));
        return result == HIDDEN;
    }

    private static boolean spend() {
        if (budget <= 0) {
            return false;
        }
        if (spent > NANOS_PER_FRAME) {
            budget = 0;
            return false;
        }
        budget--;
        return true;
    }

    private static byte test(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        long start = System.nanoTime();
        chunk = null;
        section = null;
        BOX[0] = minX;
        BOX[1] = minY;
        BOX[2] = minZ;
        BOX[3] = maxX;
        BOX[4] = maxY;
        BOX[5] = maxZ;
        byte result = VISIBLE;
        for (int axis = 0; axis < 3; axis++) {
            if (slabs(axis, CELL)) {
                result = HIDDEN;
                break;
            }
        }
        spent += System.nanoTime() - start;
        return result;
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
        int cu = cell[u];
        int cv = cell[v];
        double bu0 = BOX[u];
        double bu1 = BOX[u + 3];
        double bv0 = BOX[v];
        double bv1 = BOX[v + 3];
        INV[0] = 1.0 / (bMin - cMin);
        INV[1] = 1.0 / (bMax - cMin);
        INV[2] = 1.0 / (bMin - cMax);
        INV[3] = 1.0 / (bMax - cMax);
        project(first, cMin, cMax, bMin, bMax, cu, cv, bu0, bu1, bv0, bv1);
        double loUMin = pUMin;
        double loUMax = pUMax;
        double loVMin = pVMin;
        double loVMax = pVMax;
        project(first + 1, cMin, cMax, bMin, bMax, cu, cv, bu0, bu1, bv0, bv1);
        double hiUMin = pUMin;
        double hiUMax = pUMax;
        double hiVMin = pVMin;
        double hiVMax = pVMax;
        int count = 0;
        for (int k = first; count < MAX_SLABS; k += step) {
            count++;
            int u0 = Mth.floor(Math.min(loUMin, hiUMin) - 1.0E-4);
            int u1 = Mth.floor(Math.max(loUMax, hiUMax) + 1.0E-4);
            int v0 = Mth.floor(Math.min(loVMin, hiVMin) - 1.0E-4);
            int v1 = Mth.floor(Math.max(loVMax, hiVMax) + 1.0E-4);
            if ((u1 - u0 + 1) * (v1 - v0 + 1) <= footprint && opaque(d, k, u, u0, u1, v, v0, v1)) {
                return true;
            }
            if (k == last || count >= MAX_SLABS) {
                break;
            }
            if (step > 0) {
                loUMin = hiUMin;
                loUMax = hiUMax;
                loVMin = hiVMin;
                loVMax = hiVMax;
                project(k + 2, cMin, cMax, bMin, bMax, cu, cv, bu0, bu1, bv0, bv1);
                hiUMin = pUMin;
                hiUMax = pUMax;
                hiVMin = pVMin;
                hiVMax = pVMax;
            } else {
                hiUMin = loUMin;
                hiUMax = loUMax;
                hiVMin = loVMin;
                hiVMax = loVMax;
                project(k - 1, cMin, cMax, bMin, bMax, cu, cv, bu0, bu1, bv0, bv1);
                loUMin = pUMin;
                loUMax = pUMax;
                loVMin = pVMin;
                loVMax = pVMax;
            }
        }
        return false;
    }

    private static void project(int plane, double cMin, double cMax, double bMin, double bMax, int cu, int cv, double bu0, double bu1, double bv0, double bv1) {
        double t0 = (plane - cMin) * INV[0];
        double t1 = (plane - cMin) * INV[1];
        double t2 = (plane - cMax) * INV[2];
        double t3 = (plane - cMax) * INV[3];
        double tMin = Math.min(Math.min(t0, t1), Math.min(t2, t3));
        double tMax = Math.max(Math.max(t0, t1), Math.max(t2, t3));
        double du0 = bu0 - cu;
        double du1 = bu1 - (cu + 1);
        double dv0 = bv0 - cv;
        double dv1 = bv1 - (cv + 1);
        double uMin = cu + (du0 < 0 ? tMax : tMin) * du0;
        double uMax = (cu + 1) + (du1 > 0 ? tMax : tMin) * du1;
        double vMin = cv + (dv0 < 0 ? tMax : tMin) * dv0;
        double vMax = (cv + 1) + (dv1 > 0 ? tMax : tMin) * dv1;
        pUMin = uMin;
        pUMax = uMax;
        pVMin = vMin;
        pVMax = vMax;
    }

    private static boolean opaque(int d, int k, int u, int u0, int u1, int v, int v0, int v1) {
        int[] p = PROBE;
        p[d] = k;
        for (int a = u0; a <= u1; a++) {
            p[u] = a;
            for (int b = v0; b <= v1; b++) {
                p[v] = b;
                if (!CHANGED.isEmpty() && CHANGED.containsKey(SectionPos.asLong(p[0] >> 4, p[1] >> 4, p[2] >> 4))) {
                    unsettled = true;
                    return false;
                }
                if (!solid(p[0], p[1], p[2])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean solid(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (chunk == null || cx != chunkX || cz != chunkZ) {
            chunk = level.getChunk(cx, cz);
            chunkX = cx;
            chunkZ = cz;
            sectionIndex = Integer.MIN_VALUE;
        }
        int index = chunk.getSectionIndex(y);
        if (index != sectionIndex) {
            sectionIndex = index;
            LevelChunkSection[] sections = chunk.getSections();
            section = index >= 0 && index < sections.length ? sections[index] : null;
        }
        return section != null && !section.hasOnlyAir() && section.getBlockState(x & 15, y & 15, z & 15).isSolidRender();
    }
}
