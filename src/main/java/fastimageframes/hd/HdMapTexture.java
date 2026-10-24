package fastimageframes.hd;

import com.mojang.blaze3d.platform.NativeImage;
import fastimageframes.render.MipmappedTexture;

final class HdMapTexture extends MipmappedTexture {

    private final long sizeBytes;

    HdMapTexture(String label, NativeImage image, NativeImage[] mipmaps) {
        super(label, image, mipmaps);
        long bytes = (long) image.getWidth() * image.getHeight() * 4L;
        if (mipmaps != null) {
            for (NativeImage mipmap : mipmaps) {
                bytes += (long) mipmap.getWidth() * mipmap.getHeight() * 4L;
            }
        }
        this.sizeBytes = bytes;
        setPixels(new NativeImage(1, 1, false));
    }

    long getSizeBytes() {
        return sizeBytes;
    }

}
