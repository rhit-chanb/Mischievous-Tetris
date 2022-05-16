package tetris;

import java.awt.*;

public class EnemyPiece {
    Point pieceOrigin;
    Rotation rotation;
    Tetromino pieceType;

    public EnemyPiece(Point pieceOrigin, Rotation rotation, Tetromino pieceType) {
        this.pieceOrigin = pieceOrigin;
        this.rotation = rotation;
        this.pieceType = pieceType;
    }

    public String toString(){
        return "EnemyPiece, pieceOrigin={" + pieceOrigin.x + ", " + pieceOrigin.y + "} rotation: " + rotation + " type: " + pieceType;
    }
}
