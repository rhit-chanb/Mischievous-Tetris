import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class TetrisThread implements Runnable{
    Tetris game;

    public TetrisThread(Tetris game) {
        this.game = game;
    }

    @Override
    public void run() {
        JFrame f = new JFrame("Tetris");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(12 * 26 + 10, 26 * 23 + 25);
        f.setVisible(true);

        game.init();
        f.add(game);

        // Keyboard controls
        f.addKeyListener(new KeyListener() {
            public void keyTyped(KeyEvent e) {
            }

            public void keyPressed(KeyEvent e) {
                // TODO: read these from a config file?
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_J:
                        game.rotate(-1);
                        break;
                    case KeyEvent.VK_K:
                        game.rotate(+1);
                        break;
                    case KeyEvent.VK_L:
                        game.rotate(2);
                        break;
                    case KeyEvent.VK_A:
                        game.move(-1);
                        break;
                    case KeyEvent.VK_D:
                        game.move(+1);
                        break;
                    case KeyEvent.VK_S:
                        game.dropDown();
                        //game.score += 1;
                        break;
                    case KeyEvent.VK_SPACE:
                        game.dropToBottom();
                        break;

                    case KeyEvent.VK_R:
                        game.init();
                        break;
                }
            }

            public void keyReleased(KeyEvent e) {
            }
        });

        // Make the falling piece drop every second
        new Thread() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000);
                        game.dropDown();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }.start();
    }
}
