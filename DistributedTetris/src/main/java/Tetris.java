import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import javax.swing.JFrame;
import javax.swing.JPanel;

public class Tetris extends JPanel {

    private static final long serialVersionUID = -8715353373678321308L;
//    private static final int height =
    private final Point[][][] Tetrominos = {
            // I-Piece
            {
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(3, 1)},
                    {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(1, 3)},
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(3, 1)},
                    {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(1, 3)}
            },

            // J-Piece
            {
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(2, 0)},
                    {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(2, 2)},
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(0, 2)},
                    {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(0, 0)}
            },

            // L-Piece
            {
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(0, 0)},
                    {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(2, 0)},
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(2, 2)},
                    {new Point(1, 0), new Point(1, 1), new Point(1, 2), new Point(0, 2)}

            },

            // O-Piece
            {
                    {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)},
                    {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)},
                    {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)},
                    {new Point(0, 0), new Point(0, 1), new Point(1, 0), new Point(1, 1)}
            },

            // S-Piece
            {
                    {new Point(1, 0), new Point(2, 0), new Point(0, 1), new Point(1, 1)},
                    {new Point(0, 0), new Point(0, 1), new Point(1, 1), new Point(1, 2)},
                    {new Point(1, 0), new Point(2, 0), new Point(0, 1), new Point(1, 1)},
                    {new Point(0, 0), new Point(0, 1), new Point(1, 1), new Point(1, 2)}
            },

            // T-Piece
            {
                    {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(2, 1)},
                    {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(1, 2)},
                    {new Point(0, 1), new Point(1, 1), new Point(2, 1), new Point(1, 2)},
                    {new Point(1, 0), new Point(1, 1), new Point(2, 1), new Point(1, 2)}
            },

            // Z-Piece
            {
                    {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(2, 1)},
                    {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(0, 2)},
                    {new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(2, 1)},
                    {new Point(1, 0), new Point(0, 1), new Point(1, 1), new Point(0, 2)}
            }
    };

    private final Color[] tetrominoColors = {
            new Color(15, 155, 215), // cyan
            new Color(227, 91, 2), // orange
            new Color(33, 65, 198), // blue
            new Color(227, 159, 2), // yellow
            new Color(89, 177, 1), // green
            new Color(175, 41, 138), // pink
            new Color(215, 15, 55) // red
    };
    public static HashMap<Color,String> ColorToChar = new HashMap<>();
    public static HashMap<String,Color> CharToColor = new HashMap<>();


    private Point pieceOrigin;
    private int currentPiece;
    private int rotation;
    private ArrayList<Integer> nextPieces = new ArrayList<Integer>();

    private long score;
    private Color[][] well;
    private HashMap<Integer, Color[][]> opponentBoard = new HashMap<>();
    private final int softLockConstant = 2;
    private int softLock = softLockConstant;

    public RealClient client;

    public Tetris(){

    }
    public void bindToClient(RealClient client){
        this.client = client;
    }

    public void handleMessageEvent(String message){
        System.out.println("TetrisGame received message from client layer: " + message);
        // TODO: expand to do cooler things to the tetris game instead of just printing to console
    }

    public void handleRecvBoard(String board, int fromProcess){
        System.out.println("Received a board update from " + fromProcess);
        opponentBoard.put(fromProcess, StringToBoard(board));
    }

    public void broadcastMessage(String message){
        // more bulletproofing just in case the Tetris game somehow isn't connected to a client?
        if(this.client != null){
            this.client.broadcast(MessageType.TETRIS_EVENT, message);
        }
    }

    public String BoardToString(Color[][] board){
        StringBuilder result = new StringBuilder();
        for (Color[] value : board) {
            for (int n = 0; n < value.length; n++) {
                String val = ColorToChar.get(value[n]);
                if(val == null){
                    System.out.println(value[n] + " gave null");
                }
                result.append(val);
            }
            result.append('S');
        }
        return result.toString();
    }
    public Color[][] StringToBoard(String s){
        Color[][] result = new Color[12][24];
        String[] rows = s.split("S");
        int r = 0;
        for(String row : rows){
            for(int i = 0; i < result[r].length; i++){
                result[r][i] = CharToColor.get(row.substring(i,i+1));
            }
            r++;
        }
        return result;
    }
    // Creates a border around the well and initializes the dropping piece
    public void init() {
        ColorToChar.put(tetrominoColors[0],"c");
        ColorToChar.put(tetrominoColors[1],"o");
        ColorToChar.put(tetrominoColors[2],"b");
        ColorToChar.put(tetrominoColors[3],"y");
        ColorToChar.put(tetrominoColors[4],"g");
        ColorToChar.put(tetrominoColors[5],"p");
        ColorToChar.put(tetrominoColors[6],"r");
        ColorToChar.put(Color.BLACK,"*");
        ColorToChar.put(Color.GRAY,"|");
        ColorToChar.put(null,"N");


        CharToColor.put("c",tetrominoColors[0]);
        CharToColor.put("o",tetrominoColors[1]);
        CharToColor.put("b",tetrominoColors[2]);
        CharToColor.put("y",tetrominoColors[3]);
        CharToColor.put("g",tetrominoColors[4]);
        CharToColor.put("p",tetrominoColors[5]);
        CharToColor.put("r",tetrominoColors[6]);
        CharToColor.put("*",Color.BLACK);
        CharToColor.put("|",Color.GRAY);
        CharToColor.put("N",null);
        well = new Color[12][24];
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 23; j++) {
                if (i == 0 || i == 11 || j == 22) {
                    well[i][j] = Color.GRAY;
                } else {
                    well[i][j] = Color.BLACK;
                }
            }
        }
        newPiece();
    }

    // Put a new, random piece into the dropping position
    public void newPiece() {
        softLock = softLockConstant; // reset softlock
        pieceOrigin = new Point(5, 0); // TODO: spawn piece above the board
        rotation = 0;
        if (nextPieces.isEmpty()) {
            Collections.addAll(nextPieces, 0, 1, 2, 3, 4, 5, 6);
            Collections.shuffle(nextPieces); //current 7-bag randomization
            // TODO: change nextPieces to be able to be modified by outside events
            // TODO: add piece preview
        }
        currentPiece = nextPieces.get(0);
        nextPieces.remove(0);
    }

    // Collision test for the dropping piece
    private boolean collidesAt(int x, int y, int rotation) {
        for (Point p : Tetrominos[currentPiece][rotation]) {
            if (well[p.x + x][p.y + y] != Color.BLACK) {
                return true;
            }
        }
        return false;
    }

    // Rotate the piece clockwise or counterclockwise
    public void rotate(int i) {

        if (currentPiece == 5 && (i == 1 || i == -1)) {
            i *= -1;
            //hacky workaround for t rotation?
        }


        int newRotation = (rotation + i) % 4;
        if (newRotation < 0) {
            newRotation = 3;
        }
        if (!collidesAt(pieceOrigin.x, pieceOrigin.y, newRotation)) {
            rotation = newRotation;
        }
        repaint();
    }

    // Move the piece left or right
    public void move(int i) {
        if (!collidesAt(pieceOrigin.x + i, pieceOrigin.y, rotation)) {
            pieceOrigin.x += i;
        }
        repaint();
    }

    // Drops the piece one line or fixes it to the well if it can't drop
    public void dropDown() {
        if (!collidesAt(pieceOrigin.x, pieceOrigin.y + 1, rotation)) {
            pieceOrigin.y += 1;
        } else {
            if (softLock > 0) {
                softLock--;
            } else {
                fixToWell();
                softLock = softLockConstant;
            }
        }
        repaint();
    }

    // Make the dropping piece part of the well, so it is available for
    // collision detection.
    public void fixToWell() {
        for (Point p : Tetrominos[currentPiece][rotation]) {
            well[pieceOrigin.x + p.x][pieceOrigin.y + p.y] = tetrominoColors[currentPiece];
        }
        clearRows();
        newPiece();
        client.broadcast(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));
    }

    public void dropToBottom() {

//        while (!collidesAt(pieceOrigin.x, pieceOrigin.y + 1, rotation)) {
//            pieceOrigin.y += 1;
//        }
        int newY = checkTheoreticalPos();
        pieceOrigin.y = newY;

        fixToWell();
        repaint();

    }

    public void deleteRow(int row) {
        for (int j = row - 1; j > 0; j--) {
            for (int i = 1; i < 11; i++) {
                well[i][j + 1] = well[i][j];
            }
        }
    }

    // Clear completed rows from the field and award score according to
    // the number of simultaneously cleared rows.
    public void clearRows() {
        boolean gap;
        int numClears = 0;

        for (int j = 21; j > 0; j--) {
            gap = false;
            for (int i = 1; i < 11; i++) {
                if (well[i][j] == Color.BLACK) {
                    gap = true;
                    break;
                }
            }
            if (!gap) {
                deleteRow(j);
                j += 1;
                numClears += 1;
            }
        }

        switch (numClears) {
            case 1:
                score += 100;
                this.broadcastMessage("LINE_CLEAR SINGLE");
                break;
            case 2:
                score += 300;
                this.broadcastMessage("LINE_CLEAR DOUBLE");
                break;
            case 3:
                score += 500;
                this.broadcastMessage("LINE_CLEAR TRIPLE");
                break;
            case 4:
                score += 800;
                this.broadcastMessage("LINE_CLEAR TETRIS");
                break;
        }
    }

    //checks for the theoretical y position of the gray Tetromino (shadow piece)
    public int checkTheoreticalPos() {
        ArrayList<Integer> theor = new ArrayList<>();
        for (Point p : Tetrominos[currentPiece][rotation]) {
            int theorVal = 0;
            for (int j = p.y + pieceOrigin.y + 1; j < 22; j++) {
                if (well[p.x + pieceOrigin.x][j] == Color.BLACK) {
                    theorVal++;
                } else break;
            }
            theor.add(theorVal);
        }
        return Collections.min(theor) + pieceOrigin.y;
    }

    // Draw the falling piece
    private void drawPiece(Graphics g) {
        //paints the theoretical gray Tetromino (shadow piece)
        g.setColor(Color.GRAY);
        for (Point p : Tetrominos[currentPiece][rotation]) {
            g.fillRect((p.x + pieceOrigin.x) * 26,
                    (p.y + checkTheoreticalPos()) * 26,
                    25, 25);
        }

        g.setColor(tetrominoColors[currentPiece]);
        for (Point p : Tetrominos[currentPiece][rotation]) {

            g.fillRect((p.x + pieceOrigin.x) * 26,
                    (p.y + pieceOrigin.y) * 26,
                    25, 25);
        }

    }

    @Override
    public void paintComponent(Graphics g) {
        // Paint the well
        g.fillRect(0, 0, 26 * 12, 26 * 23);
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 23; j++) {
                g.setColor(well[i][j]);
                g.fillRect(26 * i, 26 * j, 25, 25);
            }
        }
        int offset = 1;
        for(Color[][] board : opponentBoard.values()){
            g.fillRect(offset*(312), 0, 26 * 12, 26 * 23);
            for (int i = 0; i < 12; i++) {
                for (int j = 0; j < 23; j++) {
                    g.setColor(board[i][j]);
                    g.fillRect((26 * i) + offset*(312), 26 * j, 25, 25);
                }
            }
            offset++;
        }

        // Display the score
        g.setColor(Color.WHITE);
        g.drawString("" + score, 19 * 12, 25);

        // Draw the currently falling piece
        drawPiece(g);
    }

    public static void main(String[] args) {
        JFrame f = new JFrame("Tetris");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(12 * 26 + 10, 26 * 23 + 25);
        f.setVisible(true);

        final Tetris game = new Tetris();
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
                        game.score += 1;
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