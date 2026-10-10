package fastimageframes.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

public final class VertexRecorder implements VertexConsumer {

    public static final int STRIDE = 11;

    private static long staging;
    private static int stagingSize;

    public final FloatArrayList data = new FloatArrayList();
    public boolean push = true;
    private boolean normals;
    private int vertex = -STRIDE;
    private byte[] packed;
    private float[] transformed;
    private int packedVertexSize;

    public void clear() {
        data.clear();
        vertex = -STRIDE;
        packed = null;
        normals = false;
    }

    public void truncate(int size) {
        data.size(size);
        vertex = size - STRIDE;
        packed = null;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        vertex = data.size();
        if (vertex + STRIDE > data.elements().length) {
            data.ensureCapacity(Math.max(vertex + STRIDE, data.elements().length * 2));
        }
        data.size(vertex + STRIDE);
        float[] a = data.elements();
        a[vertex] = x;
        a[vertex + 1] = y;
        a[vertex + 2] = z;
        a[vertex + 3] = Float.intBitsToFloat(-1);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        return set(3, Float.intBitsToFloat(alpha << 24 | red << 16 | green << 8 | blue));
    }

    //#if MC >= 1.21.11
    @Override
    public VertexConsumer setColor(int color) {
        return set(3, Float.intBitsToFloat(color));
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }
    //#endif

    @Override
    public VertexConsumer setUv(float u, float v) {
        set(4, u);
        return set(5, v);
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return set(6, Float.intBitsToFloat(u & 0xFFFF | v << 16));
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return set(7, Float.intBitsToFloat(u & 0xFFFF | v << 16));
    }

