package tetris;

import javax.swing.JFrame;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class TetrisThread implements Runnable {
    Tetris game;

    public TetrisThread(Tetris game) {
        this.game = game;
    }

    @Override
    public void run() {
        Tetris.setUpGame(game);
    }
}
