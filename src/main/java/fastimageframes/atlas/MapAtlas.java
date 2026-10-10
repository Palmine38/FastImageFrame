package fastimageframes.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import fastimageframes.FifConfig;
import fastimageframes.hd.HdMaps;
import fastimageframes.ifc.IfcLink;
import fastimageframes.mixin.TextureManagerAccessor;
import fastimageframes.render.FifLayers;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

public final class MapAtlas {

    public static final MapAtlas INSTANCE = new MapAtlas();

    public static final int TILE = 128;
    private static final String MAP_TEXTURE_PREFIX = "map/";
    private static final String IMMEDIATELY_FAST = "immediatelyfast";
    private static final Class<?> IMMEDIATELY_FAST_STATE = immediatelyFastClass("injection.interfaces.IMapRenderState");
    private static final MethodHandle CLEAR_IMMEDIATELY_FAST_ATLAS = clearImmediatelyFastAtlasHandle();

    public final class Page {
        final int size;
        final int perRow;
        final Identifier id;
        final AtlasPageTexture texture;
        final RenderType layer;
        final IntArrayList freeSlots = new IntArrayList();

        Page(int index, int size) {
            this.size = size;
            this.perRow = size / TILE;
            this.id = Identifier.fromNamespaceAndPath("fastimageframes", "map_atlas/" + generation + "_" + index);
            this.texture = new AtlasPageTexture(id.toString(), size);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            this.layer = RenderTypes.text(id);
            FifLayers.registerBatched(layer, 64 * 1024);
            for (int i = perRow * perRow - 1; i >= 0; i--) {
                freeSlots.add(i);
            }
        }

