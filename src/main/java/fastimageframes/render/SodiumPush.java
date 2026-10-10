package fastimageframes.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public final class SodiumPush {

    private static final int ENTITY_SIZE = 36;
    private static final int BATCH = 256;

    private static boolean tried;
    private static MethodHandle tryOf;
    private static MethodHandle push;
    private static long scratch;

    private SodiumPush() {
    }

    private static boolean init() {
        if (!tried) {
            tried = true;
            try {
                Class<?> writer = Class.forName("net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                for (java.lang.reflect.Method method : writer.getMethods()) {
                    if (method.getName().equals("tryOf") && method.getParameterCount() == 1) {
                        tryOf = lookup.unreflect(method);
                    } else if (method.getName().equals("push") && method.getParameterCount() == 4) {
                        push = lookup.unreflect(method);
                    }
                }
                if (tryOf != null && push != null) {
                    scratch = MemoryUtil.nmemAlloc((long) BATCH * ENTITY_SIZE);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                tryOf = null;
            }
        }
        return tryOf != null && push != null;
    }

    public static boolean pushRaw(VertexConsumer out, long address, int count, Object format) {
        if (!init()) {
            return false;
        }
        try {
            Object writer = tryOf.invoke(out);
            if (writer == null) {
                return false;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                push.invoke(writer, stack, address, count, format);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean push(VertexConsumer out, float[] a, int floats, int stride, float ox, float oy, float oz) {
        if (!init()) {
            return false;
        }
        Object writer;
        try {
            writer = tryOf.invoke(out);
        } catch (Throwable e) {
            return false;
        }
        if (writer == null) {
            return false;
        }
        //#if MC >= 26.1
        Object format = DefaultVertexFormat.ENTITY;
        //#else
        Object format = DefaultVertexFormat.NEW_ENTITY;
        //#endif
        int count = floats / stride;
        int done = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            while (done < count) {
                int batch = Math.min(BATCH, count - done);
                long p = scratch;
                for (int i = 0; i < batch; i++) {
                    int v = (done + i) * stride;
                    MemoryUtil.memPutFloat(p, a[v] + ox);
                    MemoryUtil.memPutFloat(p + 4, a[v + 1] + oy);
                    MemoryUtil.memPutFloat(p + 8, a[v + 2] + oz);
                    int argb = Float.floatToRawIntBits(a[v + 3]);
                    MemoryUtil.memPutInt(p + 12, argb & 0xFF00FF00 | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF);
                    MemoryUtil.memPutFloat(p + 16, a[v + 4]);
                    MemoryUtil.memPutFloat(p + 20, a[v + 5]);
                    MemoryUtil.memPutInt(p + 24, Float.floatToRawIntBits(a[v + 6]));
                    MemoryUtil.memPutInt(p + 28, Float.floatToRawIntBits(a[v + 7]));
                    MemoryUtil.memPutInt(p + 32, normal(a[v + 8]) | normal(a[v + 9]) << 8 | normal(a[v + 10]) << 16);
                    p += ENTITY_SIZE;
                }
                push.invoke(writer, stack, scratch, batch, format);
                done += batch;
            }
        } catch (Throwable e) {
            return done > 0;
        }
        return true;
    }

    private static int normal(float value) {
        return (int) (Math.clamp(value, -1.0F, 1.0F) * 127.0F) & 0xFF;
    }
}
