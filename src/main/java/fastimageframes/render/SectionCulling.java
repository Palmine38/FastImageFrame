package fastimageframes.render;

import fastimageframes.FastImageFrames;
import fastimageframes.mixin.LevelRendererAccessor;
import fastimageframes.mixin.ViewAreaAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class SectionCulling {

    private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static MethodHandle sodiumInstance;
    private static MethodHandle sodiumBoxVisible;

    private static Object sodium;
    private static SectionOcclusionGraph graph;
    private static ViewAreaAccessor viewArea;
    private static boolean failed;

    static {
        if (SODIUM) {
            try {
                Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                sodiumInstance = lookup.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer));
                sodiumBoxVisible = lookup.findVirtual(renderer, "isBoxVisible", MethodType.methodType(boolean.class,
                        double.class, double.class, double.class, double.class, double.class, double.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                sodiumInstance = null;
                FastImageFrames.LOGGER.info("Sodium section visibility not found, baked map walls are only frustum culled", e);
            }
        }
    }

    private SectionCulling() {
    }

    public static void begin(Vec3 camera) {
        sodium = null;
        graph = null;
        viewArea = null;
        if (failed) {
            return;
        }
        try {
            if (SODIUM) {
                if (sodiumInstance != null) {
                    sodium = sodiumInstance.invoke();
                }
                return;
            }
            LevelRendererAccessor renderer = (LevelRendererAccessor) Minecraft.getInstance().levelRenderer;
            ViewArea area = renderer.fif$viewArea();
            SectionOcclusionGraph occlusion = renderer.fif$sectionOcclusionGraph();
            if (area == null || occlusion == null) {
                return;
            }
            SectionRenderDispatcher.RenderSection cameraSection = ((ViewAreaAccessor) area).fif$getRenderSection(SectionPos.asLong(BlockPos.containing(camera)));
            if (cameraSection != null && occlusion.getNode(cameraSection) != null) {
                graph = occlusion;
                viewArea = (ViewAreaAccessor) area;
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    public static boolean isVisible(long section, int originX, int originY, int originZ) {
        try {
            if (sodium != null) {
                return (boolean) sodiumBoxVisible.invoke(sodium, originX + 1.0, originY + 1.0, originZ + 1.0, originX + 15.0, originY + 15.0, originZ + 15.0);
            }
            if (graph != null) {
                SectionRenderDispatcher.RenderSection renderSection = viewArea.fif$getRenderSection(section);
                return renderSection == null || graph.getNode(renderSection) != null;
            }
        } catch (Throwable e) {
            fail(e);
        }
        return true;
    }

    private static void fail(Throwable e) {
        failed = true;
        sodium = null;
        graph = null;
        FastImageFrames.LOGGER.warn("Section visibility unavailable, baked map walls are only frustum culled", e);
    }

}
