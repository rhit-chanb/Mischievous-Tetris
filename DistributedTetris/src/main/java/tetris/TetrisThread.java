package tetris;

import networking.RealClient;

import javax.swing.JFrame;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class TetrisThread implements Runnable {
    private final RealClient client;
    private final Tetris game;

    public TetrisThread(Tetris game, RealClient client) {
        this.game = game;
        this.client = client;
    }

    @Override
    public void run() {
        Tetris.setUpGame(game, client);
    }
}
