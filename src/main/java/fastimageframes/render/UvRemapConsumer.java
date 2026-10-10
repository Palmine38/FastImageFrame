package fastimageframes.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import fastimageframes.atlas.MapAtlas;

public final class UvRemapConsumer implements VertexConsumer {

    public static final UvRemapConsumer INSTANCE = new UvRemapConsumer();

    private VertexConsumer delegate;
    private float u0;
    private float v0;
    private float du;
    private float dv;

    private UvRemapConsumer() {
    }

    public VertexConsumer wrap(VertexConsumer delegate, MapAtlas.Slot slot) {
        this.delegate = delegate;
        this.u0 = slot.u0;
        this.v0 = slot.v0;
        this.du = slot.u1 - slot.u0;
        this.dv = slot.v1 - slot.v0;
        return this;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red, green, blue, alpha);
        return this;
    }

    //#if MC >= 1.21.11
    @Override
    public VertexConsumer setColor(int color) {
        delegate.setColor(color);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        delegate.setLineWidth(width);
        return this;
    }
    //#endif

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u0 + u * du, v0 + v * dv);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    //#if MC >= 26.3
    @Override
    public VertexConsumer setUv3(float u, float v) {
        delegate.setUv3(u, v);
        return this;
    }
    //#endif

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }

}
