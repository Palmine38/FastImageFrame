package fastimageframes.render;

import fastimageframes.render.Cameras;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fastimageframes.atlas.MapAtlas;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fastimageframes.bake.BakedFrames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//#if MC >= 1.21.9
import net.minecraft.data.AtlasIds;
//#endif
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class OptimizedFrameMesh {

    public static final int UP = 1;
    public static final int DOWN = 2;
    public static final int WEST = 4;
    public static final int EAST = 8;
    public static final int FRONT = 16;
    public static final int BACK = 32;

    private enum Tex { WOOD, BACK }

    private record Quad(int group, Tex tex, float[] xyz, float[] uv, float nx, float ny, float nz) {
    }

    private static final Identifier BIRCH = Identifier.withDefaultNamespace("block/birch_planks");
    private static final Identifier FRAME_BACK = Identifier.withDefaultNamespace("block/item_frame");
    private static final Identifier GLOW_FRAME_BACK = Identifier.withDefaultNamespace("block/glow_item_frame");
    private static final Identifier[] FRAME_MODEL_FILES = {
            Identifier.withDefaultNamespace("blockstates/item_frame.json"),
            Identifier.withDefaultNamespace("models/block/item_frame_map.json"),
            Identifier.withDefaultNamespace("models/block/template_item_frame_map.json")
    };
    private static final Identifier[] GLOW_FRAME_MODEL_FILES = {
            Identifier.withDefaultNamespace("blockstates/glow_item_frame.json"),
            Identifier.withDefaultNamespace("models/block/glow_item_frame_map.json"),
            Identifier.withDefaultNamespace("models/block/template_item_frame_map.json")
    };

    private static final List<Quad> QUADS = new ArrayList<>();
    private static final Direction[] LOCAL_EAST = new Direction[6];
    private static final Direction[] LOCAL_UP = new Direction[6];

    private static TextureAtlasSprite birch;
    private static TextureAtlasSprite frameBack;
    private static TextureAtlasSprite glowFrameBack;
    private static boolean spritesStale = true;
    private static TextureAtlasSprite checkedBirch;
    private static boolean vanillaFrameModel = true;
    private static boolean vanillaGlowFrameModel = true;

    static {
        float zf = 15.001F;
        face(FRONT, Tex.BACK, 1, 1, zf, 15, 15, 16, Direction.NORTH, 1, 1, 15, 15);
        face(BACK, Tex.BACK, 1, 1, zf, 15, 15, 16, Direction.SOUTH, 1, 1, 15, 15);
        face(DOWN, Tex.WOOD, 0, 0, zf, 16, 1, 16, Direction.DOWN, 0, 0, 16, 1);
        face(FRONT, Tex.WOOD, 0, 0, zf, 16, 1, 16, Direction.NORTH, 0, 15, 16, 16);
        face(BACK, Tex.WOOD, 0, 0, zf, 16, 1, 16, Direction.SOUTH, 0, 15, 16, 16);
        face(WEST, Tex.WOOD, 0, 0, zf, 16, 1, 16, Direction.WEST, 15, 15, 16, 16);
        face(EAST, Tex.WOOD, 0, 0, zf, 16, 1, 16, Direction.EAST, 0, 15, 1, 16);
        face(UP, Tex.WOOD, 0, 15, zf, 16, 16, 16, Direction.UP, 0, 15, 16, 16);
        face(FRONT, Tex.WOOD, 0, 15, zf, 16, 16, 16, Direction.NORTH, 0, 0, 16, 1);
        face(BACK, Tex.WOOD, 0, 15, zf, 16, 16, 16, Direction.SOUTH, 0, 0, 16, 1);
        face(WEST, Tex.WOOD, 0, 15, zf, 16, 16, 16, Direction.WEST, 15, 0, 16, 1);
        face(EAST, Tex.WOOD, 0, 15, zf, 16, 16, 16, Direction.EAST, 0, 0, 1, 1);
        face(FRONT, Tex.WOOD, 0, 1, zf, 1, 15, 16, Direction.NORTH, 15, 1, 16, 15);
        face(BACK, Tex.WOOD, 0, 1, zf, 1, 15, 16, Direction.SOUTH, 0, 1, 1, 15);
        face(WEST, Tex.WOOD, 0, 1, zf, 1, 15, 16, Direction.WEST, 15, 1, 16, 15);
        face(FRONT, Tex.WOOD, 15, 1, zf, 16, 15, 16, Direction.NORTH, 0, 1, 1, 15);
        face(BACK, Tex.WOOD, 15, 1, zf, 16, 15, 16, Direction.SOUTH, 15, 1, 16, 15);
        face(EAST, Tex.WOOD, 15, 1, zf, 16, 15, 16, Direction.EAST, 0, 1, 1, 15);

        for (Direction facing : Direction.values()) {
            float xRot;
            float yRot;
            if (facing.getAxis().isHorizontal()) {
                xRot = 0F;
                yRot = 180F - facing.toYRot();
            } else {
                xRot = -90 * facing.getAxisDirection().getStep();
                yRot = 180F;
            }
            Quaternionf rotation = new Quaternionf().rotateX((float) Math.toRadians(xRot)).rotateY((float) Math.toRadians(yRot));
            LOCAL_EAST[facing.ordinal()] = nearest(rotation.transform(new Vector3f(1, 0, 0)));
            LOCAL_UP[facing.ordinal()] = nearest(rotation.transform(new Vector3f(0, 1, 0)));
        }
    }

    private OptimizedFrameMesh() {
    }

    public static void onFrameStart() {
        spritesStale = true;
    }

    public static boolean hasVanillaModel(boolean glow) {
        if (spritesStale) {
            refreshSprites();
        }
        return glow ? vanillaGlowFrameModel : vanillaFrameModel;
    }

    public static int visibleFaces(ItemFrameRenderState state) {
        Direction facing = state.direction;
        long pos = BlockPos.asLong(Mth.floor(state.x), Mth.floor(state.y), Mth.floor(state.z));
        Direction east = LOCAL_EAST[facing.ordinal()];
        Direction up = LOCAL_UP[facing.ordinal()];
        int faces = 0;
        if (!FrameNeighbors.has(BlockPos.offset(pos, up), facing)) {
            faces |= UP;
        }
        if (!FrameNeighbors.has(BlockPos.offset(pos, up.getOpposite()), facing)) {
            faces |= DOWN;
        }
        if (!FrameNeighbors.has(BlockPos.offset(pos, east), facing)) {
            faces |= EAST;
        }
        if (!FrameNeighbors.has(BlockPos.offset(pos, east.getOpposite()), facing)) {
            faces |= WEST;
        }
        Vec3 camera = Cameras.position();
        if (BakedFrames.INSTANCE.isBaking() || (camera.x - state.x) * facing.getStepX() + (camera.y - state.y) * facing.getStepY() + (camera.z - state.z) * facing.getStepZ() < 0) {
            faces |= BACK;
        }
        if (state.mapId != null && MapAtlas.INSTANCE.isTransparent(state.mapId.id())) {
            faces |= FRONT;
        }
        return faces;
    }

    public static void emit(PoseStack.Pose pose, VertexConsumer consumer, int faces, boolean glow, int light) {
        if (spritesStale) {
            refreshSprites();
        }
        TextureAtlasSprite back = glow ? glowFrameBack : frameBack;
        VertexConsumer backConsumer = (faces & BACK) != 0 ? BakedFrames.INSTANCE.backLayer(consumer) : consumer;
        for (Quad quad : QUADS) {
            if ((faces & quad.group) == 0) {
                continue;
            }
            TextureAtlasSprite sprite = quad.tex == Tex.WOOD ? birch : back;
            float[] xyz = quad.xyz;
            float[] uv = quad.uv;
            VertexConsumer out = quad.group == BACK ? backConsumer : consumer;
            for (int i = 0; i < 4; i++) {
                out.addVertex(pose, xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
                        .setColor(-1)
                        .setUv(sprite.getU(uv[i * 2]), sprite.getV(uv[i * 2 + 1]))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, quad.nx, quad.ny, quad.nz);
            }
        }
    }

    private static void refreshSprites() {
        //#if MC >= 1.21.9
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        //#else
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        //#endif
        birch = atlas.getSprite(BIRCH);
        frameBack = atlas.getSprite(FRAME_BACK);
        glowFrameBack = atlas.getSprite(GLOW_FRAME_BACK);
        spritesStale = false;
        if (birch != checkedBirch) {
            checkedBirch = birch;
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            vanillaFrameModel = fromVanilla(resources, FRAME_MODEL_FILES);
            vanillaGlowFrameModel = fromVanilla(resources, GLOW_FRAME_MODEL_FILES);
        }
    }

    private static boolean fromVanilla(ResourceManager resources, Identifier[] files) {
        for (Identifier file : files) {
            Optional<Resource> resource = resources.getResource(file);
            if (resource.isEmpty() || "vanilla".equals(resource.get().sourcePackId())) {
                continue;
            }
            String pack = resource.get().sourcePackId();
            boolean fromMod = "fabric".equals(pack) || FabricLoader.getInstance().isModLoaded(pack);
            if (!fromMod || !keepsFrameShape(resource.get())) {
                return false;
            }
        }
        return true;
    }

    private static boolean keepsFrameShape(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (!json.has("elements")) {
                return false;
            }
            float[] min = {16F, 16F, 16F};
            float[] max = {0F, 0F, 0F};
            for (JsonElement element : json.getAsJsonArray("elements")) {
                JsonArray from = element.getAsJsonObject().getAsJsonArray("from");
                JsonArray to = element.getAsJsonObject().getAsJsonArray("to");
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], Math.min(from.get(i).getAsFloat(), to.get(i).getAsFloat()));
                    max[i] = Math.max(max[i], Math.max(from.get(i).getAsFloat(), to.get(i).getAsFloat()));
                }
            }
            return min[0] < 0.01F && min[1] < 0.01F && max[0] > 15.99F && max[1] > 15.99F && min[2] > 14.99F && max[2] > 15.99F;
        } catch (Exception e) {
            return false;
        }
    }

    private static void face(int group, Tex tex, float x0, float y0, float z0, float x1, float y1, float z1, Direction side,
                             float u0, float v0, float u1, float v1) {
        float[][] corners = switch (side) {
            case NORTH -> new float[][] {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}};
            case SOUTH -> new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][] {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][] {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case DOWN -> new float[][] {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            case UP -> new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        };
        float[][] auto = new float[4][2];
        for (int i = 0; i < 4; i++) {
            float x = corners[i][0];
            float y = corners[i][1];
            float z = corners[i][2];
            auto[i] = switch (side) {
                case NORTH -> new float[] {16 - x, 16 - y};
                case SOUTH -> new float[] {x, 16 - y};
                case WEST -> new float[] {z, 16 - y};
                case EAST -> new float[] {16 - z, 16 - y};
                case DOWN -> new float[] {x, 16 - z};
                case UP -> new float[] {x, z};
            };
        }
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (float[] a : auto) {
            minU = Math.min(minU, a[0]);
            maxU = Math.max(maxU, a[0]);
            minV = Math.min(minV, a[1]);
            maxV = Math.max(maxV, a[1]);
        }
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            float fu = maxU > minU ? (auto[i][0] - minU) / (maxU - minU) : 0F;
            float fv = maxV > minV ? (auto[i][1] - minV) / (maxV - minV) : 0F;
            uv[i * 2] = Mth.lerp(fu, u0, u1) / 16F;
            uv[i * 2 + 1] = Mth.lerp(fv, v0, v1) / 16F;
        }
        Vector3f normal = new Vector3f(side.getStepX(), side.getStepY(), side.getStepZ());
        Vector3f e1 = new Vector3f(corners[1][0] - corners[0][0], corners[1][1] - corners[0][1], corners[1][2] - corners[0][2]);
        Vector3f e2 = new Vector3f(corners[2][0] - corners[0][0], corners[2][1] - corners[0][1], corners[2][2] - corners[0][2]);
        boolean reverse = e1.cross(e2).dot(normal) < 0;
        float[] xyz = new float[12];
        float[] orderedUv = new float[8];
        for (int i = 0; i < 4; i++) {
            int src = reverse ? 3 - i : i;
            xyz[i * 3] = corners[src][0] / 16F;
            xyz[i * 3 + 1] = corners[src][1] / 16F;
            xyz[i * 3 + 2] = corners[src][2] / 16F;
            orderedUv[i * 2] = uv[src * 2];
            orderedUv[i * 2 + 1] = uv[src * 2 + 1];
        }
        QUADS.add(new Quad(group, tex, xyz, orderedUv, normal.x, normal.y, normal.z));
    }

    private static Direction nearest(Vector3f vector) {
        int x = Math.round(vector.x);
        int y = Math.round(vector.y);
        int z = Math.round(vector.z);
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == x && direction.getStepY() == y && direction.getStepZ() == z) {
                return direction;
            }
        }
        throw new IllegalStateException("Not an axis: " + vector);
    }

}
