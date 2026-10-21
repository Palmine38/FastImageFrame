package fastimageframes.render;

public final class FogRange {

    private static final double MAX_SHIFT = 0.5;
    private static final double MIN_SHIFT = 1.0 / 64.0;
    private static final double SHIFT_PER_BLOCK = 0.003;

    private static float environmentalStart = -160.0F;
    private static float environmentalEnd = 1024.0F;
    private static float renderDistanceStart = Float.MAX_VALUE;
    private static float renderDistanceEnd = Float.MAX_VALUE;

    private FogRange() {
    }

    public static void set(float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd) {
        FogRange.environmentalStart = environmentalStart;
        FogRange.environmentalEnd = environmentalEnd;
        FogRange.renderDistanceStart = renderDistanceStart;
        FogRange.renderDistanceEnd = renderDistanceEnd;
    }

    public static double shift(double farthest) {
        double shift = environmentalEnd > environmentalStart ? Math.min(MAX_SHIFT, SHIFT_PER_BLOCK * ((double) environmentalEnd - environmentalStart)) : MAX_SHIFT;
        if (farthest > (double) renderDistanceStart - 1.0) {
            shift = Math.min(shift, SHIFT_PER_BLOCK * ((double) renderDistanceEnd - renderDistanceStart));
        }
        return Math.max(MIN_SHIFT, shift);
    }
}
