package fastimageframes.render;

import fastimageframes.bake.BakedFrames;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class FrameNeighbors {

    private static final long ABSENT = Long.MIN_VALUE;
    private static final int PURGE_EVERY_FRAMES = 256;

    private static final Long2LongOpenHashMap frames = new Long2LongOpenHashMap();
    private static long frame;

    static {
        frames.defaultReturnValue(ABSENT);
    }

    private FrameNeighbors() {
    }

    public static void onFrameStart() {
        frame++;
        if (frame % PURGE_EVERY_FRAMES == 0) {
            ObjectIterator<Long2LongMap.Entry> itr = frames.long2LongEntrySet().fastIterator();
            while (itr.hasNext()) {
                if (frame - (itr.next().getLongValue() >>> 3) > 1) {
                    itr.remove();
                }
            }
        }
    }

    public static void add(BlockPos pos, Direction facing) {
        frames.put(pos.asLong(), frame << 3 | facing.ordinal());
    }

    public static boolean has(long pos, Direction facing) {
        long value = frames.get(pos);
        if (value != ABSENT && (value & 7) == facing.ordinal() && frame - (value >>> 3) <= 1) {
            return true;
        }
        return BakedFrames.INSTANCE.hasMapFrame(pos, facing.ordinal());
    }

}
