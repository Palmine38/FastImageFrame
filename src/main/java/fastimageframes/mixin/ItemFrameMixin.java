package fastimageframes.mixin;

import fastimageframes.bake.BakeableFrame;
import fastimageframes.bake.BakedFrames;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrame.class)
public class ItemFrameMixin implements BakeableFrame {

    @Unique
    private boolean fif$baked;

    @Unique
    private int fif$signature;

    @Unique
    private long fif$changedTick = Long.MIN_VALUE / 2;

    @Unique
    private long fif$animatedUntil = Long.MIN_VALUE / 2;

    @Unique
    private int fif$content = Integer.MIN_VALUE;

    @Unique
    private int fif$geometry;

    @Unique
    private Object fif$slot;

    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void fif$dataChanged(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        BakedFrames.INSTANCE.onFrameChanged((ItemFrame) (Object) this);
    }

    @Override
    public long fif$changedTick() {
        return fif$changedTick;
    }

    @Override
    public long fif$animatedUntil() {
        return fif$animatedUntil;
    }

    @Override
    public void fif$setChanged(long changedTick, long animatedUntil) {
        fif$changedTick = changedTick;
        fif$animatedUntil = animatedUntil;
    }

    @Override
    public int fif$content() {
        return fif$content;
    }

    @Override
    public void fif$setContent(int content) {
        fif$content = content;
    }

    @Override
    public int fif$geometry() {
        return fif$geometry;
    }

    @Override
    public Object fif$slot() {
        return fif$slot;
    }

    @Override
    public void fif$setShape(int geometry, Object slot) {
        fif$geometry = geometry;
        fif$slot = slot;
    }

    @Override
    public boolean fif$isBaked() {
        return fif$baked;
    }

    @Override
    public void fif$setBaked(boolean baked) {
        fif$baked = baked;
    }

    @Override
    public int fif$signature() {
        return fif$signature;
    }

    @Override
    public void fif$setSignature(int signature) {
        fif$signature = signature;
    }

}
