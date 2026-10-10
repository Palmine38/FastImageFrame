package fastimageframes.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//#if MC >= 26.3
import com.mojang.renderpearl.api.vertex.VertexFormat;
//#else
import com.mojang.blaze3d.vertex.VertexFormat;
//#endif
import fastimageframes.render.BulkVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements BulkVertexBuffer {

    @Shadow
    @Final
    private ByteBufferBuilder buffer;

    @Shadow
    private long vertexPointer;

    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    private int vertices;

    @Shadow
    @Final
    private int vertexSize;

    @Shadow
    private int elementsToFill;

    @Shadow
    private void endLastVertex() {
        throw new AssertionError();
    }

    @Override
    public int fif$vertexSize() {
        return vertexSize;
    }

    @Override
    public long fif$vertexEnd() {
        return vertexPointer == -1L ? -1L : vertexPointer + vertexSize;
    }

    @Override
    public Object fif$format() {
        return format;
    }

    @Override
    public long fif$reserve(int count) {
        endLastVertex();
        long address = buffer.reserve(count * vertexSize);
        vertices += count;
        vertexPointer = address + (long) (count - 1) * vertexSize;
        elementsToFill = 0;
        return address;
    }

}
