package fastimageframes.bake;

public interface BakeableFrame {

    boolean fif$isBaked();

    void fif$setBaked(boolean baked);

    int fif$signature();

    void fif$setSignature(int signature);

    long fif$changedTick();

    long fif$animatedUntil();

    void fif$setChanged(long changedTick, long animatedUntil);

    int fif$content();

    void fif$setContent(int content);

    int fif$geometry();

    Object fif$slot();

    void fif$setShape(int geometry, Object slot);

}
