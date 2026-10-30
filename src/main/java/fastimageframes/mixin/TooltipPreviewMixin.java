package fastimageframes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fastimageframes.render.MapPreviews;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.GuiGraphics;
//#endif

//#if MC >= 26.1
@Mixin(GuiGraphicsExtractor.class)
//#else
@Mixin(GuiGraphics.class)
//#endif
public class TooltipPreviewMixin {

    //#if MC >= 26.1
    @WrapOperation(method = "tooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;extractImage(Lnet/minecraft/client/gui/Font;IIIILnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void fif$mapPreview(ClientTooltipComponent component, Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics, Operation<Void> original) {
    //#elif MC >= 1.21.6
    @WrapOperation(method = "renderTooltip", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;renderImage(Lnet/minecraft/client/gui/Font;IIIILnet/minecraft/client/gui/GuiGraphics;)V"))
    private void fif$mapPreview(ClientTooltipComponent component, Font font, int x, int y, int width, int height, GuiGraphics graphics, Operation<Void> original) {
    //#else
    @WrapOperation(method = "renderTooltipInternal", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;renderImage(Lnet/minecraft/client/gui/Font;IIIILnet/minecraft/client/gui/GuiGraphics;)V"))
    private void fif$mapPreview(ClientTooltipComponent component, Font font, int x, int y, int width, int height, GuiGraphics graphics, Operation<Void> original) {
    //#endif
        if (!MapPreviews.INSTANCE.draw(component, x, y, graphics)) {
            original.call(component, font, x, y, width, height, graphics);
        }
    }

}
