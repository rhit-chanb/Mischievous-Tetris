package tetris;

import java.awt.Point;

public enum Tetromino {
    I_PIECE(0, new Point[][]{
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(3, 1)},
            {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(1, 3)},
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(3, 1)},
            {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(1, 3)}
    }, TColor.CYAN),
    J_PIECE(1, new Point[][]{
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(2, 0)},
            {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(2, 2)},
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(0, 2)},
            {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(0, 0)}
    }, TColor.ORANGE),
    L_PIECE(2, new Point[][]{
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(0, 0)},
            {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(2, 0)},
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(2, 2)},
            {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(0, 2)}

    }, TColor.BLUE),
    O_PIECE(3, new Point[][]{
            {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)},
            {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)},
            {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)},
            {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)}
    }, TColor.YELLOW),
    S_PIECE(4, new Point[][]{
            {new Point(1, 0), new Point(2, 0), new Point(0, 1), new Point(1, 1)},
            {new Point(0, 0), new Point(0, 1), new Point(1, 1), new Point(1, 2)},
            {new Point(1, 0), new Point(2, 0), new Point(0, 1), new Point(1, 1)},
            {new Point(0, 0), new Point(0, 1), new Point(1, 1), new Point(1, 2)}
    }, TColor.GREEN),
    T_PIECE(5, new Point[][]{
            {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(2, 1)},
            {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(1, 2)},
            {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(1, 2)},
            {new Point(1, 0), new Point(1, 1), new Point(2, 1), new Point(1, 2)}
    }, TColor.PINK),
    Z_PIECE(6, new Point[][]{
            {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(2, 1)},
            {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(0, 2)},
            {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(2, 1)},
            {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(0, 2)}
    }, TColor.RED),
    SAND(7, new Point[][]{
        {new Point(0, 0)},
        {new Point(0, 0)},
        {new Point(0, 0)},
        {new Point(0, 0)}
    }, TColor.SAND);

    public static final Tetromino[] ORDER = new Tetromino[]{I_PIECE, J_PIECE, L_PIECE, O_PIECE, S_PIECE, T_PIECE, Z_PIECE};
    public final Point[][] shapeRotations;
    public final int legacyInt;
    public final TColor tcolor;

    Tetromino(int legacyInt, Point[][] shapeRot, TColor tcolor) {
        this.legacyInt = legacyInt;
        this.shapeRotations = shapeRot;
        this.tcolor = tcolor;
    }

    public static Tetromino fromInt(int index) {
        if (index < 0 || index >= values().length) {
            throw new RuntimeException("Invalid type index " + index);
        }
        return values()[index];
    }

    public Point[] inRotation(Rotation rot) {
        return this.shapeRotations[rot.toInt()];
    }
}
