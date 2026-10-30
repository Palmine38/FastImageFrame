package fastimageframes.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
//#if MC >= 1.21.5
import com.mojang.blaze3d.systems.RenderSystem;
//#else
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
//#endif
//#if MC >= 26.3
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
//#elif MC >= 26.2
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
//#elif MC >= 1.21.11
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.TextureFormat;
//#elif MC >= 1.21.5
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
//#endif

public class MipmappedTexture extends DynamicTexture {

    private int levels = 1;

    protected MipmappedTexture(String label, int width, int height, int levels) {
        //#if MC >= 1.21.5
        super(label, width, height, true);
        //#else
        super(width, height, true);
        //#endif
        allocate(label, width, height, levels);
    }

    protected MipmappedTexture(String label, NativeImage image, NativeImage[] mipmaps) {
        //#if MC >= 1.21.5
        super(() -> label, image);
        //#else
        super(image);
        //#endif
        if (mipmaps != null && mipmaps.length > 0) {
            allocate(label, image.getWidth(), image.getHeight(), mipmaps.length + 1);
            writeLevel(image, 0, 0, 0);
            for (int i = 0; i < mipmaps.length; i++) {
                writeLevel(mipmaps[i], i + 1, 0, 0);
            }
        }
    }

    private void allocate(String label, int width, int height, int levels) {
        if (levels <= 1) {
            return;
        }
        this.levels = levels;
        //#if MC >= 1.21.6
        texture.close();
        textureView.close();
        //#if MC >= 26.2
        texture = RenderSystem.getDevice().createTexture(label, 5, GpuFormat.RGBA8_UNORM, width, height, 1, levels);
        //#else
        texture = RenderSystem.getDevice().createTexture(label, 5, TextureFormat.RGBA8, width, height, 1, levels);
        //#endif
        //#if MC < 1.21.11
        texture.setTextureFilter(FilterMode.LINEAR, FilterMode.NEAREST, true);
        //#endif
        textureView = RenderSystem.getDevice().createTextureView(texture);
        //#elif MC >= 1.21.5
        texture.close();
        texture = RenderSystem.getDevice().createTexture(label, TextureFormat.RGBA8, width, height, levels);
        texture.setTextureFilter(FilterMode.LINEAR, FilterMode.NEAREST, true);
        //#else
        TextureUtil.prepareImage(getId(), levels - 1, width, height);
        setFilter(false, true);
        //#endif
    }

    public int levels() {
        return levels;
    }

    public void writeLevel(NativeImage image, int level, int x, int y) {
        //#if MC >= 26.2
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), image, level, 0, x, y);
        //#elif MC >= 1.21.6
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), image, level, 0, x, y, image.getWidth(), image.getHeight(), 0, 0);
        //#elif MC >= 1.21.5
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), image, level, x, y, image.getWidth(), image.getHeight(), 0, 0);
        //#else
        bind();
        image.upload(level, x, y, 0, 0, image.getWidth(), image.getHeight(), false);
        //#endif
    }

    //#if MC >= 1.21.11
    @Override
    public GpuSampler getSampler() {
        return levels > 1 ? RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.NEAREST, true)
                : super.getSampler();
    }
    //#elif MC >= 1.21.6
    @Override
    public void setUseMipmaps(boolean mipmaps) {
        if (levels > 1) {
            getTexture().setTextureFilter(FilterMode.LINEAR, FilterMode.NEAREST, true);
        } else {
            super.setUseMipmaps(mipmaps);
        }
    }
    //#elif MC >= 1.21.5
    @Override
    public void setFilter(boolean blur, boolean mipmaps) {
        if (levels > 1) {
            getTexture().setTextureFilter(FilterMode.LINEAR, FilterMode.NEAREST, true);
        } else {
            super.setFilter(blur, mipmaps);
        }
    }
    //#else
    @Override
    public void setFilter(boolean blur, boolean mipmaps) {
        if (levels > 1) {
            bind();
            GlStateManager._texParameter(3553, 10241, 9987);
            GlStateManager._texParameter(3553, 10240, 9728);
        } else {
            super.setFilter(blur, mipmaps);
        }
    }
    //#endif

    public static int levelsFor(int width, int height, int smallest) {
        int levels = 1;
        for (int size = Math.min(width, height); size >= smallest * 2; size >>= 1) {
            levels++;
        }
        return levels;
    }

    public static NativeImage[] mipmaps(NativeImage base, int levels) {
        NativeImage[] mipmaps = new NativeImage[Math.max(0, levels - 1)];
        int width = base.getWidth();
        int height = base.getHeight();
        for (int i = 0; i < mipmaps.length; i++) {
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
            mipmaps[i] = new NativeImage(width, height, false);
        }
        fill(base, mipmaps);
        return mipmaps;
    }

    public static void fill(NativeImage base, NativeImage[] mipmaps) {
        int[] source = base.getPixels();
        int sourceWidth = base.getWidth();
        int sourceHeight = base.getHeight();
        for (NativeImage mipmap : mipmaps) {
            int width = mipmap.getWidth();
            int height = mipmap.getHeight();
            int[] target = new int[width * height];
            for (int y = 0; y < height; y++) {
                int row0 = Math.min(y * 2, sourceHeight - 1) * sourceWidth;
                int row1 = Math.min(y * 2 + 1, sourceHeight - 1) * sourceWidth;
                for (int x = 0; x < width; x++) {
                    int x0 = Math.min(x * 2, sourceWidth - 1);
                    int x1 = Math.min(x * 2 + 1, sourceWidth - 1);
                    int pixel = average(source[row0 + x0], source[row0 + x1], source[row1 + x0], source[row1 + x1]);
                    target[y * width + x] = pixel;
                    mipmap.setPixel(x, y, pixel);
                }
            }
            source = target;
            sourceWidth = width;
            sourceHeight = height;
        }
    }

    private static int average(int a, int b, int c, int d) {
        int aa = a >>> 24;
        int ba = b >>> 24;
        int ca = c >>> 24;
        int da = d >>> 24;
        int alpha = aa + ba + ca + da;
        if (alpha == 0) {
            return 0;
        }
        int red = ((a >> 16 & 0xFF) * aa + (b >> 16 & 0xFF) * ba + (c >> 16 & 0xFF) * ca + (d >> 16 & 0xFF) * da) / alpha;
        int green = ((a >> 8 & 0xFF) * aa + (b >> 8 & 0xFF) * ba + (c >> 8 & 0xFF) * ca + (d >> 8 & 0xFF) * da) / alpha;
        int blue = ((a & 0xFF) * aa + (b & 0xFF) * ba + (c & 0xFF) * ca + (d & 0xFF) * da) / alpha;
        return (alpha + 2) / 4 << 24 | red << 16 | green << 8 | blue;
    }

    public static void close(NativeImage[] mipmaps) {
        if (mipmaps != null) {
            for (NativeImage mipmap : mipmaps) {
                mipmap.close();
            }
        }
    }

}
