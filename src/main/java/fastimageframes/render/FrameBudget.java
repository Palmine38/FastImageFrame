package fastimageframes.render;

import net.fabricmc.loader.api.FabricLoader;

public final class FrameBudget {

    public static final String KEY = "voltum:frame_budget";

    private static final long BUDGET_NANOS = 2000000L;
    private static final long SAME_FRAME_NANOS = 200000L;
    private static final long[] SHARED = shared();

    private FrameBudget() {
    }

    private static long[] shared() {
        long[] own = new long[2];
        try {
            Object existing = FabricLoader.getInstance().getObjectShare().putIfAbsent(KEY, own);
            return existing instanceof long[] array && array.length >= 2 ? array : own;
        } catch (RuntimeException | LinkageError e) {
            return own;
        }
    }

    public static void beginFrame() {
        long now = System.nanoTime();
        if (now - SHARED[0] > SAME_FRAME_NANOS) {
            SHARED[0] = now;
            SHARED[1] = now + BUDGET_NANOS;
        }
    }

    public static long deadline() {
        return SHARED[1];
    }
}
