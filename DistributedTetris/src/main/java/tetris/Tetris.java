package tetris;

import networking.MessageType;
import networking.RealClient;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Tetris extends JPanel {
    public static final String BOARD_ROW_SEPARATOR = "S";
    public static final int STARTING_AMMO = 8;
    public static final int BOARD_WIDTH_CELLS = 12;
    public static final int BOARD_HEIGHT_CELLS = 24;
    public static final int BOARD_HEIGHT_ONE_LESS = BOARD_HEIGHT_CELLS - 1;
    public static final int CELL_SIZE = 26;
    public static final int GRID_LINE_WIDTH = 1;
    public static final int CELL_SIZE_PADDED = CELL_SIZE - GRID_LINE_WIDTH;
    public static final int GAME_TICK_MS = 1000;
    @Serial
    private static final long serialVersionUID = -8715353373678321308L;
    private static final double RANDOM_EVENT_CHANCE = 0.02;
    private final int softLockConstant = 2;
    private final List<Tetromino> nextPieces = new ArrayList<>();
    private final Map<Integer, TColor[][]> opponentBoards = new HashMap<>();
    public RealClient client;
    private Point pieceOrigin;
    private Tetromino currentPiece;
    private Rotation rotation;
    private long score;
    private TColor[][] well;
    private int softLock = softLockConstant;
    private TGameStatus status;
    private ArrayList<EnemyPiece> attackQueue;
    private int ammo;
    private boolean attacking = false;

    public Tetris() {

    }

    public static void setUpGame(Tetris instance, RealClient client) {
        JFrame frame = new JFrame("Mischievous Tetris" + ((client == null) ? " Standalone" : ""));
        int boardWidthPx = (BOARD_WIDTH_CELLS * CELL_SIZE) + 10;
        int heightPx = (CELL_SIZE * (BOARD_HEIGHT_CELLS - 1)) + CELL_SIZE_PADDED;
        frame.setSize(boardWidthPx * 3, heightPx);
        frame.setVisible(true);

        instance.init();
        frame.add(instance);

        frame.addKeyListener(new TetrisKeyListener(instance));

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                frame.dispose();
                if (client != null) {
                    client.shutdownProcedure();
                } else {
                    System.exit(0);
                }
            }
        });

        // Game timer
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(GAME_TICK_MS);
                    instance.dropDown();
                    instance.attemptRandomEvent();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public static void main(String[] args) {
        setUpGame(new Tetris(), null);
    }

    public void attemptRandomEvent() {
        Random rand = new Random();
        double roll = rand.nextDouble();
        if (roll <= RANDOM_EVENT_CHANCE) {
            if (this.client != null) {
                this.client.startRandomEvent();
            }
        }
    }

    public void bindToClient(RealClient client) {
        this.client = client;
    }

    public void triggerRandomEvent(RandomEvent event) {
        System.out.println("Triggering RandomEvent: " + event);
        switch (event) {
            case ADD_AMMO -> ammo += 5;
            case REMOVE_AMMO -> {
                ammo -= 5;
                if (ammo < 0) ammo = 0;
            }
            case CLEAR_LINES -> {
                deleteRow(21);
                deleteRow(21);
                sendBoardUpdate();
            }
            case CLEAR_VERTICAL_LINE -> {
                //Choose random line from 1 - 11 to remove
            }

        }
    }

    public void handleMessageEvent(String message) {
        System.out.println("TetrisGame received message from client layer: " + message);
        // TODO: expand to do cooler things to the tetris game instead of just printing to console
    }

    public void handleRecvBoard(String board, int fromProcess) {
        System.out.println("Received a board update from " + fromProcess);
        opponentBoards.put(fromProcess, StringToBoard(board));
    }

    public void handleDisconnect(int fromProcess) {
        System.out.println(fromProcess + " has disconnected");
        opponentBoards.remove(fromProcess);
        repaint();
    }

    public void handleDeath(int fromProcess) {
        System.out.println(fromProcess + " has topped out!");

        //TODO: other death related things
    }

    public void handleAttack(EnemyPiece piece) {
        this.attackQueue.add(piece);

        System.out.println("adding to attack queue: " + piece);

        System.out.println("Remaining attack queue: ");
        for (EnemyPiece p : attackQueue) {
            System.out.println(piece);
        }
    }

    private void sendBoardUpdate() {
        this.broadcastMessage(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));
    }

    public void broadcastMessage(String message) {
        // more bulletproofing just in case the Tetris.Tetris game somehow isn't connected to a client?
        if (this.client != null) {
            this.client.broadcast(MessageType.TETRIS_EVENT, message);
        } else {
            System.err.println("Somehow not connected to a client?");
        }
    }

    public void broadcastMessage(MessageType type, String message) {
        // more bulletproofing just in case the Tetris.Tetris game somehow isn't connected to a client?
        if (this.client != null) {
            this.client.broadcast(type, message);
        } else {
            System.err.println("Somehow not connected to a client?");
        }
    }

    public String BoardToString(TColor[][] board) {
        StringBuilder result = new StringBuilder();
        for (TColor[] row : board) { // TODO name - might be a col and not a row?
            for (TColor cell : row) {
                if (cell == null) {
                    // TODO find out why there are null cells (is that okay?)
//                    System.out.println("Cell was null?");
                    result.append(TColor.UNKNOWN.toString());
                } else {
                    result.append(cell);
                }
            }
            result.append(BOARD_ROW_SEPARATOR);
        }
        return result.toString();
    }

    public TColor[][] StringToBoard(String s) {
        TColor[][] result = new TColor[BOARD_WIDTH_CELLS][BOARD_HEIGHT_CELLS];
        String[] rows = s.split(BOARD_ROW_SEPARATOR);
        int r = 0;
        for (String row : rows) {
            for (int i = 0; i < result[r].length; i++) {
                result[r][i] = TColor.fromString(row.substring(i, i + 1));
            }
            r++;
        }
        return result;
    }

    // Creates a border around the well and initializes the dropping piece
    public void init() {
        well = new TColor[BOARD_WIDTH_CELLS][BOARD_HEIGHT_CELLS];
        for (int i = 0; i < BOARD_WIDTH_CELLS; i++) {
            for (int j = 0; j < BOARD_HEIGHT_ONE_LESS; j++) {
                if (i == 0 || i == 11 || j == 22) {
                    well[i][j] = TColor.BAR;
                } else {
                    well[i][j] = TColor.OPEN;
                }
            }
        }
        this.broadcastMessage(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));
        this.attackQueue = new ArrayList<>();
        this.score = 0;
        this.ammo = STARTING_AMMO;
        newPiece();
        this.status = TGameStatus.PLAYING;

    }

    // accidental on purpose recursion here
    public boolean takeAttackFromQueue() {
        boolean success = false;
        if (!attackQueue.isEmpty()) {
            EnemyPiece toTake = attackQueue.remove(0);
            if (ammo > 0) {
                ammo--;
                return true;
            }


            System.out.println("Taking attack: " + toTake);

            System.out.println("Remaining attack queue: ");
            for (EnemyPiece p : attackQueue) {
                System.out.println(attackQueue);
            }

            pieceOrigin = toTake.pieceOrigin;
            rotation = toTake.rotation;
            currentPiece = toTake.pieceType;

            int newY = checkTheoreticalPos();
            pieceOrigin.y = newY;

            fixToWellNoNewPiece();
            repaint();

            success = true;
        }
        return success;
    }

    // Put a new, random piece into the dropping position
    public void newPiece() {
        boolean attackTaken = takeAttackFromQueue();
        if (this.status == TGameStatus.GAME_OVER) {
            return;
        }

        softLock = softLockConstant; // reset softlock
        pieceOrigin = new Point(5, 0); // TODO: spawn piece above the board
        rotation = Rotation._0;
        if (nextPieces.isEmpty()) {
            nextPieces.addAll(generateNewBag());
            // TODO: change nextPieces to be able to be modified by outside events
            // TODO: add piece preview
        }
        currentPiece = nextPieces.get(0);
        nextPieces.remove(0);
    }

    public List<Tetromino> generateNewBag() {
        List<Tetromino> bag = new ArrayList<>();
        Collections.addAll(bag, Tetromino.I_PIECE, Tetromino.J_PIECE, Tetromino.L_PIECE, Tetromino.O_PIECE, Tetromino.S_PIECE, Tetromino.T_PIECE, Tetromino.Z_PIECE);
        Collections.shuffle(bag);
        return bag;
    }

    // Collision test for the dropping piece
    private boolean collidesAt(int x, int y, Rotation rotation) {
        for (Point p : currentPiece.inRotation(rotation)) {
            if (well[p.x + x][p.y + y] != TColor.OPEN) {
                return true;
            }
        }
        return false;
    }

    // Rotate the piece clockwise or counterclockwise
    public void rotate(int i) {

        if (currentPiece == Tetromino.T_PIECE && (i == 1 || i == -1)) {
            i *= -1;
            //hacky workaround for t rotation?
        }


        int newRotationIndex = (rotation.toInt() + i) % 4;
        if (newRotationIndex < 0) {
            newRotationIndex = 3;
        }
        Rotation newRotation = Rotation.fromInt(newRotationIndex);
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

    public void toggleMode() {
        this.attacking = !this.attacking;
    }

    // Drops the piece one line or fixes it to the well if it can't drop
    public void dropDown() {
        if (this.status == TGameStatus.PLAYING) {
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

    }

    // Make the dropping piece part of the well, so it is available for
    // collision detection.
    public void fixToWell() {

        if (ammo > 0 && this.attacking) {
            // "send" piece to other board(s) // TODO: actually have either random or fixed targeting maybe?
            pieceOrigin.y = 0;
            this.broadcastMessage(MessageType.ATTACK, pieceOrigin.x + " " + pieceOrigin.y + " " + rotation.toInt() + " " + currentPiece.legacyInt);
            ammo--;
            newPiece();
            // skip placement of piece on player's board since it "went" to the other board(s)
            return;
        }

        for (Point p : currentPiece.inRotation(rotation)) {
            well[pieceOrigin.x + p.x][pieceOrigin.y + p.y] = currentPiece.tcolor;
        }
        clearRows();

        // TODO probably abstract broadcasts elsewhere so we don't need to null check
        //  or maybe make a fake client
        //  client was null (maybe testing offline?)

        sendBoardUpdate();

        checkForTopOut();
        if (this.status == TGameStatus.PLAYING) {
            newPiece();
        } else {
            this.broadcastMessage(MessageType.DEATH, "");
        }
    }

    public void fixToWellNoNewPiece() {
        for (Point p : currentPiece.inRotation(rotation)) {
            well[pieceOrigin.x + p.x][pieceOrigin.y + p.y] = currentPiece.tcolor;
        }
        clearRows();

        // TODO probably abstract broadcasts elsewhere so we don't need to null check
        //  or maybe make a fake client
        //  client was null (maybe testing offline?)

        this.broadcastMessage(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));

        checkForTopOut();

        if (this.status == TGameStatus.GAME_OVER) {
            this.broadcastMessage(MessageType.DEATH, "");
        }
    }

    public void checkForTopOut() {
        for (int i = 0; i < well.length; i++) {
            for (int j = 0; j < 4; j++) {
                if (well[i][j] != null) {
                    if (!(well[i][j] == TColor.OPEN || well[i][j] == TColor.BAR)) {
                        //System.out.println("Detected out of bounds piece at: x= " + i + ", y= " + j);
                        this.status = TGameStatus.GAME_OVER;
                    }
                }
            }
        }
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
                if (well[i][j] == TColor.OPEN) {
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
            case 1 -> {
                score += 100;
                this.broadcastMessage("LINE_CLEAR SINGLE");
                this.ammo += 1;
            }
            case 2 -> {
                score += 300;
                this.broadcastMessage("LINE_CLEAR DOUBLE");
                this.ammo += 2;
            }
            case 3 -> {
                score += 500;
                this.broadcastMessage("LINE_CLEAR TRIPLE");
                this.ammo += 3;
            }
            case 4 -> {
                score += 800;
                this.broadcastMessage("LINE_CLEAR TETRIS");
                this.ammo += 4;
            }
        }
    }

    //checks for the theoretical y position of the gray Tetris.Tetromino (shadow piece)
    public int checkTheoreticalPos() {
        ArrayList<Integer> theor = new ArrayList<>();
        for (Point p : currentPiece.inRotation(rotation)) {
            int theoreticalVal = 0;
            for (int j = p.y + pieceOrigin.y + 1; j < 22; j++) {
                if (well[p.x + pieceOrigin.x][j] == TColor.OPEN) {
                    theoreticalVal++;
                } else break;
            }
            theor.add(theoreticalVal);
        }
        return Collections.min(theor) + pieceOrigin.y;
    }

    // Draw the falling piece
    private void drawPiece(Graphics g) {
        //paints the theoretical gray Tetromino (shadow piece)
        g.setColor(Color.GRAY);
        if (this.ammo > 0) {
            if (this.attacking) {
                g.setColor(new Color(242, 20, 8, 50)); // should be like transparent reddish
            } else {
                g.setColor(new Color(0, 255, 0, 75)); // should be transparent greenish
            }
        }

        for (Point p : currentPiece.inRotation(rotation)) {
            g.fillRect((p.x + pieceOrigin.x) * CELL_SIZE,
                    (p.y + checkTheoreticalPos()) * CELL_SIZE,
                    CELL_SIZE_PADDED, CELL_SIZE_PADDED);
        }

        g.setColor(currentPiece.tcolor.color);
        for (Point p : currentPiece.inRotation(rotation)) {

            g.fillRect((p.x + pieceOrigin.x) * CELL_SIZE,
                    (p.y + pieceOrigin.y) * CELL_SIZE,
                    CELL_SIZE_PADDED, CELL_SIZE_PADDED);
        }
    }

    private void drawDamageGauge(Graphics g) {
        if (this.attackQueue.isEmpty()) {
            return;
        }
        g.setColor(Color.red);
        int x = 13 - 5;
        int y = CELL_SIZE * 23;
        int height = (this.attackQueue.size() * CELL_SIZE) + CELL_SIZE;

        g.fillRect(x, y - height, 10, height);


    }

    @Override
    public void paintComponent(Graphics g) {
        // Paint the well
        Color boardBackground = g.getColor();
        g.fillRect(0, 0, CELL_SIZE * BOARD_WIDTH_CELLS, CELL_SIZE * BOARD_HEIGHT_ONE_LESS);
        g.setColor(Color.red);
        g.fillRect(0, (CELL_SIZE * 4) - 2, CELL_SIZE * BOARD_WIDTH_CELLS, 2);

        g.setColor(Color.white);
        g.fillRect((CELL_SIZE * BOARD_WIDTH_CELLS) + 10, 0, 1000, CELL_SIZE * BOARD_HEIGHT_ONE_LESS);
        for (int i = 0; i < BOARD_WIDTH_CELLS; i++) {
            for (int j = 0; j < BOARD_HEIGHT_ONE_LESS; j++) {
                g.setColor(well[i][j].color);
                g.fillRect(CELL_SIZE * i, CELL_SIZE * j, CELL_SIZE_PADDED, CELL_SIZE_PADDED);
            }
        }

        drawDamageGauge(g);

        g.setColor(boardBackground);

        int offset = 1;
        for (TColor[][] board : opponentBoards.values()) {
            g.fillRect(offset * (312), 0, CELL_SIZE * BOARD_WIDTH_CELLS, CELL_SIZE * BOARD_HEIGHT_ONE_LESS);
            for (int i = 0; i < BOARD_WIDTH_CELLS; i++) {
                for (int j = 0; j < BOARD_HEIGHT_ONE_LESS; j++) {
                    g.setColor(board[i][j].color);
                    g.fillRect((CELL_SIZE * i) + offset * (312), CELL_SIZE * j, CELL_SIZE_PADDED, CELL_SIZE_PADDED);
                }
            }
            offset++;
        }

        // Display the score
        g.setColor(Color.WHITE);
        g.drawString("score: " + score, 19 * BOARD_WIDTH_CELLS, 25);

        g.setColor(Color.WHITE);
        g.drawString("ammo: " + ammo, 19 * BOARD_WIDTH_CELLS, 45);

        // Show if game over
        g.setColor(Color.red);

        if (this.status == TGameStatus.GAME_OVER) {
            Font gameOverFont = new Font("Sans Serif", Font.PLAIN, 24);
            g.setFont(gameOverFont);

            g.drawString("GAME OVER", (int) (CELL_SIZE * 3.5), CELL_SIZE * BOARD_WIDTH_CELLS);
        }


        // Draw the currently falling piece
        drawPiece(g);
    }

    static class TetrisKeyListener implements KeyListener {
        private final Tetris game;

        public TetrisKeyListener(Tetris game) {
            this.game = game;
        }

        public void keyTyped(KeyEvent e) {
        }

        public void keyPressed(KeyEvent e) {
            // TODO: read these from a config file?
            switch (e.getKeyCode()) {
                case KeyEvent.VK_J -> game.rotate(-1);
                case KeyEvent.VK_K -> game.rotate(+1);
                case KeyEvent.VK_L -> game.rotate(2);
                case KeyEvent.VK_A -> game.move(-1);
                case KeyEvent.VK_D -> game.move(+1);
                case KeyEvent.VK_S -> game.dropDown();
                case KeyEvent.VK_SPACE -> game.dropToBottom();
                case KeyEvent.VK_R -> game.init();
                case KeyEvent.VK_SHIFT -> game.toggleMode();
            }
        }

        public void keyReleased(KeyEvent e) {
        }
    }
}