package fastimageframes.bake;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import fastimageframes.FastImageFrames;
import fastimageframes.render.FifLayers;
import fastimageframes.render.VertexRecorder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
//#if MC >= 26.3
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import fastimageframes.render.FogShader;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.function.Consumer;
//#elif MC >= 1.21.6
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import fastimageframes.render.FogShader;
import net.minecraft.client.renderer.DynamicUniforms;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
//#if MC >= 26.2
import com.mojang.blaze3d.IndexType;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

import java.util.Optional;
import java.util.function.Consumer;
//#else
import com.mojang.blaze3d.vertex.VertexFormat.IndexType;

import java.util.OptionalInt;
//#endif
//#if MC >= 1.21.11 && MC < 26.2
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.joml.Matrix4fStack;

import java.util.function.Consumer;
//#endif
//#elif MC >= 1.21.5
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat.IndexType;
import fastimageframes.render.FogRange;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Matrix4fStack;

import java.util.OptionalDouble;
import java.util.OptionalInt;
//#else
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.vertex.VertexBuffer;
import fastimageframes.render.FogRange;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.FogParameters;
//#endif

final class SectionMesh {

    static boolean enabled = !Boolean.getBoolean("fastimageframes.cpuReplay");
    static int draws;

    private static final int UPLOAD_BUDGET = 1 << 18;
    private static final ByteBufferBuilder SCRATCH = new ByteBufferBuilder(1 << 20);
    private static final Reference2ObjectOpenHashMap<RenderType, Batch> BATCHES = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<RenderType, Boolean> SUPPORTED = new Reference2ObjectOpenHashMap<>();
    private static final List<Batch> QUEUED = new ArrayList<>();
    private static final List<Runnable> RETIRED = new ArrayList<>();
    //#if MC >= 1.21.6
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    //#endif
    private static int budget;
    private static boolean broken;

    //#if MC >= 1.21.5
    private record Part(RenderType type, GpuBuffer vertices, GpuBuffer indices, IndexType indexType, int indexCount, int back) {
    }
    //#else
    private record Part(RenderType type, VertexBuffer buffer, int back) {
    }
    //#endif

    private static final class Batch {
        final RenderType type;
        final List<Part> parts = new ArrayList<>();
        final List<Matrix4f> poses = new ArrayList<>();
        boolean queued;
        //#if MC >= 26.3
        PreparedRenderType prepared;
        GpuBufferSlice[] slices;
        GpuBuffer sequentialBuffer;
        IndexType sequentialType;
        //#endif

