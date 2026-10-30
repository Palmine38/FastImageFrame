package fastimageframes.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import fastimageframes.render.MipmappedTexture;

final class AtlasPageTexture extends MipmappedTexture {

    AtlasPageTexture(String label, int size, int levels) {
        super(label, size, size, levels);
        setPixels(new NativeImage(1, 1, false));
    }

    void write(NativeImage image, int x, int y) {
        writeLevel(image, 0, x, y);
    }

}
