package fastimageframes.render;

import java.lang.reflect.Method;

public final class Shaders {

    private static Method shaderPackInUse;
    private static Object irisApi;
    private static boolean checked;
    private static boolean active;
    private static java.lang.reflect.Field shadowPass;
    private static boolean shadowTried;

    private Shaders() {
    }

    public static boolean active() {
        if (!checked) {
            checked = true;
            active = false;
            try {
                if (shaderPackInUse == null && irisApi == null) {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    shaderPackInUse = api.getMethod("isShaderPackInUse");
                }
                if (shaderPackInUse != null) {
                    active = (boolean) shaderPackInUse.invoke(irisApi);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                irisApi = Boolean.FALSE;
            }
        }
        return active;
    }

    public static boolean shadowPass() {
        if (!active()) {
            return false;
        }
        if (!shadowTried) {
            shadowTried = true;
            try {
                shadowPass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer").getField("ACTIVE");
            } catch (ReflectiveOperationException | LinkageError e) {
                shadowPass = null;
            }
        }
        try {
            return shadowPass != null && shadowPass.getBoolean(null);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    public static void tick() {
        checked = false;
    }
}
