package fastimageframes.render;

public interface BulkVertexBuffer {

    int fif$vertexSize();

    long fif$vertexEnd();

    long fif$reserve(int count);

    Object fif$format();

}