        Batch(RenderType type) {
            this.type = type;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    private final List<Runnable> buffers = new ArrayList<>();
    private final Set<RenderType> types = Collections.newSetFromMap(new IdentityHashMap<>());
    private int backs;
    private int version = -1;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    static boolean usable() {
        return enabled && !broken;
    }

    static void beginFrame() {
        budget = UPLOAD_BUDGET;
        for (Batch batch : QUEUED) {
            reset(batch);
        }
        QUEUED.clear();
        release();
    }

    boolean ready(Map<BakedFrames.Layer, VertexRecorder> layers, VertexRecorder[] backRecorders, int sectionVersion, int originX, int originY, int originZ,
                  Vec3 camera) {
        boolean built = version == sectionVersion;
        //#if MC >= 1.21.6
        if (built) {
            return true;
        }
        double ax = originX;
        double ay = originY;
        double az = originZ;
        //#else
        if (built) {
            fog();
            double dx = anchorX - camera.x;
            double dy = anchorY - camera.y;
            double dz = anchorZ - camera.z;
            double shift = FogRange.shift(far(originX, originY, originZ, camera));
            if (dx * dx + dy * dy + dz * dz <= shift * shift || budget <= 0) {
                return true;
            }
        }
        double ax = camera.x;
        double ay = camera.y;
        double az = camera.z;
        //#endif
        int vertices = 0;
        for (VertexRecorder recorder : layers.values()) {
            vertices += recorder.data.size() / VertexRecorder.STRIDE;
        }
        for (VertexRecorder recorder : backRecorders) {
            vertices += recorder == null ? 0 : recorder.data.size() / VertexRecorder.STRIDE;
        }
        if (vertices > budget && budget < UPLOAD_BUDGET) {
            return built;
        }
        budget -= vertices;
        try {
            build(layers, backRecorders, ax, ay, az, (float) (originX - ax), (float) (originY - ay), (float) (originZ - az));
        } catch (RuntimeException e) {
            FastImageFrames.LOGGER.warn("Could not keep baked frames on the GPU, they are copied each frame instead", e);
            broken = true;
            close();
            return false;
        }
        version = sectionVersion;
        return true;
    }

    //#if MC < 1.21.6
    private static void fog() {
        FogParameters fog = RenderSystem.getShaderFog();
        if (fog.shape() == FogShape.CYLINDER) {
            FogRange.set(1.0F, 0.0F, fog.start(), fog.end());
        } else {
            FogRange.set(fog.start(), fog.end(), Float.MAX_VALUE, Float.MAX_VALUE);
        }
    }

    private static double far(int originX, int originY, int originZ, Vec3 camera) {
        double fx = Math.max(Math.abs(originX - 1 - camera.x), Math.abs(originX + 17 - camera.x));
        double fy = Math.max(Math.abs(originY - 1 - camera.y), Math.abs(originY + 17 - camera.y));
        double fz = Math.max(Math.abs(originZ - 1 - camera.z), Math.abs(originZ + 17 - camera.z));
        return Math.max(Math.sqrt(fx * fx + fz * fz), fy);
    }
    //#endif

    private void build(Map<BakedFrames.Layer, VertexRecorder> layers, VertexRecorder[] backRecorders, double ax, double ay, double az, float ox, float oy,
                       float oz) {
        close();
        anchorX = ax;
        anchorY = ay;
        anchorZ = az;
        for (Map.Entry<BakedFrames.Layer, VertexRecorder> layer : layers.entrySet()) {
            RenderType type = layer.getKey().type();
            if (supported(type)) {
                upload(type, layer.getValue(), -1, ox, oy, oz);
                types.add(type);
            }
        }
        for (int i = 0; i < backRecorders.length; i++) {
            if (backRecorders[i] != null && supported(FifLayers.FRAME_LAYER)) {
                upload(FifLayers.FRAME_LAYER, backRecorders[i], i, ox, oy, oz);
                backs |= 1 << i;
            }
        }
    }

    private static boolean supported(RenderType type) {
        Boolean supported = SUPPORTED.get(type);
        if (supported == null) {
            supported = check(type);
            SUPPORTED.put(type, supported);
        }
        return supported;
    }

    private static boolean check(RenderType type) {
        if (type.sortOnUpload()) {
            return false;
        }
        //#if MC >= 26.3
        if (translucentPhase(type) && type.state.oitPipelineSet != null) {
            try {
                for (OitStage stage : OitStage.values()) {
                    if (FogShader.fog(type.state.oitPipelineSet.getPipeline(stage)) == null) {
                        return false;
                    }
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return FogShader.fog(pipeline(type)) != null;
        //#elif MC >= 1.21.11
        return FogShader.fog(pipeline(type)) != null;
        //#elif MC >= 1.21.6
        return type instanceof RenderType.CompositeRenderType && FogShader.fog(pipeline(type)) != null;
        //#else
        return true;
        //#endif
    }

    private void upload(RenderType type, VertexRecorder recorder, int back, float ox, float oy, float oz) {
        if (recorder.data.isEmpty()) {
            return;
        }
        BufferBuilder builder = builder(type);
        recorder.replay(builder, ox, oy, oz);
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        //#if MC >= 1.21.5
        try (mesh) {
            //#if MC >= 1.21.6
            GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "Fast ImageFrames baked vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            //#else
            GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "Fast ImageFrames baked vertices", BufferType.VERTICES, BufferUsage.STATIC_WRITE,
                    mesh.vertexBuffer());
            //#endif
            buffers.add(vertices::close);
            GpuBuffer indices = null;
            if (mesh.indexBuffer() != null) {
                //#if MC >= 1.21.6
                indices = RenderSystem.getDevice().createBuffer(() -> "Fast ImageFrames baked indices", GpuBuffer.USAGE_INDEX, mesh.indexBuffer());
                //#else
                indices = RenderSystem.getDevice().createBuffer(() -> "Fast ImageFrames baked indices", BufferType.INDICES, BufferUsage.STATIC_WRITE,
                        mesh.indexBuffer());
                //#endif
                buffers.add(indices::close);
            }
            parts.add(new Part(type, vertices, indices, mesh.drawState().indexType(), mesh.drawState().indexCount(), back));
        }
        //#else
        VertexBuffer buffer = new VertexBuffer(BufferUsage.STATIC_WRITE);
        buffers.add(buffer::close);
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        parts.add(new Part(type, buffer, back));
        //#endif
    }

    boolean has(RenderType type) {
        return types.contains(type);
    }

    boolean hasBack(int back) {
        return (backs & 1 << back) != 0;
    }

    void queue(Vec3 camera, int visibleBacks) {
        Matrix4f pose = new Matrix4f().translation((float) (anchorX - camera.x), (float) (anchorY - camera.y), (float) (anchorZ - camera.z));
        for (Part part : parts) {
            if (part.back >= 0 && (visibleBacks & 1 << part.back) == 0) {
                continue;
            }
            Batch batch = BATCHES.computeIfAbsent(part.type, Batch::new);
            batch.parts.add(part);
            batch.poses.add(pose);
            if (!batch.queued) {
                batch.queued = true;
                QUEUED.add(batch);
            }
        }
    }

    void close() {
        RETIRED.addAll(buffers);
        buffers.clear();
        parts.clear();
        types.clear();
        backs = 0;
        version = -1;
    }

    private static void release() {
        for (Runnable buffer : RETIRED) {
            buffer.run();
        }
        RETIRED.clear();
    }

    private static void reset(Batch batch) {
        batch.queued = false;
        batch.parts.clear();
        batch.poses.clear();
        //#if MC >= 26.3
        batch.prepared = null;
        batch.slices = null;
        batch.sequentialBuffer = null;
        //#endif
    }

    private static BufferBuilder builder(RenderType type) {
        //#if MC >= 26.2
        return new BufferBuilder(SCRATCH, type.primitiveTopology(), type.format());
        //#else
        return new BufferBuilder(SCRATCH, type.mode(), type.format());
        //#endif
    }

    //#if MC >= 1.21.5
    private static RenderSystem.AutoStorageIndexBuffer sequential(RenderType type) {
        //#if MC >= 26.2
        return RenderSystem.getSequentialBuffer(type.primitiveTopology());
        //#else
        return RenderSystem.getSequentialBuffer(type.mode());
        //#endif
    }
    //#endif

    //#if MC >= 1.21.6
    private static RenderPipeline pipeline(RenderType type) {
        //#if MC >= 1.21.11
        return type.pipeline();
        //#else
        return ((RenderType.CompositeRenderType) type).renderPipeline;
        //#endif
    }

    private static Vector3f offset(Matrix4f pose) {
        return new Vector3f(pose.m30(), pose.m31(), pose.m32());
    }
    //#endif

    //#if MC >= 26.3
    static boolean translucentPhase(RenderType type) {
        return type.hasBlending() && !type.forceSolidModelPhase();
    }

    static void draw() {
    }

    static void prepare() {
        try {
            for (Batch batch : QUEUED) {
                if (!broken) {
                    prepare(batch);
                }
            }
        } catch (RuntimeException e) {
            FastImageFrames.LOGGER.warn("Could not draw baked frames from the GPU, they are copied each frame instead", e);
            broken = true;
        }
    }

    private static void prepare(Batch batch) {
        RenderType type = batch.type;
        batch.prepared = type.prepare();
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        Consumer<Matrix4f> layering = type.state.layeringTransform.getModifier();
        if (layering != null) {
            layering.accept(modelView);
        }
        Matrix4f textures = type.state.textureTransform.createMatrix();
        int count = batch.parts.size();
        DynamicGpuData.Transform[] transforms = new DynamicGpuData.Transform[count];
        int sequential = 0;
        for (int i = 0; i < count; i++) {
            Matrix4f pose = batch.poses.get(i);
            transforms[i] = new DynamicGpuData.Transform(new Matrix4f(modelView).mul(pose), WHITE, offset(pose), textures);
            Part part = batch.parts.get(i);
            if (part.indices == null) {
                sequential = Math.max(sequential, part.indexCount);
            }
        }
        if (sequential > 0) {
            RenderSystem.AutoStorageIndexBuffer indices = sequential(type);
            batch.sequentialBuffer = indices.getBuffer(sequential);
            batch.sequentialType = indices.type();
        }
        batch.slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
    }

    static void draw(RenderPass pass, boolean sorted, OitStage stage, boolean last) {
        if (QUEUED.isEmpty()) {
            return;
        }
        try {
            for (Batch batch : QUEUED) {
                if (!broken && batch.slices != null && translucentPhase(batch.type) == sorted) {
                    draw(pass, batch, stage);
                }
            }
        } catch (RuntimeException e) {
            FastImageFrames.LOGGER.warn("Could not draw baked frames from the GPU, they are copied each frame instead", e);
            broken = true;
        } finally {
            if (last || broken) {
                QUEUED.removeIf(batch -> {
                    if (broken || translucentPhase(batch.type) == sorted) {
                        reset(batch);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    private static void draw(RenderPass pass, Batch batch, OitStage stage) {
        PreparedRenderType prepared = batch.prepared;
        RenderPipeline pipeline = FogShader.fog(stage == null ? prepared.pipeline() : prepared.oitPipelineSet().getPipeline(stage));
        if (pipeline == null) {
            return;
        }
        pass.pushDebugGroup(() -> "Fast ImageFrames baked frames");
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            if (prepared.scissorState().enabled()) {
                pass.enableScissor(prepared.scissorState().x(), prepared.scissorState().y(), prepared.scissorState().width(), prepared.scissorState().height());
            }
            RenderSystem.bindDefaultUniforms(pass);
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
            }
            for (int i = 0; i < batch.parts.size(); i++) {
                Part part = batch.parts.get(i);
                pass.setUniform("DynamicTransforms", batch.slices[i]);
                pass.setVertexBuffer(0, part.vertices.slice());
                if (part.indices != null) {
                    pass.setIndexBuffer(part.indices, part.indexType);
                } else {
                    pass.setIndexBuffer(batch.sequentialBuffer, batch.sequentialType);
                }
                pass.drawIndexed(part.indexCount, 1, 0, 0, 0);
                draws++;
            }
        } finally {
            pass.popDebugGroup();
        }
    }
    //#else
    static void draw() {
        if (QUEUED.isEmpty()) {
            return;
        }
        try {
            for (Batch batch : QUEUED) {
                if (!broken && !batch.parts.isEmpty()) {
                    draw(batch);
                }
            }
        } catch (RuntimeException e) {
            FastImageFrames.LOGGER.warn("Could not draw baked frames from the GPU, they are copied each frame instead", e);
            broken = true;
        } finally {
            for (Batch batch : QUEUED) {
                reset(batch);
            }
            QUEUED.clear();
        }
    }
    //#endif

    //#if MC >= 1.21.6 && MC < 26.3
    private static void draw(Batch batch) {
        RenderType type = batch.type;
        RenderPipeline pipeline = FogShader.fog(pipeline(type));
        if (pipeline == null) {
            return;
        }
        //#if MC >= 26.2
        PreparedRenderType prepared = type.prepare();
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        Consumer<Matrix4f> layering = type.state.layeringTransform.getModifier();
        if (layering != null) {
            layering.accept(modelView);
        }
        Matrix4f textures = type.state.textureTransform.createMatrix();
        //#elif MC >= 1.21.11
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        Consumer<Matrix4fStack> layering = type.state.layeringTransform.getModifier();
        if (layering != null) {
            stack.pushMatrix();
            layering.accept(stack);
        }
        Matrix4f textures = type.state.textureTransform.getMatrix();
        Matrix4f modelView = RenderSystem.getModelViewMatrix();
        //#else
        type.setupRenderState();
        Matrix4f textures = RenderSystem.getTextureMatrix();
        Matrix4f modelView = RenderSystem.getModelViewMatrix();
        //#endif
        try {
            int count = batch.parts.size();
            DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[count];
            int sequential = 0;
            for (int i = 0; i < count; i++) {
                Matrix4f pose = batch.poses.get(i);
                transforms[i] = transform(new Matrix4f(modelView).mul(pose), textures, offset(pose));
                Part part = batch.parts.get(i);
                if (part.indices == null) {
                    sequential = Math.max(sequential, part.indexCount);
                }
            }
            GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
            GpuBuffer sequentialBuffer = null;
            IndexType sequentialType = null;
            if (sequential > 0) {
                RenderSystem.AutoStorageIndexBuffer indices = sequential(type);
                sequentialBuffer = indices.getBuffer(sequential);
                sequentialType = indices.type();
            }
            //#if MC >= 26.2
            RenderTarget target = prepared.outputTarget().getRenderTarget();
            ScissorState scissor = prepared.scissorState();
            //#elif MC >= 1.21.11
            RenderTarget target = type.state.outputTarget.getRenderTarget();
            ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            //#else
            RenderTarget target = ((RenderType.CompositeRenderType) type).state.outputState.getRenderTarget();
            ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
            //#endif
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            GpuTextureView depth = target.useDepth
                    ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView())
                    : null;
            try (RenderPass pass = open(color, depth)) {
                pass.setPipeline(pipeline);
                if (scissor.enabled()) {
                    pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                }
                RenderSystem.bindDefaultUniforms(pass);
                //#if MC >= 26.2
                for (PreparedRenderType.Texture texture : prepared.textures()) {
                    pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
                }
                //#elif MC >= 1.21.11
                for (Map.Entry<String, RenderSetup.TextureAndSampler> texture : type.state.getTextures().entrySet()) {
                    pass.bindTexture(texture.getKey(), texture.getValue().textureView(), texture.getValue().sampler());
                }
                //#else
                for (int i = 0; i < 12; i++) {
                    GpuTextureView texture = RenderSystem.getShaderTexture(i);
                    if (texture != null) {
                        pass.bindSampler("Sampler" + i, texture);
                    }
                }
                //#endif
                for (int i = 0; i < count; i++) {
                    Part part = batch.parts.get(i);
                    pass.setUniform("DynamicTransforms", slices[i]);
                    //#if MC >= 26.2
                    pass.setVertexBuffer(0, part.vertices.slice());
                    pass.setIndexBuffer(part.indices != null ? part.indices : sequentialBuffer, part.indices != null ? part.indexType : sequentialType);
                    pass.drawIndexed(part.indexCount, 1, 0, 0, 0);
                    //#else
                    pass.setVertexBuffer(0, part.vertices);
                    pass.setIndexBuffer(part.indices != null ? part.indices : sequentialBuffer, part.indices != null ? part.indexType : sequentialType);
                    pass.drawIndexed(0, 0, part.indexCount, 1);
                    //#endif
                    draws++;
                }
            }
        } finally {
            //#if MC >= 1.21.11 && MC < 26.2
            if (layering != null) {
                stack.popMatrix();
            }
            //#elif MC < 1.21.11
            type.clearRenderState();
            //#endif
        }
    }

    private static DynamicUniforms.Transform transform(Matrix4f modelView, Matrix4f textures, Vector3f offset) {
        //#if MC >= 1.21.11
        return new DynamicUniforms.Transform(modelView, WHITE, offset, textures);
        //#else
        return new DynamicUniforms.Transform(modelView, WHITE, offset, textures, RenderSystem.getShaderLineWidth());
        //#endif
    }

    private static RenderPass open(GpuTextureView color, GpuTextureView depth) {
        //#if MC >= 26.2
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Fast ImageFrames baked frames", color, Optional.empty(), depth,
                OptionalDouble.empty());
        //#else
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Fast ImageFrames baked frames", color, OptionalInt.empty(), depth,
                OptionalDouble.empty());
        //#endif
    }
    //#elif MC < 1.21.6
    private static void draw(Batch batch) {
        RenderType type = batch.type;
        type.setupRenderState();
        try {
            //#if MC >= 1.21.5
            int sequential = 0;
            for (Part part : batch.parts) {
                if (part.indices == null) {
                    sequential = Math.max(sequential, part.indexCount);
                }
            }
            GpuBuffer sequentialBuffer = null;
            IndexType sequentialType = null;
            if (sequential > 0) {
                RenderSystem.AutoStorageIndexBuffer indices = sequential(type);
                sequentialBuffer = indices.getBuffer(sequential);
                sequentialType = indices.type();
            }
            RenderTarget target = type.getRenderTarget();
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(target.getColorTexture(), OptionalInt.empty(), target.useDepth ? target.getDepthTexture() : null, OptionalDouble.empty())) {
                pass.setPipeline(type.getRenderPipeline());
                if (RenderSystem.SCISSOR_STATE.isEnabled()) {
                    pass.enableScissor(RenderSystem.SCISSOR_STATE);
                }
                for (int i = 0; i < 12; i++) {
                    GpuTexture texture = RenderSystem.getShaderTexture(i);
                    if (texture != null) {
                        pass.bindSampler("Sampler" + i, texture);
                    }
                }
                for (int i = 0; i < batch.parts.size(); i++) {
                    Part part = batch.parts.get(i);
                    stack.pushMatrix();
                    stack.mul(batch.poses.get(i));
                    try {
                        pass.setVertexBuffer(0, part.vertices);
                        pass.setIndexBuffer(part.indices != null ? part.indices : sequentialBuffer, part.indices != null ? part.indexType : sequentialType);
                        pass.drawIndexed(0, part.indexCount);
                    } finally {
                        stack.popMatrix();
                    }
                    draws++;
                }
            }
            //#else
            CompiledShaderProgram shader = RenderSystem.getShader();
            Matrix4f modelView = RenderSystem.getModelViewMatrix();
            for (int i = 0; i < batch.parts.size(); i++) {
                Part part = batch.parts.get(i);
                part.buffer.bind();
                part.buffer.drawWithShader(new Matrix4f(modelView).mul(batch.poses.get(i)), RenderSystem.getProjectionMatrix(), shader);
                draws++;
            }
            VertexBuffer.unbind();
            //#endif
        } finally {
            type.clearRenderState();
        }
    }
    //#endif
}
