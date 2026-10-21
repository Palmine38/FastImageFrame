package fastimageframes.render;

//#if MC >= 1.21.6
//#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.util.HashMap;
//#else
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#if MC >= 26.2
import com.mojang.blaze3d.pipeline.ColorTargetState;
//#endif
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
//#endif

public final class FogShader {

    //#if MC >= 1.21.6
    private static final String NAMESPACE = "fastimageframes";
    private static final String PREFIX = "fog/";
    private static final Pattern FOG = Pattern.compile("\\b(fog_spherical_distance|fog_cylindrical_distance)\\s*\\(\\s*Position\\s*\\)");
    private static final Pattern PROJECTED = Pattern.compile("ProjMat\\s*\\*\\s*ModelViewMat\\s*\\*\\s*vec4\\s*\\(\\s*Position\\s*,\\s*1\\.0\\s*\\)");
    private static final Pattern POSITION = Pattern.compile("\\bPosition\\b");
    private static final Pattern MAIN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");

    private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
    private static final Map<RenderPipeline, Boolean> VALID = new IdentityHashMap<>();
    //#endif

    private FogShader() {
    }

    //#if MC >= 1.21.6
    public static RenderPipeline fog(RenderPipeline base) {
        RenderPipeline pipeline = PIPELINES.computeIfAbsent(base, FogShader::derive);
        return VALID.computeIfAbsent(pipeline, FogShader::compiles) ? pipeline : null;
    }

    private static boolean compiles(RenderPipeline pipeline) {
        //#if MC >= 26.3
        return RenderSystem.getCompiledPipelineNullable(pipeline) != null;
        //#else
        return RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
        //#endif
    }

    private static RenderPipeline derive(RenderPipeline base) {
        //#if MC >= 26.3
        Identifier vertex = base.getShaders().get(ShaderType.VERTEX);
        //#else
        Identifier vertex = base.getVertexShader();
        //#endif
        Identifier shader = Identifier.fromNamespaceAndPath(NAMESPACE, PREFIX + vertex.getNamespace() + "/" + vertex.getPath());
        Identifier location = Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/" + PREFIX + base.getLocation().getNamespace() + "/" + base.getLocation().getPath());
        //#if MC >= 26.3
        ColorTargetState[] targets = base.getColorTargetStates().toArray(new ColorTargetState[0]);
        Map<ShaderType, Identifier> shaders = new HashMap<>(base.getShaders());
        shaders.put(ShaderType.VERTEX, shader);
        return RenderPipeline.builder(new RenderPipeline.Snippet(shaders, Optional.of(base.getShaderDefines()),
                        Optional.of(base.getBindGroupLayouts()), targets, targets.length, Optional.ofNullable(base.getDepthStencilState()), Optional.of(base.getPolygonMode()),
                        Optional.of(base.isCull()), base.getVertexFormatBindings().toArray(new VertexFormat[0]), Optional.of(base.getPrimitiveTopology()),
                        base.pushConstantSize()))
                .withLocation(location)
                .withDepthStencilState(Optional.ofNullable(base.getDepthStencilState()))
                .build();
        //#elif MC >= 26.2
        ColorTargetState[] targets = base.getColorTargetStates().clone();
        int active = 0;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != null) {
                active = i + 1;
            }
        }
        return RenderPipeline.builder(new RenderPipeline.Snippet(Optional.of(shader), Optional.of(base.getFragmentShader()),
                        Optional.of(base.getShaderDefines()), Optional.of(base.getBindGroupLayouts()), targets, active,
                        Optional.ofNullable(base.getDepthStencilState()), Optional.of(base.getPolygonMode()), Optional.of(base.isCull()),
                        base.getVertexFormatBindings().clone(), Optional.of(base.getPrimitiveTopology())))
                .withLocation(location)
                .withDepthStencilState(Optional.ofNullable(base.getDepthStencilState()))
                .build();
        //#elif MC >= 26.1
        return RenderPipeline.builder(new RenderPipeline.Snippet(Optional.of(shader), Optional.of(base.getFragmentShader()),
                        Optional.of(base.getShaderDefines()), Optional.of(base.getSamplers()), Optional.of(base.getUniforms()), Optional.of(base.getColorTargetState()),
                        Optional.ofNullable(base.getDepthStencilState()), Optional.of(base.getPolygonMode()), Optional.of(base.isCull()),
                        Optional.of(base.getVertexFormat()), Optional.of(base.getVertexFormatMode())))
                .withLocation(location)
                .withDepthStencilState(Optional.ofNullable(base.getDepthStencilState()))
                .build();
        //#else
        return RenderPipeline.builder(new RenderPipeline.Snippet(Optional.of(shader), Optional.of(base.getFragmentShader()),
                        Optional.of(base.getShaderDefines()), Optional.of(base.getSamplers()), Optional.of(base.getUniforms()), base.getBlendFunction(),
                        Optional.of(base.getDepthTestFunction()), Optional.empty(), Optional.of(base.isCull()), Optional.of(base.isWriteColor()),
                        Optional.of(base.isWriteAlpha()), Optional.of(base.isWriteDepth()), Optional.of(base.getColorLogic()), Optional.of(base.getVertexFormat()),
                        Optional.of(base.getVertexFormatMode())))
                .withLocation(location)
                .withPolygonMode(base.getPolygonMode())
                .withDepthBias(base.getDepthBiasScaleFactor(), base.getDepthBiasConstant())
                .build();
        //#endif
    }

    public static Identifier original(Identifier id) {
        if (!id.getNamespace().equals(NAMESPACE) || !id.getPath().startsWith(PREFIX)) {
            return null;
        }
        String rest = id.getPath().substring(PREFIX.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? null : Identifier.fromNamespaceAndPath(rest.substring(0, slash), rest.substring(slash + 1));
    }

    public static String patch(String source) {
        if (source == null || !source.contains("ModelOffset") && !source.contains("dynamictransforms")) {
            return null;
        }
        Matcher main = MAIN.matcher(source);
        if (!main.find()) {
            return null;
        }
        String body = source.substring(main.end());
        if (POSITION.matcher(FOG.matcher(PROJECTED.matcher(body).replaceAll("")).replaceAll("")).find()) {
            return null;
        }
        return source.substring(0, main.end()) + FOG.matcher(body).replaceAll("$1(Position + ModelOffset)");
    }
    //#endif
}