    //#if MC >= 26.3
    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }
    //#endif

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        normals = true;
        set(8, x);
        set(9, y);
        return set(10, z);
    }

    private VertexConsumer set(int offset, float value) {
        data.elements()[vertex + offset] = value;
        return this;
    }

    public int append(float[] vertices) {
        int start = data.size();
        data.addElements(start, vertices);
        vertex = data.size() - STRIDE;
        packed = null;
        return start / STRIDE;
    }

    public void write(int first, float[] vertices) {
        System.arraycopy(vertices, 0, data.elements(), first * STRIDE, vertices.length);
        packed = null;
    }

    public void collapse(int first, int count) {
        float[] a = data.elements();
        int origin = first * STRIDE;
        for (int i = 1; i < count; i++) {
            int v = origin + i * STRIDE;
            a[v] = a[origin];
            a[v + 1] = a[origin + 1];
            a[v + 2] = a[origin + 2];
        }
        packed = null;
    }

    public void setUvs(int first, boolean[] high, float u0, float v0, float u1, float v1) {
        float[] a = data.elements();
        for (int i = 0; i < high.length / 2; i++) {
            int v = (first + i) * STRIDE;
            a[v + 4] = high[i * 2] ? u1 : u0;
            a[v + 5] = high[i * 2 + 1] ? v1 : v0;
        }
        packed = null;
    }

    public void replay(VertexConsumer out, float ox, float oy, float oz) {
        int count = data.size() / STRIDE;
        if (count == 0) {
            return;
        }
        if (out instanceof BulkVertexBuffer bulk && (normals && push || !Shaders.active())) {
            int size = bulk.fif$vertexSize();
            if (packed != null && packedVertexSize == size) {
                if (Shaders.active()) {
                    if (staging == 0L || stagingSize < packed.length) {
                        MemoryUtil.nmemFree(staging);
                        stagingSize = Math.max(packed.length, stagingSize * 2);
                        staging = MemoryUtil.nmemAlloc(stagingSize);
                    }
                    copy(staging, size, count, ox, oy, oz);
                    if (SodiumPush.pushRaw(out, staging, count, bulk.fif$format())) {
                        return;
                    }
                    packed = null;
                    write(out, ox, oy, oz);
                    return;
                }
                copy(bulk.fif$reserve(count), size, count, ox, oy, oz);
                return;
            }
            write(out, ox, oy, oz);
            pack(bulk.fif$vertexEnd(), size, count, ox, oy, oz);
            return;
        }
        write(out, ox, oy, oz);
    }

    private void write(VertexConsumer out, float ox, float oy, float oz) {
        if (!push || !normals || !SodiumPush.push(out, data.elements(), data.size(), STRIDE, ox, oy, oz)) {
            writeEach(out, data.elements(), data.size(), ox, oy, oz);
        }
    }

    public void replay(VertexConsumer out, Matrix4f pose, Matrix3f normal, float ox, float oy, float oz) {
        int size = data.size();
        if (size == 0) {
            return;
        }
        if (transformed == null || transformed.length < size) {
            transformed = new float[data.elements().length];
        }
        float[] a = data.elements();
        float[] t = transformed;
        Vector3f v = new Vector3f();
        for (int i = 0; i < size; i += STRIDE) {
            pose.transformPosition(v.set(a[i] + ox, a[i + 1] + oy, a[i + 2] + oz));
            t[i] = v.x;
            t[i + 1] = v.y;
            t[i + 2] = v.z;
            System.arraycopy(a, i + 3, t, i + 3, 5);
            normal.transform(v.set(a[i + 8], a[i + 9], a[i + 10])).normalize();
            t[i + 8] = v.x;
            t[i + 9] = v.y;
            t[i + 10] = v.z;
        }
        if (!push || !normals || !SodiumPush.push(out, t, size, STRIDE, 0.0F, 0.0F, 0.0F)) {
            writeEach(out, t, size, 0.0F, 0.0F, 0.0F);
        }
    }

    private void writeEach(VertexConsumer out, float[] a, int size, float ox, float oy, float oz) {
        if (!normals) {
            for (int i = 0; i < size; i += STRIDE) {
                int overlay = Float.floatToRawIntBits(a[i + 6]);
                int light = Float.floatToRawIntBits(a[i + 7]);
                out.addVertex(a[i] + ox, a[i + 1] + oy, a[i + 2] + oz).setColor(Float.floatToRawIntBits(a[i + 3])).setUv(a[i + 4], a[i + 5])
                        .setUv1(overlay & 0xFFFF, overlay >>> 16).setUv2(light & 0xFFFF, light >>> 16);
            }
            return;
        }
        for (int i = 0; i < size; i += STRIDE) {
            out.addVertex(a[i] + ox, a[i + 1] + oy, a[i + 2] + oz, Float.floatToRawIntBits(a[i + 3]), a[i + 4], a[i + 5],
                    Float.floatToRawIntBits(a[i + 6]), Float.floatToRawIntBits(a[i + 7]), a[i + 8], a[i + 9], a[i + 10]);
        }
    }

    private void pack(long end, int size, int count, float ox, float oy, float oz) {
        if (end == -1L || size < 12) {
            return;
        }
        int bytes = count * size;
        long start = end - bytes;
        float[] a = data.elements();
        if (MemoryUtil.memGetFloat(start) != a[0] + ox || MemoryUtil.memGetFloat(start + 4) != a[1] + oy
                || MemoryUtil.memGetFloat(start + 8) != a[2] + oz) {
            return;
        }
        byte[] copy = new byte[bytes];
        MemoryUtil.memByteBuffer(start, bytes).get(copy);
        packed = copy;
        packedVertexSize = size;
    }

    private void copy(long address, int size, int count, float ox, float oy, float oz) {
        MemoryUtil.memByteBuffer(address, packed.length).put(packed);
        float[] a = data.elements();
        long p = address;
        for (int i = 0; i < count; i++) {
            int v = i * STRIDE;
            MemoryUtil.memPutFloat(p, a[v] + ox);
            MemoryUtil.memPutFloat(p + 4, a[v + 1] + oy);
            MemoryUtil.memPutFloat(p + 8, a[v + 2] + oz);
            p += size;
        }
    }

}
