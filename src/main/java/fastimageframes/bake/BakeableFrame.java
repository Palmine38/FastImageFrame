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

    int fif$itemKey();

    long fif$keySince();

    long fif$nextProbe();

    boolean fif$volatile();

    int fif$probed();

    void fif$setProbe(int itemKey, long keySince, long nextProbe, boolean changing, int probed);

}
