package fastimageframes.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class Cameras {

    private Cameras() {
    }

    public static Vec3 position() {
        //#if MC >= 26.2
        return Minecraft.getInstance().gameRenderer.mainCamera().position();
        //#elif MC >= 1.21.11
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
        //#else
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        //#endif
    }

}
