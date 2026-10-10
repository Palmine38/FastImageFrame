package fastimageframes.atlas;

import com.mojang.blaze3d.platform.NativeImage;
//#if MC >= 1.21.5
import com.mojang.blaze3d.systems.RenderSystem;
//#endif
import net.minecraft.client.renderer.texture.DynamicTexture;

final class AtlasPageTexture extends DynamicTexture {

    AtlasPageTexture(String label, int size) {
        //#if MC >= 1.21.5
        super(label, size, size, true);
        //#else
        super(size, size, true);
        //#endif
        setPixels(new NativeImage(1, 1, false));
    }

    void write(NativeImage image, int x, int y) {
        //#if MC >= 26.2
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), image, 0, 0, x, y);
        //#elif MC >= 1.21.6
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), image, 0, 0, x, y, image.getWidth(), image.getHeight(), 0, 0);
        //#elif MC >= 1.21.5
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), image, 0, x, y, image.getWidth(), image.getHeight(), 0, 0);
        //#else
        bind();
        image.upload(0, x, y, 0, 0, image.getWidth(), image.getHeight(), false);
        //#endif
    }

}
