package fastimageframes.hd;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

final class HdMapTexture extends DynamicTexture {

    private final long sizeBytes;

    HdMapTexture(String label, NativeImage image) {
        //#if MC >= 1.21.5
        super(() -> label, image);
        //#else
        super(image);
        //#endif
        this.sizeBytes = (long) image.getWidth() * image.getHeight() * 4L;
        setPixels(new NativeImage(1, 1, false));
    }

    long getSizeBytes() {
        return sizeBytes;
    }

}
