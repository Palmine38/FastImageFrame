package fastimageframes.bake;

import fastimageframes.render.VertexRecorder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fastimageframes.FastImageFrames;
import fastimageframes.FifConfig;
import fastimageframes.atlas.MapAtlas;
import fastimageframes.hd.HdLod;
import fastimageframes.hd.HdMaps;
import fastimageframes.render.Cameras;
import fastimageframes.render.IrisCompat;
import fastimageframes.render.Occlusion;
import fastimageframes.render.Shaders;
import fastimageframes.render.SectionCulling;
import fastimageframes.render.FifLayers;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
//#if MC >= 1.21.9 && MC < 26.1
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockStateModel;
//#endif
//#if MC >= 1.21.9
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
//#if MC < 26.1
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
//#else
import net.minecraft.client.renderer.MultiBufferSource;
//#endif
//#if MC >= 26.1
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#elif MC >= 1.21.9
import net.minecraft.client.renderer.state.CameraRenderState;
//#endif

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BakedFrames {

    public static final BakedFrames INSTANCE = new BakedFrames();

    public static final int NOT_BAKEABLE = 0;
    private static final int CHECK_GROUPS = 4;
    private static final int BAKE_BUDGET_PER_TICK = 1024;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final MethodHandle HEAD_ATLAS_GENERATION = headAtlasGenerationHandle();
    private static final int GLOW_ITEM_LIGHT = 0xF000F0;
    private static final int GLOW_MAP_LIGHT = 0xF000D2;

    private static final class Section {
        final long key;
        final int originX;
        final int originY;
        final int originZ;
        final AABB bounds;
        final List<ItemFrame> frames = new ArrayList<>();
        final Map<Layer, VertexRecorder> layers = new Object2ObjectArrayMap<>();
        final VertexRecorder[] backs = new VertexRecorder[12];
        final double[] backPlanes = new double[12];
        final int[] backEntities = new int[12];
        int shadowFrame = -1;
        Entity shadowTrigger;
        final Map<ItemFrame, MapQuad> quads = new Reference2ObjectOpenHashMap<>();
        final Map<ItemFrame, Span> spans = new Reference2ObjectOpenHashMap<>();
        int occlusionGeneration = -1;
        boolean occluded;
        boolean dirty = true;
        boolean hasData;
        int version;
        SectionMesh mesh;

        Section(long key) {
            this.key = key;
            this.originX = SectionPos.sectionToBlockCoord(SectionPos.x(key));
            this.originY = SectionPos.sectionToBlockCoord(SectionPos.y(key));
            this.originZ = SectionPos.sectionToBlockCoord(SectionPos.z(key));
            this.bounds = new AABB(originX - 1, originY - 1, originZ - 1, originX + 17, originY + 17, originZ + 17);
        }
    }

    record Layer(RenderType type, int entity, int blockEntity, int item) {
    }

    private static final class Span {
        final VertexRecorder[] recorders;
        final int[] ranges;
        final boolean patchable;
        int light;

        Span(VertexRecorder[] recorders, int[] ranges, boolean patchable, int light) {
            this.recorders = recorders;
            this.ranges = ranges;
            this.patchable = patchable;
            this.light = light;
        }
    }

    private static final class MapQuad {
        final Layer layer;
        final float[] vertices;
        final boolean[] high = new boolean[8];
        final Reference2IntArrayMap<VertexRecorder> placed = new Reference2IntArrayMap<>();
        VertexRecorder live;

        MapQuad(Layer layer, VertexRecorder recorder, int first) {
            this.layer = layer;
            vertices = new float[4 * VertexRecorder.STRIDE];
            System.arraycopy(recorder.data.elements(), first * VertexRecorder.STRIDE, vertices, 0, vertices.length);
            float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                minU = Math.min(minU, vertices[i * VertexRecorder.STRIDE + 4]);
                maxU = Math.max(maxU, vertices[i * VertexRecorder.STRIDE + 4]);
                minV = Math.min(minV, vertices[i * VertexRecorder.STRIDE + 5]);
                maxV = Math.max(maxV, vertices[i * VertexRecorder.STRIDE + 5]);
            }
            for (int i = 0; i < 4; i++) {
                high[i * 2] = vertices[i * VertexRecorder.STRIDE + 4] > (minU + maxU) / 2;
                high[i * 2 + 1] = vertices[i * VertexRecorder.STRIDE + 5] > (minV + maxV) / 2;
            }
            placed.defaultReturnValue(-1);
            placed.put(recorder, first);
            live = recorder;
        }

        void show(VertexRecorder target, MapAtlas.Slot slot) {
            if (target != live) {
                live.collapse(placed.getInt(live), 4);
                int first = placed.getInt(target);
                if (first < 0) {
                    placed.put(target, target.append(vertices));
                } else {
                    target.write(first, vertices);
                }
                live = target;
            }
            live.setUvs(placed.getInt(live), high, slot.u0, slot.v0, slot.u1, slot.v1);
        }

        void relight(int from, int to) {
            for (int v = 7; v < vertices.length; v += VertexRecorder.STRIDE) {
                if (Float.floatToRawIntBits(vertices[v]) == from) {
                    vertices[v] = Float.intBitsToFloat(to);
                }
            }
            int first = placed.getInt(live);
            live.relight(first * VertexRecorder.STRIDE, (first + 4) * VertexRecorder.STRIDE, from, to);
        }
    }

    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ByteOpenHashMap mapFrames = new Long2ByteOpenHashMap();
    private final MapRenderState scratch = new MapRenderState();
    private final List<Section> rebuild = new ArrayList<>();
    private Section baked;
    private MapAtlas.Slot lastSlot;
    private int lastGeometry;
    private ItemFrame recording;
    private boolean rejected;
    private Frustum frustum;
    private Level level;
    private boolean active;
    private boolean baking;
    private boolean probing;
    private boolean recordingItems;
    private int probeTypes;
    private int headGeneration = -1;
    private final Map<RenderType, VertexRecorder> probes = new java.util.LinkedHashMap<>();
    private int atlasGeneration = -1;
    private long tick;
    private final int[] reasons = new int[8];
    private final long[] totals = new long[8];
    private static final String[] REASON_NAMES = {"no map data", "glowing", "named and targeted", "not in the atlas", "named marker", "drawn with other layers", "changed", "animated"};
    private static final long ANIMATION_WINDOW_TICKS = 40;
    private static final long ANIMATION_HOLD_TICKS = 100;
    private static final long REPORT_EVERY_TICKS = 60 * 20;
    private static final int PROBE_EVERY_TICKS = 32;
    private static final long SETTLE_TICKS = 200;
    private final java.util.Set<RenderType> decorationLayers = new ReferenceOpenHashSet<>();
    private String example = "";
    private String lastRejection = "";
    private int frame;
    private boolean shaders;
    private Object idMaps;
    private int recordingEntity;
    private int relit;
    private int bakes;

    private BakedFrames() {
        mapFrames.defaultReturnValue((byte) -1);
    }

    public static boolean supported() {
        return true;
    }

    private static MethodHandle headAtlasGenerationHandle() {
        if (!FabricLoader.getInstance().isModLoaded("voltum")) {
            return null;
        }
        try {
            Class<?> atlas = Class.forName("voltum.atlas.HeadAtlas");
            return MethodHandles.publicLookup().findVirtual(atlas, "getGeneration", MethodType.methodType(int.class)).bindTo(atlas.getField("INSTANCE").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static int headAtlasGeneration() {
        if (HEAD_ATLAS_GENERATION == null) {
            return 0;
        }
        try {
            return (int) HEAD_ATLAS_GENERATION.invokeExact();
        } catch (Throwable e) {
            return 0;
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isBaking() {
        return baking || probing;
    }

    public boolean hasMapFrame(long pos, int facing) {
        return active && mapFrames.get(pos) == facing;
    }

    public static int cull(Entity entity, Frustum frustum) {
        BakedFrames bakedFrames = INSTANCE;
        boolean shadow = bakedFrames.shaders && Shaders.shadowPass();
        if (!shadow) {
            bakedFrames.frustum = frustum;
        }
        if (!(entity instanceof BakeableFrame frame) || !frame.fif$isBaked() || !bakedFrames.active || bakedFrames.shaders != Shaders.active()) {
            return entity instanceof ItemFrame itemFrame && hidden(itemFrame) ? 1 : 0;
        }
        if (!shadow) {
            return 1;
        }
        Section section = bakedFrames.sections.get(SectionPos.asLong(entity.blockPosition()));
        if (section == null || !section.hasData || section.dirty || section.shadowFrame == bakedFrames.frame) {
            return 1;
        }
        section.shadowFrame = bakedFrames.frame;
        if (frustum != null && !frustum.isVisible(section.bounds)) {
            return 1;
        }
        section.shadowTrigger = entity;
        return 2;
    }

    private static boolean hidden(ItemFrame frame) {
        return Occlusion.active() && !Shaders.shadowPass() && !Minecraft.getInstance().shouldEntityAppearGlowing(frame)
                && Occlusion.blockHidden(frame.blockPosition(), 0.0);
    }

    public void onFrameStart() {
        frame++;
        //#if MC < 26.2
        SectionMesh.beginFrame();
        //#endif
    }

    public static void beginGpuFrame() {
        SectionMesh.beginFrame();
    }

    private Section shadowSection(Entity entity) {
        if (!shaders || !(entity instanceof ItemFrame) || !Shaders.shadowPass()) {
            return null;
        }
        Section section = sections.get(SectionPos.asLong(entity.blockPosition()));
        if (section == null || section.shadowTrigger != entity) {
            return null;
        }
        section.shadowTrigger = null;
        return section.hasData && !section.dirty ? section : null;
    }

    private void report() {
        int frames = 0;
        for (Section section : sections.values()) {
            frames += section.frames.size();
        }
        long[] minute = new long[reasons.length];
        for (int i = 0; i < reasons.length; i++) {
            minute[i] = reasons[i];
        }
        FastImageFrames.LOGGER.debug("Baked map walls: {}/{} item frames baked, not baked in the last minute: {}{}", bakedCount(), frames, describe(minute),
                example.isEmpty() ? "" : " (for example " + example + ")");
        example = "";
        java.util.Arrays.fill(reasons, 0);
        lastRejection = "";
    }

    private int count(int reason) {
        totals[reason]++;
        return ++reasons[reason];
    }

    public String debugReasons() {
        return describe(totals);
    }

    private String describe(long[] counts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                text.append(REASON_NAMES[i]).append(": ").append(counts[i]).append(", ");
            }
        }
        if (!lastRejection.isEmpty()) {
            text.append("last rejected: ").append(lastRejection).append(", ");
        }
        return text.isEmpty() ? "none" : text.substring(0, text.length() - 2);
    }

    public String describeLayers() {
        Map<String, Integer> vertices = new java.util.TreeMap<>();
        for (Section section : sections.values()) {
            for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
                String name = layer.getKey().type().toString();
                int start = name.indexOf("texture[Optional[");
                String label = start < 0 ? name.substring(0, Math.min(40, name.length())) : name.substring(start + 17, name.indexOf(']', start));
                vertices.merge(label, layer.getValue().data.size() / VertexRecorder.STRIDE, Integer::sum);
            }
            for (VertexRecorder back : section.backs) {
                if (back != null) {
                    vertices.merge("frame backs", back.data.size() / VertexRecorder.STRIDE, Integer::sum);
                }
            }
        }
        return vertices.toString();
    }

    public static void setGpu(boolean enabled) {
        SectionMesh.enabled = enabled;
    }

    public int gpuDraws() {
        return SectionMesh.draws;
    }

    public int strandedFrames() {
        int count = 0;
        for (Section section : sections.values()) {
            if (section.dirty || !section.hasData) {
                for (ItemFrame frame : section.frames) {
                    if (((BakeableFrame) frame).fif$isBaked()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public int bakedCount() {
        int count = 0;
        for (Section section : sections.values()) {
            for (ItemFrame frame : section.frames) {
                if (((BakeableFrame) frame).fif$isBaked()) {
                    count++;
                }
            }
        }
        return count;
    }

    public void onLoad(Entity entity, Level entityLevel) {
        if (entityLevel != level) {
            clear();
            level = entityLevel;
        }
        if (entity instanceof ItemFrame frame) {
            ((BakeableFrame) frame).fif$setBaked(false);
            ((BakeableFrame) frame).fif$setSignature(NOT_BAKEABLE);
            ((BakeableFrame) frame).fif$setProbe(0, tick, Long.MIN_VALUE, false, NOT_BAKEABLE);
            Section section = sections.computeIfAbsent(SectionPos.asLong(frame.blockPosition()), Section::new);
            section.frames.add(frame);
            markDirty(section);
        }
    }

    public void onUnload(Entity entity, Level entityLevel) {
        if (entityLevel == level && entity instanceof ItemFrame frame) {
            ((BakeableFrame) frame).fif$setBaked(false);
            long key = SectionPos.asLong(frame.blockPosition());
            Section section = sections.get(key);
            if (section != null) {
                section.frames.remove(frame);
                markDirty(section);
                if (section.frames.isEmpty()) {
                    closeMesh(section);
                    sections.remove(key);
                }
            }
            if (mapFrames.remove(frame.blockPosition().asLong()) != -1) {
                neighborsChanged(frame.blockPosition());
            }
        }
    }

    public void onFrameChanged(ItemFrame frame) {
        BakeableFrame bakeable = (BakeableFrame) frame;
        MapId mapId = frame.getFramedMapId(frame.getItem());
        int content = ((mapId == null ? -1 : mapId.id()) * 31 + frame.getRotation()) * 31 + (frame.isInvisible() ? 1 : 0);
        if (content == bakeable.fif$content()) {
            return;
        }
        bakeable.fif$setContent(content);
        if (active && frame.level() == level && swap(frame, bakeable)) {
            return;
        }
        long last = bakeable.fif$changedTick();
        if (last != tick) {
            boolean animated = tick - last <= ANIMATION_WINDOW_TICKS;
            bakeable.fif$setChanged(tick, animated ? tick + ANIMATION_HOLD_TICKS : bakeable.fif$animatedUntil());
        }
        if (bakeable.fif$isBaked()) {
            Section section = sections.get(SectionPos.asLong(frame.blockPosition()));
            if (section != null) {
                markDirty(section);
            }
        }
    }

    private boolean swap(ItemFrame frame, BakeableFrame bakeable) {
        if (!(bakeable.fif$slot() instanceof MapAtlas.Slot)) {
            return false;
        }
        int geometry = bakeable.fif$geometry();
        int signature = check(Minecraft.getInstance(), frame, Cameras.position());
        if (lastSlot == null || lastGeometry != geometry) {
            return false;
        }
        if (!bakeable.fif$isBaked()) {
            return true;
        }
        Section section = sections.get(SectionPos.asLong(frame.blockPosition()));
        MapQuad quad = section == null ? null : section.quads.get(frame);
        if (quad == null || signature == NOT_BAKEABLE) {
            return false;
        }
        quad.show(section.layers.computeIfAbsent(new Layer(lastSlot.layer(), quad.layer.entity(), quad.layer.blockEntity(), quad.layer.item()), BakedFrames::recorder), lastSlot);
        section.version++;
        bakeable.fif$setSignature(signature);
        return true;
    }

    private int check(Minecraft minecraft, ItemFrame frame, Vec3 camera) {
        lastSlot = null;
        lastGeometry = 0;
        int signature = signature(minecraft, frame, camera);
        ((BakeableFrame) frame).fif$setShape(lastGeometry, lastSlot);
        return signature;
    }

    public void tick() {
        tick++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level && level != null) {
            clear();
            level = null;
        }
        boolean nowActive = computeActive(minecraft);
        if (nowActive != active) {
            active = nowActive;
            if (!active) {
                clearBakes();
            }
        }
        if (!active) {
            return;
        }
        int generation = MapAtlas.INSTANCE.getGeneration();
        if (generation != atlasGeneration) {
            atlasGeneration = generation;
            clearBakes();
        }
        int heads = headAtlasGeneration();
        if (heads != headGeneration) {
            headGeneration = heads;
            clearBakes();
        }
        boolean nowShaders = Shaders.active();
        Object maps = nowShaders ? IrisCompat.idMaps() : null;
        if (nowShaders != shaders || maps != idMaps) {
            shaders = nowShaders;
            idMaps = maps;
            clearBakes();
        }
        int group = (int) (tick % CHECK_GROUPS);
        Vec3 camera = Cameras.position();
        for (Section section : sections.values()) {
            List<ItemFrame> frames = section.frames;
            for (int i = 0; i < frames.size(); i++) {
                ItemFrame frame = frames.get(i);
                if (!section.dirty && (frame.getId() & (CHECK_GROUPS - 1)) != group) {
                    continue;
                }
                BakeableFrame bakeable = (BakeableFrame) frame;
                int signature = check(minecraft, frame, camera);
                if (signature != bakeable.fif$signature()) {
                    if (bakeable.fif$isBaked()) {
                        count(6);
                    }
                    if (signature != NOT_BAKEABLE && bakeable.fif$signature() != NOT_BAKEABLE && bakeable.fif$slot() == null) {
                        long last = bakeable.fif$changedTick();
                        boolean animated = tick - last <= ANIMATION_WINDOW_TICKS;
                        bakeable.fif$setChanged(tick, animated ? tick + ANIMATION_HOLD_TICKS : bakeable.fif$animatedUntil());
                    }
                    bakeable.fif$setSignature(signature);
                    markDirty(section);
                }
                if (bakeable.fif$isBaked() && !section.dirty) {
                    relight(minecraft, section, frame);
                }
            }
        }
        if (tick % REPORT_EVERY_TICKS == 0 && !sections.isEmpty()) {
            boolean worth = false;
            for (int i = 0; i < reasons.length; i++) {
                worth |= reasons[i] != 0 && i != 6 && i != 7;
            }
            if (worth) {
                report();
            } else {
                java.util.Arrays.fill(reasons, 0);
            }
        }
        rebuild.clear();
        for (Section section : sections.values()) {
            if (section.dirty) {
                rebuild.add(section);
            }
        }
        if (minecraft.getEntityRenderDispatcher().camera == null) {
            return;
        }
        int budget = BAKE_BUDGET_PER_TICK;
        for (Section section : rebuild) {
            if (budget <= 0) {
                break;
            }
            budget -= section.frames.size();
            bake(minecraft, section, camera);
        }
    }

    private boolean computeActive(Minecraft minecraft) {
        if (!FifConfig.bakeActive() || minecraft.level == null) {
            return false;
        }
        //#if MC < 1.21.9
        if (minecraft.getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            return false;
        }
        //#endif
        if (Shaders.active() && !IrisCompat.available()) {
            return false;
        }
        return true;
    }

    private int signature(Minecraft minecraft, ItemFrame frame, Vec3 camera) {
        long pos = frame.blockPosition().asLong();
        ItemStack stack = frame.getItem();
        MapId mapId = frame.getFramedMapId(stack);
        MapItemSavedData data = mapId == null ? null : frame.level().getMapData(mapId);
        int facing = frame.getDirection().ordinal();
        boolean mapFrame = data != null && !frame.isInvisible();
        byte previous = mapFrames.get(pos);
        if (mapFrame && previous != facing) {
            mapFrames.put(pos, (byte) facing);
            neighborsChanged(frame.blockPosition());
        } else if (!mapFrame && previous != -1) {
            mapFrames.remove(pos);
            neighborsChanged(frame.blockPosition());
        }
        if (data == null) {
            return itemSignature(minecraft, frame, camera, stack);
        }
        if (minecraft.shouldEntityAppearGlowing(frame)) {
            count(1);
            return NOT_BAKEABLE;
        }
        if (stack.has(DataComponents.CUSTOM_NAME) && minecraft.crosshairPickEntity == frame) {
            count(2);
            return NOT_BAKEABLE;
        }
        if (HdMaps.INSTANCE.hasSources()) {
            HdLod.setDistance(Math.sqrt(frame.distanceToSqr(camera)));
        }
        minecraft.getMapRenderer().extractRenderState(mapId, data, scratch);
        HdLod.clear();
        MapAtlas.Slot slot = FifConfig.atlasActive() ? MapAtlas.INSTANCE.slotFor(scratch) : null;
        if (slot == null) {
            if (count(3) == 1) {
                Object texture = scratch.texture == null ? null : minecraft.getTextureManager().getTexture(scratch.texture);
                example = "map " + mapId.id() + " texture " + scratch.texture + " (" + (texture == null ? "none" : texture.getClass().getName()) + ")"
                        + ", atlas " + (FifConfig.atlasActive() ? "on" : "off") + ", HD " + HdMaps.INSTANCE.describe(mapId.id());
            }
            return NOT_BAKEABLE;
        }
        int markers = 0;
        for (MapRenderState.MapDecorationRenderState decoration : scratch.decorations) {
            if (decoration.renderOnFrame) {
                if (decoration.name != null || decoration.atlasSprite == null) {
                    count(4);
                    return NOT_BAKEABLE;
                }
                decorationLayers.add(RenderTypes.text(decoration.atlasSprite.atlasLocation()));
                markers = markers * 31 + System.identityHashCode(decoration.atlasSprite);
                markers = markers * 31 + (decoration.x << 16 | (decoration.y & 0xFF) << 8 | decoration.rot & 0xFF);
            }
        }
        int geometry = frame.getRotation();
        geometry = geometry * 31 + facing;
        geometry = geometry * 31 + (frame.isInvisible() ? 1 : 0);
        geometry = geometry * 31 + (MapAtlas.INSTANCE.isTransparent(mapId.id()) ? 1 : 0);
        geometry = geometry * 31 + frame.getType().hashCode();
        geometry = geometry * 31 + markers;
        lastSlot = slot;
        lastGeometry = geometry;
        if (tick < ((BakeableFrame) frame).fif$animatedUntil()) {
            count(7);
            return NOT_BAKEABLE;
        }
        int hash = mapId.id();
        hash = hash * 31 + System.identityHashCode(slot);
        hash = hash * 31 + geometry;
        return hash == NOT_BAKEABLE ? 1 : hash;
    }

    private int itemSignature(Minecraft minecraft, ItemFrame frame, Vec3 camera, ItemStack stack) {
        lastSlot = null;
        lastGeometry = 0;
        if (!itemFramesSupported() || !FifConfig.bakeItemFrames) {
            count(0);
            return NOT_BAKEABLE;
        }
        if (minecraft.shouldEntityAppearGlowing(frame)) {
            count(1);
            return NOT_BAKEABLE;
        }
        if (stack.has(DataComponents.CUSTOM_NAME) && minecraft.crosshairPickEntity == frame) {
            count(2);
            return NOT_BAKEABLE;
        }
        if (tick < ((BakeableFrame) frame).fif$animatedUntil()) {
            count(7);
            return NOT_BAKEABLE;
        }
        Section section = sections.get(SectionPos.asLong(frame.blockPosition()));
        if (section == null || minecraft.getEntityRenderDispatcher().camera == null) {
            return NOT_BAKEABLE;
        }
        BakeableFrame bakeable = (BakeableFrame) frame;
        int key = itemKey(minecraft, frame, stack);
        boolean sameKey = key == bakeable.fif$itemKey() && bakeable.fif$nextProbe() != Long.MIN_VALUE;
        if (sameKey && !bakeable.fif$volatile() && tick - bakeable.fif$keySince() > SETTLE_TICKS && tick < bakeable.fif$nextProbe()) {
            return bakeable.fif$probed();
        }
        int signature = probe(minecraft, frame, section, camera, stack);
        long next = tick + PROBE_EVERY_TICKS + (frame.getId() & (PROBE_EVERY_TICKS - 1));
        if (!sameKey) {
            bakeable.fif$setProbe(key, tick, next, false, signature);
        } else {
            boolean changing = bakeable.fif$volatile() || tick - bakeable.fif$keySince() > SETTLE_TICKS && signature != bakeable.fif$probed();
            bakeable.fif$setProbe(key, bakeable.fif$keySince(), next, changing, signature);
        }
        return signature;
    }

    private static int itemKey(Minecraft minecraft, ItemFrame frame, ItemStack stack) {
        int key = System.identityHashCode(stack.getItem());
        key = key * 31 + stack.getComponentsPatch().hashCode();
        key = key * 31 + frame.getRotation();
        key = key * 31 + frame.getDirection().ordinal();
        key = key * 31 + (frame.isInvisible() ? 1 : 0);
        return key;
    }

    private int probe(Minecraft minecraft, ItemFrame frame, Section section, Vec3 camera, ItemStack stack) {
        for (VertexRecorder probe : probes.values()) {
            probe.clear();
        }
        probeTypes = 1;
        rejected = false;
        probing = true;
        recording = frame;
        recordingItems = true;
        try {
            record(minecraft.getEntityRenderDispatcher(), frame, section, camera, new PoseStack());
        } catch (RuntimeException e) {
            rejected = true;
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            lastRejection = stack.getItem() + ": " + cause;
        } finally {
            probing = false;
            recording = null;
        }
        if (rejected) {
            count(5);
            return NOT_BAKEABLE;
        }
        int hash = probeTypes;
        for (VertexRecorder probe : probes.values()) {
            float[] a = probe.data.elements();
            for (int i = 0, size = probe.data.size(); i < size; i++) {
                if (i % VertexRecorder.STRIDE != 7) {
                    hash = hash * 31 + Float.floatToRawIntBits(a[i]);
                }
            }
        }
        hash = hash * 31 + frame.getType().hashCode();
        return hash == NOT_BAKEABLE ? 1 : hash;
    }

    private static boolean itemFramesSupported() {
        //#if MC >= 26.1
        return false;
        //#else
        return true;
        //#endif
    }

    private VertexRecorder layerFor(RenderType type) {
        if (probing) {
            probeTypes = probeTypes * 31 + System.identityHashCode(type);
            return probes.computeIfAbsent(type, t -> new VertexRecorder());
        }
        if (recordingItems || type == FifLayers.FRAME_LAYER || MapAtlas.INSTANCE.isAtlasLayer(type) || decorationLayers.contains(type)) {
            Layer layer = shaders ? new Layer(type, recordingEntity, IrisCompat.currentBlockEntity(), IrisCompat.currentItem()) : new Layer(type, 0, 0, 0);
            return baked.layers.computeIfAbsent(layer, BakedFrames::recorder);
        }
        rejected = true;
        lastRejection = "layer " + type;
        return null;
    }

    public VertexConsumer backLayer(VertexConsumer consumer) {
        if (!baking || recording == null || !(consumer instanceof VertexRecorder)) {
            return consumer;
        }
        Section section = baked;
        Direction direction = recording.getDirection();
        int facing = backIndex(recording);
        section.backEntities[facing] = recordingEntity;
        VertexRecorder back = section.backs[facing];
        if (back == null) {
            back = section.backs[facing] = new VertexRecorder();
            section.backPlanes[facing] = Double.NEGATIVE_INFINITY;
        }
        double plane = recording.getX() * direction.getStepX() + recording.getY() * direction.getStepY() + recording.getZ() * direction.getStepZ();
        section.backPlanes[facing] = Math.max(section.backPlanes[facing], plane);
        return back;
    }

    private void bake(Minecraft minecraft, Section section, Vec3 camera) {
        bakes++;
        section.dirty = false;
        section.version++;
        for (VertexRecorder layer : section.layers.values()) {
            layer.clear();
        }
        clearBacks(section);
        section.quads.clear();
        section.spans.clear();
        section.occlusionGeneration = -1;
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        PoseStack poseStack = new PoseStack();
        baked = section;
        baking = true;
        try {
            for (ItemFrame frame : section.frames) {
                BakeableFrame bakeable = (BakeableFrame) frame;
                if (bakeable.fif$signature() == NOT_BAKEABLE || frame.isRemoved()) {
                    bakeable.fif$setBaked(false);
                    continue;
                }
                Layer[] types = section.layers.keySet().toArray(new Layer[0]);
                int[] sizes = new int[types.length];
                for (int i = 0; i < types.length; i++) {
                    sizes[i] = section.layers.get(types[i]).data.size();
                }
                int facing = backIndex(frame);
                int backSize = section.backs[facing] == null ? 0 : section.backs[facing].data.size();
                double backPlane = section.backPlanes[facing];
                int light = dispatcher.getPackedLightCoords(frame, 1.0F);
                rejected = false;
                recording = frame;
                MapId framedMap = frame.getFramedMapId(frame.getItem());
                recordingItems = framedMap == null || frame.level().getMapData(framedMap) == null;
                recordingEntity = shaders ? IrisCompat.entityId(frame.getType()) : 0;
                if (shaders) {
                    IrisCompat.setIds(0, 0, 0);
                }
                try {
                    record(dispatcher, frame, section, camera, poseStack);
                } finally {
                    recording = null;
                    if (shaders) {
                        IrisCompat.setIds(0, 0, 0);
                    }
                }
                if (rejected) {
                    count(5);
                    for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
                        int index = indexOf(types, layer.getKey());
                        if (index < 0) {
                            layer.getValue().clear();
                        } else {
                            layer.getValue().truncate(sizes[index]);
                        }
                    }
                    if (section.backs[facing] != null) {
                        section.backs[facing].truncate(backSize);
                    }
                    section.backPlanes[facing] = backPlane;
                } else {
                    MapQuad quad = captureQuad(section, types, sizes);
                    if (quad != null) {
                        section.quads.put(frame, quad);
                    }
                    section.spans.put(frame, span(section, frame, types, sizes, facing, backSize, light));
                }
                bakeable.fif$setBaked(!rejected);
            }
        } catch (RuntimeException e) {
            FastImageFrames.LOGGER.warn("Unable to bake the map frames of a section, they are drawn the normal way", e);
            for (ItemFrame frame : section.frames) {
                ((BakeableFrame) frame).fif$setBaked(false);
                ((BakeableFrame) frame).fif$setSignature(NOT_BAKEABLE);
            }
            for (VertexRecorder layer : section.layers.values()) {
                layer.clear();
            }
            clearBacks(section);
            section.quads.clear();
            section.spans.clear();
        } finally {
            baking = false;
            baked = null;
        }
        section.hasData = false;
        for (VertexRecorder layer : section.layers.values()) {
            section.hasData |= !layer.data.isEmpty();
        }
        for (VertexRecorder back : section.backs) {
            section.hasData |= back != null && !back.data.isEmpty();
        }
    }

    private static MapQuad captureQuad(Section section, Layer[] types, int[] sizes) {
        VertexRecorder grown = null;
        Layer key = null;
        int first = 0;
        for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
            int index = indexOf(types, layer.getKey());
            int before = index < 0 ? 0 : sizes[index];
            int added = layer.getValue().data.size() - before;
            if (added == 0 || !MapAtlas.INSTANCE.isAtlasLayer(layer.getKey().type())) {
                continue;
            }
            if (grown != null || added != 4 * VertexRecorder.STRIDE) {
                return null;
            }
            grown = layer.getValue();
            key = layer.getKey();
            first = before / VertexRecorder.STRIDE;
        }
        return grown == null ? null : new MapQuad(key, grown, first);
    }

    private static Span span(Section section, ItemFrame frame, Layer[] types, int[] sizes, int facing, int backSize, int light) {
        boolean glow = frame instanceof GlowItemFrame;
        List<VertexRecorder> recorders = new ArrayList<>(4);
        IntArrayList ranges = new IntArrayList();
        for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
            int index = indexOf(types, layer.getKey());
            addRange(recorders, ranges, layer.getValue(), index < 0 ? 0 : sizes[index]);
        }
        addRange(recorders, ranges, section.backs[facing], backSize);
        boolean patchable = !glow || !glowLight(light);
        for (int i = 0; i < recorders.size() && patchable; i++) {
            float[] a = recorders.get(i).data.elements();
            for (int v = ranges.getInt(i * 2) + 7; v < ranges.getInt(i * 2 + 1); v += VertexRecorder.STRIDE) {
                int value = Float.floatToRawIntBits(a[v]);
                if (value != light && !(glow && glowLight(value))) {
                    patchable = false;
                    break;
                }
            }
        }
        return new Span(recorders.toArray(new VertexRecorder[0]), ranges.toIntArray(), patchable, light);
    }

    private static void addRange(List<VertexRecorder> recorders, IntArrayList ranges, VertexRecorder recorder, int start) {
        if (recorder != null && recorder.data.size() > start) {
            recorders.add(recorder);
            ranges.add(start);
            ranges.add(recorder.data.size());
        }
    }

    private static boolean glowLight(int light) {
        return light == GLOW_ITEM_LIGHT || light == GLOW_MAP_LIGHT;
    }

    private void relight(Minecraft minecraft, Section section, ItemFrame frame) {
        Span span = section.spans.get(frame);
        if (span == null) {
            return;
        }
        int light = minecraft.getEntityRenderDispatcher().getPackedLightCoords(frame, 1.0F);
        if (light == span.light) {
            return;
        }
        if (!span.patchable || frame instanceof GlowItemFrame && glowLight(light)) {
            markDirty(section);
            return;
        }
        for (int i = 0; i < span.recorders.length; i++) {
            span.recorders[i].relight(span.ranges[i * 2], span.ranges[i * 2 + 1], span.light, light);
        }
        MapQuad quad = section.quads.get(frame);
        if (quad != null) {
            quad.relight(span.light, light);
        }
        span.light = light;
        section.version++;
        relit++;
    }

    public int relitCount() {
        return relit;
    }

    public int frameCount() {
        return frame;
    }

    public int sectionBakes() {
        return bakes;
    }

    private boolean occluded(Section section) {
        if (!Occlusion.active() || Shaders.shadowPass()) {
            return false;
        }
        int generation = Occlusion.generation();
        if (section.occlusionGeneration == generation) {
            return section.occluded;
        }
        boolean hidden = true;
        boolean settled = true;
        for (ItemFrame frame : section.frames) {
            if (!((BakeableFrame) frame).fif$isBaked()) {
                continue;
            }
            boolean frameHidden = Occlusion.blockHidden(frame.blockPosition(), 0.0);
            settled &= Occlusion.settled();
            if (!frameHidden) {
                hidden = false;
                break;
            }
        }
        if (settled) {
            section.occlusionGeneration = generation;
            section.occluded = hidden;
        }
        return hidden;
    }

    private static VertexRecorder recorder(Layer layer) {
        VertexRecorder recorder = new VertexRecorder();
        recorder.push = layer.type() == FifLayers.FRAME_LAYER;
        return recorder;
    }

    private static int backIndex(ItemFrame frame) {
        return frame.getDirection().ordinal() + (frame instanceof GlowItemFrame ? 6 : 0);
    }

    private static void clearBacks(Section section) {
        for (int i = 0; i < 12; i++) {
            if (section.backs[i] != null) {
                section.backs[i].clear();
            }
            section.backPlanes[i] = Double.NEGATIVE_INFINITY;
        }
    }

    private static boolean backVisible(Section section, int facing, Vec3 camera) {
        VertexRecorder back = section.backs[facing];
        if (back == null || back.data.isEmpty()) {
            return false;
        }
        Direction direction = DIRECTIONS[facing % 6];
        return camera.x * direction.getStepX() + camera.y * direction.getStepY() + camera.z * direction.getStepZ() < section.backPlanes[facing];
    }

    private static int indexOf(Layer[] types, Layer type) {
        for (int i = 0; i < types.length; i++) {
            if (types[i].equals(type)) {
                return i;
            }
        }
        return -1;
    }

    //#if MC >= 1.21.9
    private final SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(BakedFrames.class.getClassLoader(),
            new Class<?>[] {SubmitNodeCollector.class}, recordingHandler());

    //#if MC < 26.1
    private final MultiBufferSource recorder = type -> {
        VertexRecorder layer = layerFor(type);
        return layer != null ? layer : DISCARD;
    };

    private static final VertexRecorder DISCARD = new VertexRecorder();
    //#endif

    @SuppressWarnings("unchecked")
    private InvocationHandler recordingHandler() {
        return (proxy, method, args) -> switch (method.getName()) {
            case "order" -> proxy;
            case "submitCustomGeometry" -> {
                VertexRecorder recorder = layerFor((RenderType) args[1]);
                if (recorder != null) {
                    ((SubmitNodeCollector.CustomGeometryRenderer) args[2]).render(((PoseStack) args[0]).last(), recorder);
                }
                yield null;
            }
            //#if MC < 26.1
            case "submitBlockModel", "submitBlockStateModel" -> {
                RenderType type = args[1] instanceof RenderType renderType ? renderType : FifLayers.FRAME_LAYER;
                VertexRecorder recorder = args.length >= 9 && args[2] instanceof BlockStateModel && (int) args[8] == 0 ? layerFor(type) : null;
                if (recorder != null) {
                    ModelBlockRenderer.renderModel(((PoseStack) args[0]).last(), recorder, (BlockStateModel) args[2],
                            (float) args[3], (float) args[4], (float) args[5], (int) args[6], (int) args[7]);
                } else {
                    rejected = true;
                    lastRejection = method.toString();
                }
                yield null;
            }
            //#endif
            //#if MC < 26.1
            case "submitItem" -> {
                if (args.length == 9 && args[7] instanceof RenderType type) {
                    ItemRenderer.renderItem((ItemDisplayContext) args[1], (PoseStack) args[0], this.recorder, (int) args[2], (int) args[3], (int[]) args[5],
                            (List<BakedQuad>) args[6], type, (ItemStackRenderState.FoilType) args[8]);
                } else {
                    rejected = true;
                    lastRejection = method.toString();
                }
                yield null;
            }
            case "submitModel" -> {
                if (args[3] instanceof RenderType type) {
                    int tint = -1;
                    TextureAtlasSprite sprite = null;
                    if (args.length >= 10) {
                        tint = (int) args[6];
                        sprite = (TextureAtlasSprite) args[7];
                    }
                    VertexConsumer consumer = recorder.getBuffer(type);
                    if (sprite != null) {
                        consumer = sprite.wrap(consumer);
                    }
                    Model model = (Model) args[0];
                    model.setupAnim(args[1]);
                    model.renderToBuffer((PoseStack) args[2], consumer, (int) args[4], (int) args[5], tint);
                } else {
                    rejected = true;
                    lastRejection = method.toString();
                }
                yield null;
            }
            case "submitModelPart" -> {
                RenderType type = (RenderType) args[2];
                TextureAtlasSprite sprite = (TextureAtlasSprite) args[5];
                boolean sheeted = args.length >= 8 && (boolean) args[6];
                boolean foil = args.length >= 8 && (boolean) args[7];
                int tint = args.length >= 9 ? (int) args[8] : -1;
                VertexConsumer consumer = foil ? ItemRenderer.getFoilBuffer(recorder, type, sheeted, true) : recorder.getBuffer(type);
                if (sprite != null) {
                    consumer = sprite.wrap(consumer);
                }
                ((ModelPart) args[0]).render((PoseStack) args[1], consumer, (int) args[3], (int) args[4], tint);
                yield null;
            }
            //#endif
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Fast ImageFrames bake collector";
            default -> {
                rejected = true;
                lastRejection = method.toString();
                yield defaultValue(method.getReturnType());
            }
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private void record(EntityRenderDispatcher dispatcher, ItemFrame frame, Section section, Vec3 camera, PoseStack poseStack) {
        EntityRenderState state = dispatcher.extractEntity(frame, 1.0F);
        CameraRenderState cameraState = new CameraRenderState();
        cameraState.pos = camera;
        dispatcher.submit(state, cameraState, state.x - section.originX, state.y - section.originY, state.z - section.originZ, poseStack, collector);
    }

    public void submit(SubmitNodeCollector out, PoseStack poseStack) {
        if (!replayable()) {
            return;
        }
        Vec3 camera = Cameras.position();
        Frustum frustum = this.frustum;
        SectionCulling.begin(camera);
        Matrix4fc base = poseStack.last().pose();
        boolean gpu = !shaders && SectionMesh.usable() && (base.properties() & Matrix4fc.PROPERTY_TRANSLATION) != 0;
        Vec3 placed = camera.subtract(base.m30(), base.m31(), base.m32());
        for (Section section : sections.values()) {
            if (!section.hasData || section.dirty || frustum != null && !frustum.isVisible(section.bounds)
                    || !SectionCulling.isVisible(section.key, section.originX, section.originY, section.originZ) || occluded(section)) {
                continue;
            }
            float ox = (float) (section.originX - camera.x);
            float oy = (float) (section.originY - camera.y);
            float oz = (float) (section.originZ - camera.z);
            SectionMesh mesh = gpu ? mesh(section, camera) : null;
            for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
                VertexRecorder recorder = layer.getValue();
                if (!recorder.data.isEmpty() && (mesh == null || !mesh.has(layer.getKey().type()))) {
                    ids(layer.getKey());
                    out.submitCustomGeometry(poseStack, layer.getKey().type(), (pose, consumer) -> recorder.replay(consumer, ox, oy, oz));
                }
            }
            int visibleBacks = 0;
            for (int i = 0; i < 12; i++) {
                if (backVisible(section, i, camera)) {
                    if (mesh != null && mesh.hasBack(i)) {
                        visibleBacks |= 1 << i;
                        continue;
                    }
                    VertexRecorder recorder = section.backs[i];
                    backIds(section, i);
                    out.submitCustomGeometry(poseStack, FifLayers.FRAME_LAYER, (pose, consumer) -> recorder.replay(consumer, ox, oy, oz));
                }
            }
            if (mesh != null) {
                mesh.queue(placed, visibleBacks);
            }
        }
        clearIds();
        //#if MC < 26.2
        SectionMesh.draw();
        //#endif
    }

    //#if MC >= 26.2
    public static void drawGpu() {
        SectionMesh.draw();
    }
    //#endif

    //#if MC >= 26.3
    public static void prepareGpu() {
        SectionMesh.prepare();
    }

    public static void drawGpu(com.mojang.renderpearl.api.commands.RenderPass pass, boolean sorted, net.minecraft.client.renderer.oit.OitStage stage, boolean last) {
        SectionMesh.draw(pass, sorted, stage, last);
    }
    //#endif

    public static final class BakedFrameState extends EntityRenderState {
        final Section section;

        BakedFrameState(Section section, Entity trigger) {
            this.section = section;
            this.entityType = trigger.getType();
            this.x = trigger.getX();
            this.y = trigger.getY();
            this.z = trigger.getZ();
        }
    }

    public EntityRenderState shadowState(Entity entity) {
        Section section = shadowSection(entity);
        return section == null ? null : new BakedFrameState(section, entity);
    }

    public void submitShadow(BakedFrameState state, double x, double y, double z, PoseStack poseStack, SubmitNodeCollector out) {
        Section section = state.section;
        if (!section.hasData || section.dirty) {
            return;
        }
        float ox = (float) (section.originX - (state.x - x));
        float oy = (float) (section.originY - (state.y - y));
        float oz = (float) (section.originZ - (state.z - z));
        for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
            VertexRecorder recorder = layer.getValue();
            if (!recorder.data.isEmpty()) {
                ids(layer.getKey());
                out.submitCustomGeometry(poseStack, layer.getKey().type(), (pose, consumer) -> recorder.replay(consumer, pose.pose(), pose.normal(), ox, oy, oz));
            }
        }
        for (int i = 0; i < 12; i++) {
            VertexRecorder recorder = section.backs[i];
            if (recorder != null && !recorder.data.isEmpty()) {
                backIds(section, i);
                out.submitCustomGeometry(poseStack, FifLayers.FRAME_LAYER, (pose, consumer) -> recorder.replay(consumer, pose.pose(), pose.normal(), ox, oy, oz));
            }
        }
        clearIds();
    }
    //#else
    private final MultiBufferSource recorder = type -> {
        VertexRecorder layer = layerFor(type);
        return layer != null ? layer : DISCARD;
    };

    private static final VertexRecorder DISCARD = new VertexRecorder();

    private void record(EntityRenderDispatcher dispatcher, ItemFrame frame, Section section, Vec3 camera, PoseStack poseStack) {
        DISCARD.clear();
        dispatcher.render(frame, frame.getX() - section.originX, frame.getY() - section.originY, frame.getZ() - section.originZ,
                1.0F, poseStack, recorder, dispatcher.getPackedLightCoords(frame, 1.0F));
    }

    public void render(MultiBufferSource buffers, Vec3 camera) {
        if (!replayable()) {
            return;
        }
        Frustum frustum = this.frustum;
        SectionCulling.begin(camera);
        boolean gpu = !shaders && SectionMesh.usable();
        for (Section section : sections.values()) {
            if (!section.hasData || section.dirty || frustum != null && !frustum.isVisible(section.bounds)
                    || !SectionCulling.isVisible(section.key, section.originX, section.originY, section.originZ) || occluded(section)) {
                continue;
            }
            float ox = (float) (section.originX - camera.x);
            float oy = (float) (section.originY - camera.y);
            float oz = (float) (section.originZ - camera.z);
            SectionMesh mesh = gpu ? mesh(section, camera) : null;
            for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
                if (!layer.getValue().data.isEmpty() && (mesh == null || !mesh.has(layer.getKey().type()))) {
                    ids(layer.getKey());
                    VertexConsumer consumer = buffers.getBuffer(type(layer.getKey().type()));
                    layer.getValue().replay(consumer, ox, oy, oz);
                }
            }
            int visibleBacks = 0;
            for (int i = 0; i < 12; i++) {
                if (backVisible(section, i, camera)) {
                    if (mesh != null && mesh.hasBack(i)) {
                        visibleBacks |= 1 << i;
                        continue;
                    }
                    backIds(section, i);
                    section.backs[i].replay(buffers.getBuffer(type(FifLayers.FRAME_LAYER)), ox, oy, oz);
                }
            }
            if (mesh != null) {
                mesh.queue(camera, visibleBacks);
            }
        }
        clearIds();
        SectionMesh.draw();
    }



    private RenderType type(RenderType type) {
        return shaders ? IrisCompat.wrapEntity(type) : type;
    }

    public boolean renderShadow(Entity entity, double x, double y, double z, PoseStack poseStack, MultiBufferSource buffers) {
        Section section = shadowSection(entity);
        if (section == null) {
            return false;
        }
        float ox = (float) (section.originX - (entity.getX() - x));
        float oy = (float) (section.originY - (entity.getY() - y));
        float oz = (float) (section.originZ - (entity.getZ() - z));
        PoseStack.Pose pose = poseStack.last();
        for (Map.Entry<Layer, VertexRecorder> layer : section.layers.entrySet()) {
            if (!layer.getValue().data.isEmpty()) {
                ids(layer.getKey());
                layer.getValue().replay(buffers.getBuffer(type(layer.getKey().type())), pose.pose(), pose.normal(), ox, oy, oz);
            }
        }
        for (int i = 0; i < 12; i++) {
            VertexRecorder recorder = section.backs[i];
            if (recorder != null && !recorder.data.isEmpty()) {
                backIds(section, i);
                recorder.replay(buffers.getBuffer(type(FifLayers.FRAME_LAYER)), pose.pose(), pose.normal(), ox, oy, oz);
            }
        }
        clearIds();
        return true;
    }
    //#endif

    private static SectionMesh mesh(Section section, Vec3 camera) {
        if (section.mesh == null) {
            section.mesh = new SectionMesh();
        }
        return section.mesh.ready(section.layers, section.backs, section.version, section.originX, section.originY, section.originZ, camera) ? section.mesh : null;
    }

    private void ids(Layer layer) {
        if (shaders) {
            IrisCompat.setIds(layer.entity(), layer.blockEntity(), layer.item());
        }
    }

    private void backIds(Section section, int index) {
        if (shaders) {
            IrisCompat.setIds(section.backEntities[index], 0, 0);
        }
    }

    private void clearIds() {
        if (shaders) {
            IrisCompat.setIds(0, 0, 0);
        }
    }

    private boolean replayable() {
        if (!active || sections.isEmpty() || shaders != Shaders.active()) {
            return false;
        }
        if (MapAtlas.INSTANCE.getGeneration() != atlasGeneration) {
            clearBakes();
            return false;
        }
        return true;
    }

    private void markDirty(Section section) {
        if (!section.dirty) {
            section.dirty = true;
            for (ItemFrame frame : section.frames) {
                ((BakeableFrame) frame).fif$setBaked(false);
            }
        }
    }

    private void neighborsChanged(BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Section section = sections.get(SectionPos.asLong(pos.offset(dx, dy, dz)));
                    if (section != null) {
                        markDirty(section);
                    }
                }
            }
        }
    }

    private void clearBakes() {
        for (Section section : sections.values()) {
            markDirty(section);
            section.hasData = false;
            for (VertexRecorder layer : section.layers.values()) {
                layer.clear();
            }
            section.layers.clear();
            clearBacks(section);
            section.quads.clear();
            section.spans.clear();
            section.version++;
            closeMesh(section);
        }
    }

    private static void closeMesh(Section section) {
        if (section.mesh != null) {
            section.mesh.close();
            section.mesh = null;
        }
    }

    private void clear() {
        for (Section section : sections.values()) {
            closeMesh(section);
        }
        decorationLayers.clear();
        sections.clear();
        mapFrames.clear();
        frustum = null;
    }

}