        void close() {
            FifLayers.unregisterBatched(layer);
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    public static final class Slot {
        final Page page;
        final int index;
        final int x;
        final int y;
        boolean overridden;
        boolean fromColors;
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;

        Slot(Page page, int index) {
            this.page = page;
            this.index = index;
            this.x = (index % page.perRow) * TILE;
            this.y = (index / page.perRow) * TILE;
            float inset = 1F / (page.size * 64F);
            this.u0 = x / (float) page.size + inset;
            this.v0 = y / (float) page.size + inset;
            this.u1 = (x + TILE) / (float) page.size - inset;
            this.v1 = (y + TILE) / (float) page.size - inset;
        }

        public RenderType layer() {
            return page.layer;
        }
    }

    private final List<Page> pages = new ArrayList<>();
    private final Object2ObjectOpenHashMap<Identifier, Slot> slots = new Object2ObjectOpenHashMap<>();
    private final IntSet overrides = new IntOpenHashSet();
    private final Int2ObjectMap<NativeImage> pendingOverrides = new Int2ObjectOpenHashMap<>();
    private final IntSet transparentMaps = new IntOpenHashSet();
    private NativeImage scratch;
    private NativeImage colorImage;
    private int generation;
    private int pageSize = FifConfig.atlasSize.getPixels();

    private MapAtlas() {
    }

    public Slot slotFor(MapRenderState state) {
        Identifier texture = state.texture;
        if (texture == null || !IMMEDIATELY_FAST.equals(texture.getNamespace())) {
            releaseImmediatelyFast(state);
            return getSlot(texture);
        }
        int mapId = ((MapRenderStateData) state).fif$mapId();
        if (mapId < 0) {
            return null;
        }
        Identifier hd = FifConfig.hdActive() ? HdMaps.INSTANCE.loadedTexture(mapId) : IfcLink.loadedHdTexture(mapId);
        if (hd != null) {
            state.texture = hd;
            releaseImmediatelyFast(state);
            return null;
        }
        Identifier own = mapTextureId(mapId);
        Slot slot = getSlot(own);
        if (slot != null) {
            state.texture = own;
            releaseImmediatelyFast(state);
        }
        return slot;
    }

    public static void releaseImmediatelyFast(MapRenderState state) {
        if (CLEAR_IMMEDIATELY_FAST_ATLAS == null || !IMMEDIATELY_FAST_STATE.isInstance(state) || (state.texture != null && IMMEDIATELY_FAST.equals(state.texture.getNamespace()))) {
            return;
        }
        try {
            CLEAR_IMMEDIATELY_FAST_ATLAS.invokeExact(state);
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> immediatelyFastClass(String name) {
        if (!FabricLoader.getInstance().isModLoaded(IMMEDIATELY_FAST)) {
            return null;
        }
        try {
            return Class.forName("net.raphimc.immediatelyfast." + name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static MethodHandle clearImmediatelyFastAtlasHandle() {
        Class<?> atlasTexture = immediatelyFastClass("feature.map_atlas_generation.MapAtlasTexture");
        if (IMMEDIATELY_FAST_STATE == null || atlasTexture == null) {
            return null;
        }
        try {
            MethodHandle setter = MethodHandles.publicLookup().findVirtual(IMMEDIATELY_FAST_STATE, "immediatelyFast$setAtlasTexture", MethodType.methodType(void.class, atlasTexture));
            return MethodHandles.insertArguments(setter, 1, (Object) null).asType(MethodType.methodType(void.class, MapRenderState.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    public Slot getSlot(Identifier texture) {
        Slot slot = slots.get(texture);
        if (slot != null) {
            return slot;
        }
        int mapId = parseMapId(texture);
        if (mapId < 0) {
            return null;
        }
        NativeImage source = vanillaImage(texture);
        boolean fromColors = source == null;
        if (fromColors) {
            source = colorImage(mapId);
            if (source == null) {
                return null;
            }
        }
        slot = allocate();
        slot.fromColors = fromColors;
        slots.put(texture, slot);
        NativeImage pending = pendingOverrides.remove(mapId);
        if (pending != null) {
            write(slot, pending);
            pending.close();
            slot.overridden = true;
        } else {
            write(slot, source);
            overrides.remove(mapId);
        }
        return slot;
    }

    public void onVanillaTextureUpdated(Identifier texture, int mapId, NativeImage image, byte[] colors) {
        updateTransparency(mapId, colors);
        Slot slot = slots.get(texture);
        if (slot != null && !slot.overridden && image != null) {
            write(slot, image);
        }
    }

    public void onColorsChanged(int mapId) {
        Slot slot = slots.get(mapTextureId(mapId));
        if (slot != null && slot.fromColors && !slot.overridden) {
            NativeImage image = colorImage(mapId);
            if (image != null) {
                write(slot, image);
            }
        }
    }

    public void onVanillaTextureClosed(Identifier texture) {
        Slot slot = slots.remove(texture);
        if (slot != null) {
            slot.page.freeSlots.add(slot.index);
        }
    }

    public boolean isAtlasLayer(RenderType layer) {
        for (Page page : pages) {
            if (page.layer == layer) {
                return true;
            }
        }
        return false;
    }

    public boolean isTransparent(int mapId) {
        return transparentMaps.contains(mapId);
    }

    public void setOverride(int mapId, NativeImage image) {
        overrides.add(mapId);
        Slot slot = slots.get(mapTextureId(mapId));
        if (slot != null) {
            write(slot, image);
            slot.overridden = true;
        } else {
            NativeImage copy = new NativeImage(TILE, TILE, false);
            image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), copy);
            NativeImage previous = pendingOverrides.put(mapId, copy);
            if (previous != null) {
                previous.close();
            }
        }
    }

    public boolean hasOverride(int mapId) {
        return overrides.contains(mapId);
    }

    public void clearOverride(int mapId) {
        if (!overrides.remove(mapId)) {
            return;
        }
        NativeImage pending = pendingOverrides.remove(mapId);
        if (pending != null) {
            pending.close();
        }
        Identifier texture = mapTextureId(mapId);
        Slot slot = slots.get(texture);
        if (slot != null) {
            slot.overridden = false;
            NativeImage source = slot.fromColors ? colorImage(mapId) : vanillaImage(texture);
            if (source != null) {
                write(slot, source);
            }
        }
    }

    public int getGeneration() {
        return generation;
    }

    public void clear() {
        resetPages();
        transparentMaps.clear();
    }

    public void onConfigChanged() {
        if (!FifConfig.atlasActive() || FifConfig.atlasSize.getPixels() != pageSize) {
            resetPages();
        }
    }

    private void resetPages() {
        for (Page page : pages) {
            page.close();
        }
        pages.clear();
        slots.clear();
        overrides.clear();
        for (NativeImage image : pendingOverrides.values()) {
            image.close();
        }
        pendingOverrides.clear();
        pageSize = FifConfig.atlasSize.getPixels();
        generation++;
    }

    private Slot allocate() {
        for (Page page : pages) {
            if (!page.freeSlots.isEmpty()) {
                return new Slot(page, page.freeSlots.removeInt(page.freeSlots.size() - 1));
            }
        }
        Page page = new Page(pages.size(), pageSize);
        pages.add(page);
        return new Slot(page, page.freeSlots.removeInt(page.freeSlots.size() - 1));
    }

    private void write(Slot slot, NativeImage image) {
        if (image.getWidth() == TILE && image.getHeight() == TILE) {
            slot.page.texture.write(image, slot.x, slot.y);
            return;
        }
        if (scratch == null) {
            scratch = new NativeImage(TILE, TILE, false);
        }
        image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), scratch);
        slot.page.texture.write(scratch, slot.x, slot.y);
    }

    private void updateTransparency(int mapId, byte[] colors) {
        if (colors == null) {
            return;
        }
        boolean transparent = false;
        for (byte color : colors) {
            if ((color & 0xFF) < 4) {
                transparent = true;
                break;
            }
        }
        if (transparent) {
            transparentMaps.add(mapId);
        } else {
            transparentMaps.remove(mapId);
        }
    }

    private NativeImage colorImage(int mapId) {
        Minecraft minecraft = Minecraft.getInstance();
        MapItemSavedData data = minecraft.level == null ? null : minecraft.level.getMapData(new MapId(mapId));
        if (data == null || data.colors == null || data.colors.length < TILE * TILE) {
            return null;
        }
        if (colorImage == null) {
            colorImage = new NativeImage(TILE, TILE, false);
        }
        byte[] colors = data.colors;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                colorImage.setPixel(x, y, MapColor.getColorFromPackedId(colors[x + y * TILE] & 0xFF));
            }
        }
        updateTransparency(mapId, colors);
        return colorImage;
    }

    private static NativeImage vanillaImage(Identifier texture) {
        AbstractTexture abstractTexture = ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager()).fif$byPath().get(texture);
        return abstractTexture instanceof DynamicTexture dynamic ? dynamic.getPixels() : null;
    }

    private static Identifier mapTextureId(int mapId) {
        return Identifier.withDefaultNamespace(MAP_TEXTURE_PREFIX + mapId);
    }

    private static int parseMapId(Identifier texture) {
        if (!"minecraft".equals(texture.getNamespace())) {
            return -1;
        }
        String path = texture.getPath();
        if (!path.startsWith(MAP_TEXTURE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(path, MAP_TEXTURE_PREFIX.length(), path.length(), 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

}
