package tetris;

public enum Rotation {
    _0(0),
    _90(1),
    _180(2),
    _270(3);

    public static final Rotation[] ORDER = new Rotation[]{_0, _90, _180, _270};
    public final int index;

    Rotation(int index) {
        this.index = index;
    }

    public static Rotation fromInt(int index) {
        if (index < 0 || index >= ORDER.length) {
            throw new RuntimeException("Invalid rotation index " + index);
        }
        return ORDER[index];
    }

    public int toInt() {
        return index;
    }
}
