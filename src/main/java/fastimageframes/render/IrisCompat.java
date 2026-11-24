package fastimageframes.render;

import fastimageframes.FastImageFrames;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
//#if MC < 1.21.9
import net.minecraft.client.renderer.rendertype.RenderType;
//#endif

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.Map;

public final class IrisCompat {

    private static boolean tried;
    private static boolean available;
    private static Object capturedState;
    private static MethodHandle setCurrentBlockEntity;
    private static MethodHandle setCurrentEntity;
    private static MethodHandle setCurrentItem;
    private static MethodHandle currentBlockEntity;
    private static MethodHandle currentItem;
    private static Object settings;
    private static MethodHandle entityIds;
    private static Constructor<?> namespacedId;
    private static final Map<EntityType<?>, Object> ENTITY_KEYS = new Reference2ObjectOpenHashMap<>();
    private static MethodHandle beginEntities;
    private static MethodHandle endEntities;
    private static java.lang.reflect.Field renderingLevel;
    //#if MC < 1.21.9
    private static MethodHandle wrapExactlyOnce;
    private static Object entityShard;
    private static Class<?> wrappable;
    private static MethodHandle unwrap;
    //#endif

    private IrisCompat() {
    }

    public static boolean available() {
        if (!tried) {
            tried = true;
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> captured = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                capturedState = captured.getField("INSTANCE").get(null);
                setCurrentBlockEntity = lookup.unreflect(captured.getMethod("setCurrentBlockEntity", int.class));
                setCurrentEntity = lookup.unreflect(captured.getMethod("setCurrentEntity", int.class));
                setCurrentItem = lookup.unreflect(captured.getMethod("setCurrentRenderedItem", int.class));
                currentBlockEntity = lookup.unreflect(captured.getMethod("getCurrentRenderedBlockEntity"));
                currentItem = lookup.unreflect(captured.getMethod("getCurrentRenderedItem"));
                Class<?> worldSettings = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
                settings = worldSettings.getField("INSTANCE").get(null);
                entityIds = lookup.unreflect(worldSettings.getMethod("getEntityIds"));
                namespacedId = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId").getConstructor(String.class, String.class);
                try {
                    Class<?> programs = Class.forName("net.irisshaders.iris.layer.GbufferPrograms");
                    beginEntities = lookup.unreflect(programs.getMethod("beginEntities"));
                    endEntities = lookup.unreflect(programs.getMethod("endEntities"));
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    beginEntities = null;
                    endEntities = null;
                }
                try {
                    renderingLevel = Class.forName("net.irisshaders.iris.vertices.ImmediateState").getField("isRenderingLevel");
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    renderingLevel = null;
                }
                //#if MC >= 1.21.9
                available = true;
                //#else
                Class<?> wrapped = Class.forName("net.irisshaders.iris.layer.OuterWrappedRenderType");
                entityShard = Class.forName("net.irisshaders.iris.layer.EntityRenderStateShard").getField("INSTANCE").get(null);
                for (java.lang.reflect.Method method : wrapped.getMethods()) {
                    if (method.getName().equals("wrapExactlyOnce") && method.getParameterCount() == 3) {
                        wrapExactlyOnce = lookup.unreflect(method);
                    }
                }
                available = wrapExactlyOnce != null;
                try {
                    wrappable = Class.forName("net.irisshaders.batchedentityrendering.impl.WrappableRenderType");
                    unwrap = lookup.unreflect(wrappable.getMethod("unwrap"));
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    wrappable = null;
                }
                //#endif
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                available = false;
            }
            if (!available) {
                FastImageFrames.LOGGER.info("Iris hooks for entities not found, baked frames stay off with shaders");
            }
        }
        return available;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int entityId(EntityType<?> type) {
        try {
            Object2IntFunction ids = (Object2IntFunction) entityIds.invoke(settings);
            if (ids == null) {
                return 0;
            }
            Object key = ENTITY_KEYS.get(type);
            if (key == null) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                key = namespacedId.newInstance(id.getNamespace(), id.getPath());
                ENTITY_KEYS.put(type, key);
            }
            return ids.getInt(key);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static Object idMaps() {
        try {
            return entityIds.invoke(settings);
        } catch (Throwable e) {
            return null;
        }
    }

    public static void setIds(int entity, int blockEntity, int item) {
        try {
            setCurrentEntity.invoke(capturedState, entity);
            setCurrentBlockEntity.invoke(capturedState, blockEntity);
            setCurrentItem.invoke(capturedState, item);
        } catch (Throwable ignored) {
        }
    }

    public static boolean entityPhase() {
        return available() && beginEntities != null && endEntities != null;
    }

    public static void beginEntities() {
        try {
            beginEntities.invoke();
        } catch (Throwable ignored) {
        }
    }

    public static void endEntities() {
        try {
            endEntities.invoke();
        } catch (Throwable ignored) {
        }
    }

    public static boolean beginLevel() {
        try {
            if (renderingLevel == null || renderingLevel.getBoolean(null)) {
                return false;
            }
            renderingLevel.setBoolean(null, true);
            return true;
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
    }

    public static void endLevel() {
        try {
            renderingLevel.setBoolean(null, false);
        } catch (IllegalAccessException | RuntimeException ignored) {
        }
    }

    public static int currentBlockEntity() {
        try {
            return (int) currentBlockEntity.invoke(capturedState);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static int currentItem() {
        try {
            return (int) currentItem.invoke(capturedState);
        } catch (Throwable e) {
            return 0;
        }
    }

    //#if MC < 1.21.9
    public static RenderType unwrap(RenderType type) {
        try {
            while (wrappable != null && wrappable.isInstance(type)) {
                type = (RenderType) unwrap.invoke(type);
            }
        } catch (Throwable ignored) {
        }
        return type;
    }

    public static RenderType wrapEntity(RenderType type) {
        try {
            return (RenderType) wrapExactlyOnce.invoke("iris:entity", type, entityShard);
        } catch (Throwable e) {
            return type;
        }
    }
    //#endif
}
