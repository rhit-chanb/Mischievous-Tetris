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
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Semaphore;

public class Tetris extends JPanel {
    public static final String BOARD_ROW_SEPARATOR = "S";
    public static final int STARTING_AMMO = 8;
    public static final int BOARD_WIDTH_CELLS = 12;
    public static final int LEFTMOST_PLAYABLE_X = 1;
    public static final int RIGHTMOST_PLAYABLE_X = BOARD_WIDTH_CELLS - 1;
    public static final int BOARD_HEIGHT_CELLS = 24;
    public static final int BOARD_HEIGHT_ONE_LESS = BOARD_HEIGHT_CELLS - 1;
    public static final int CELL_SIZE = 26;
    public static final int GRID_LINE_WIDTH = 1;
    public static final int CELL_SIZE_PADDED = CELL_SIZE - GRID_LINE_WIDTH;
    public static final int GAME_TICK_MS = 1000;
    public static final int DEFAULT_MESSAGE_TIMEOUT = 5; // number of game ticks a message persists for, if no timeout period is given
    public static final int BOMB_COOLDOWN_LENGTH = 15;
    public static final int BOMB_AMMO_COST = 5;
    public static final int AMMO_COST_COOLDOWN_LENGTH = 5;
    public static final int MAX_AMMO_AMT = 20;
    public static final int SAND_EVENT_NUM_PIECES = 7;
    public static final int ATTACK_AMMO_COST = 2;
    public static final double BOMB_DEBRIS_ATTACK_CHANCE_PER_CELL = 0.8;
    @Serial
    private static final long serialVersionUID = -8715353373678321308L;
    private static final double RANDOM_EVENT_CHANCE = 0.02;
    private final int softLockConstant = 2;
    private final List<Tetromino> nextPieces = new ArrayList<>();
    private final Map<Integer, TColor[][]> opponentBoards = new HashMap<>();
    public RealClient client;
    Semaphore attackQueueLock;
    private Point pieceOrigin;
    private Tetromino currentPiece;
    private Rotation currentRotation;
    private long score;
    private TColor[][] well;
    private int softLock = softLockConstant;
    private TGameStatus status;
    private ArrayList<EnemyPiece> attackQueue;
    private int ammo;
    private boolean attacking = false;
    private String currentDisplayedMessage = "";
    private int messageTimeout;
    private int bombCooldown;
    private int AMMO_COST = 2;
    private int ammoCostCooldown;

    public Tetris() {
        attackQueueLock = new Semaphore(1);
    }

    public static void setUpGame(Tetris instance, RealClient client) {
        JFrame frame = new JFrame("Mischievous Tetris" + ((client == null) ? " Standalone" : ""));
        int boardWidthPx = (BOARD_WIDTH_CELLS * CELL_SIZE) + 10;
        int heightPx = (CELL_SIZE * (BOARD_HEIGHT_CELLS - 1)) + CELL_SIZE_PADDED + ((5 * CELL_SIZE) / 2);
        frame.setSize(boardWidthPx * 4, heightPx);
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
                    instance.updateBombCooldown();
                    instance.updateMessageCooldown();
                    instance.updateCostCooldown();
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
        double roll = new Random().nextDouble();
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
        //System.out.println("Triggering RandomEvent: " + event);
        setCurrentDisplayedMessage("Random Event: " + event);
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
                Random rand = new Random();
                int roll = rand.nextInt(11) + 1;
                for (int i = 0; i < 22; i++) {
                    well[roll][i] = TColor.OPEN;
                }
            }
            case DISCOUNT_AMMO_COST -> {
                AMMO_COST = 1;
                ammoCostCooldown = 0;
            }
            case REDUCE_ATTACK_QUEUE -> {
                try {
                    if (!attackQueue.isEmpty()) {
                        try {
                            attackQueueLock.acquire();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        int toRemove = attackQueue.size() / 2;
                        for (int i = 0; i < toRemove; i++) {
                            attackQueue.remove(i);
                        }
                        attackQueueLock.release();
                    }
                } catch (ConcurrentModificationException e) {
                    setCurrentDisplayedMessage("No Reduced piece queue!", 3);
                }
            }
            case MAX_AMMO -> ammo = MAX_AMMO_AMT;
            case SAND_DROP -> {
                dropSandEvent();
            }
            case NO_EVENT -> {
            }
        }
    }

    private void dropSandEvent() {
        for (int i = 0; i < SAND_EVENT_NUM_PIECES; i++) {
            dropSandPiece();
        }
        clearRows();
        sendBoardUpdate();
    }

    private void dropSandPiece() {
        int x = new Random().nextInt(RIGHTMOST_PLAYABLE_X - LEFTMOST_PLAYABLE_X) + LEFTMOST_PLAYABLE_X;
        int dropY = checkTheoreticalPos(Tetromino.SAND, Rotation._0, x, 0);
        // write sand (it's 1x1 now, but this can support bigger sand if we do that)
        for (Point p : Tetromino.SAND.inRotation(Rotation._0)) {
            well[x + p.x][dropY + p.y] = Tetromino.SAND.tcolor;
        }
    }

    public void handleMessageEvent(String message) {
//        System.out.println("TetrisGame received message from client layer: " + message);
        // TODO: expand to do cooler things to the tetris game instead of just printing to console
    }

    public void handleRecvBoard(String board, int fromProcess) {
//        System.out.println("Received a board update from " + fromProcess);
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

    public void handleAttack(EnemyPiece piece, int from) {
        try {
            attackQueueLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.attackQueue.add(piece);
        attackQueueLock.release();

        setCurrentDisplayedMessage("Attack from P" + from, 1);

        //System.out.println("adding to attack queue: " + piece);
        //System.out.println("Remaining attack queue: ");
//        for (EnemyPiece p : attackQueue) {
//            System.out.println(piece);
//        }
    }

    private void sendBoardUpdate() {
        this.broadcastMessage(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));
    }

    public void broadcastMessage(String message) {
        broadcastMessage(MessageType.TETRIS_EVENT, message);
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
                if (i == 0 || i == (BOARD_WIDTH_CELLS - 1) || j == (BOARD_HEIGHT_CELLS - 2)) {
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
            try {
                attackQueueLock.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            EnemyPiece toTake = attackQueue.remove(0);
            if (ammo > 0) {
                ammo--;
                attackQueueLock.release();
                return true;
            }

            System.out.println("Taking attack: " + toTake);
            System.out.println("Remaining attack queue: ");
            for (EnemyPiece p : attackQueue) {
                System.out.println("\t" + attackQueue);
            }
            attackQueueLock.release();
            setCurrentDisplayedMessage("RECEIVED AN ATTACK!", 2);

            pieceOrigin = toTake.pieceOrigin;
            currentRotation = toTake.rotation;
            currentPiece = toTake.pieceType;

            int newY = checkTheoreticalPos(currentPiece, currentRotation, pieceOrigin.x, pieceOrigin.y);
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
        currentRotation = Rotation._0;
        if (nextPieces.isEmpty()) {
            nextPieces.addAll(generateNewBag());
            // TODO: change nextPieces to be able to be modified by outside events
            // TODO: add piece preview
        }
        currentPiece = nextPieces.get(0);
        nextPieces.remove(0);
    }

    public List<Tetromino> generateNewBag() {
        List<Tetromino> bag = new ArrayList<>(List.of(Tetromino.ORDER));
        Collections.shuffle(bag);
        return bag;
    }

    // Collision test for the dropping piece
    private boolean collidesAt(int x, int y, Rotation rotation) {
        if (x < 0 || x > RIGHTMOST_PLAYABLE_X) {
            return true;
        }
        for (Point p : currentPiece.inRotation(rotation)) {
            if (well[p.x + x][p.y + y] != TColor.OPEN) {
                return true;
            }
        }
        return false;
    }

    // Rotate the piece clockwise or counterclockwise
    public void rotate(int i) {
        //hacky workaround for t rotation?
        if (currentPiece == Tetromino.T_PIECE && (i == 1 || i == -1)) {
            i *= -1;
        }

        int newRotationIndex = (currentRotation.toInt() + i) % 4;
        if (newRotationIndex < 0) {
            newRotationIndex = 3;
        }
        Rotation newRotation = Rotation.fromInt(newRotationIndex);
        if (!collidesAt(pieceOrigin.x, pieceOrigin.y, newRotation)) {
            currentRotation = newRotation;
        }
        repaint();
    }

    // Move the piece left or right
    public void move(int i) {
        if (!collidesAt(pieceOrigin.x + i, pieceOrigin.y, currentRotation)) {
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
            if (!collidesAt(pieceOrigin.x, pieceOrigin.y + 1, currentRotation)) {
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

    private void sendAttack(int x, int y, Rotation rotation, Tetromino piece) {
        this.broadcastMessage(MessageType.ATTACK, x + " " + y + " " + rotation.toInt() + " " + piece.legacyInt);
    }

    // Make the dropping piece part of the well, so it is available for
    // collision detection.
    public void fixToWell() {

        if (ammo >= AMMO_COST && this.attacking) {
            // "send" piece to other board(s) // TODO: actually have either random or fixed targeting maybe?
            sendAttack(pieceOrigin.x, 0, currentRotation, currentPiece);
            ammo -= ATTACK_AMMO_COST;
            newPiece();
            // skip placement of piece on player's board since it "went" to the other board(s)
            return;
        }

        for (Point p : currentPiece.inRotation(currentRotation)) {
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

    // TODO why is this a tweaked copy-paste of fixToWell?
    public void fixToWellNoNewPiece() {
        for (Point p : currentPiece.inRotation(currentRotation)) {
            well[pieceOrigin.x + p.x][pieceOrigin.y + p.y] = currentPiece.tcolor;
        }
        clearRows();

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
        int newY = checkTheoreticalPos(currentPiece, currentRotation, pieceOrigin.x, pieceOrigin.y);
        pieceOrigin.y = newY;

        fixToWell();
        repaint();
    }

    public void dropToBottomAndBomb() {
        if (bombCooldown >= BOMB_COOLDOWN_LENGTH || ammo >= 5) {
            int newY = checkTheoreticalPos(currentPiece, currentRotation, pieceOrigin.x, pieceOrigin.y);
            pieceOrigin.y = newY;
            bombBoard();
        }
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
        int ammoToAdd = 0;
        switch (numClears) {
            case 1 -> {
                score += 100;
                this.broadcastMessage("LINE_CLEAR SINGLE");
                ammoToAdd = 1;
            }
            case 2 -> {
                score += 300;
                this.broadcastMessage("LINE_CLEAR DOUBLE");
                ammoToAdd = 2;
            }
            case 3 -> {
                score += 500;
                this.broadcastMessage("LINE_CLEAR TRIPLE");
                ammoToAdd = 3;
            }
            case 4 -> {
                score += 800;
                this.broadcastMessage("LINE_CLEAR TETRIS");
                ammoToAdd = 5;
            }
        }
        ammo += ammoToAdd;
        if (ammo > 20) {
            ammo = 20; //hard cap at 20
        }
    }

    //checks for the theoretical y position of the gray Tetris.Tetromino (shadow piece)
    // TODO make a data holder class for this maybe?
    public int checkTheoreticalPos(Tetromino piece, Rotation rotation, int xPos, int yPos) {
        ArrayList<Integer> candidates = new ArrayList<>();
        for (Point p : piece.inRotation(rotation)) {
            int theoreticalVal = 0;
            for (int j = p.y + yPos + 1; j < 22; j++) {
                if (well[p.x + xPos][j] == TColor.OPEN) {
                    theoreticalVal++;
                } else break;
            }
            candidates.add(theoreticalVal);
        }
        return Collections.min(candidates) + yPos;
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

        int futureY = checkTheoreticalPos(currentPiece, currentRotation, pieceOrigin.x, pieceOrigin.y);
        for (Point p : currentPiece.inRotation(currentRotation)) {
            g.fillRect((p.x + pieceOrigin.x) * CELL_SIZE,
                    (p.y + futureY) * CELL_SIZE,
                    CELL_SIZE_PADDED, CELL_SIZE_PADDED);
        }


        if (pieceOrigin.y % 2 == 0) { // so the bomb indicator blinks
            if (bombCooldown >= BOMB_COOLDOWN_LENGTH || ammo >= 5) {
                g.setColor(new Color(128, 0, 0, 200)); // piece origin(for bombing)
                int x = (pieceOrigin.x * CELL_SIZE) + CELL_SIZE;
                int y = (futureY * CELL_SIZE) + CELL_SIZE;
                g.fillRect(x, y, CELL_SIZE_PADDED, CELL_SIZE_PADDED);
            }
        }


        g.setColor(currentPiece.tcolor.color);
        for (Point p : currentPiece.inRotation(currentRotation)) {
            g.fillRect((p.x + pieceOrigin.x) * CELL_SIZE,
                    (p.y + pieceOrigin.y) * CELL_SIZE,
                    CELL_SIZE_PADDED, CELL_SIZE_PADDED);
        }
    }

    private void setCurrentDisplayedMessage(String message) {
        this.currentDisplayedMessage = message;
        this.messageTimeout = DEFAULT_MESSAGE_TIMEOUT;
    }

    private void setCurrentDisplayedMessage(String message, int timeout) {
        this.currentDisplayedMessage = message;
        this.messageTimeout = timeout;
    }

    private void updateMessageCooldown() {
        this.messageTimeout--;
        if (messageTimeout == 0) {
            this.currentDisplayedMessage = "";
        }
    }

    private void drawMessageBox(Graphics g) {
        // draw the box for the message to appear in (erases old message)
        g.setColor(Color.WHITE);
        g.fillRect(CELL_SIZE,
                (CELL_SIZE * (BOARD_HEIGHT_CELLS - 1)),
                (2 * BOARD_WIDTH_CELLS - 2) * CELL_SIZE,
                (CELL_SIZE * 3) / 2);
        if (!this.currentDisplayedMessage.isEmpty()) {
            g.setColor(Color.BLACK);

            Font prevFont = g.getFont();

            Font announcerFont = new Font("Sans Serif", Font.BOLD, 14);
            g.setFont(announcerFont);
            g.drawString(currentDisplayedMessage, CELL_SIZE + (CELL_SIZE / 2), (CELL_SIZE * BOARD_HEIGHT_CELLS));

            g.setFont(prevFont);
        }
    }

    private void drawDamageGauge(Graphics g) {
        if (this.attackQueue.isEmpty()) {
            return;
        }
        g.setColor(Color.red);
        int x = 13 - 5;
        int y = CELL_SIZE * 23;
        int height = (int) (((double) this.attackQueue.size() / 2) * CELL_SIZE);

        g.fillRect(x, y - height, 10, height);
    }

    private void drawAmmoGauge(Graphics g) {
        if (this.ammo <= STARTING_AMMO) {
            return;
        }
        if (this.ammo == MAX_AMMO_AMT) {
            g.setColor(new Color(134, 255, 94));
        } else {
            g.setColor(Color.orange);
        }
        int x = CELL_SIZE * BOARD_WIDTH_CELLS - (3 * CELL_SIZE / 4);
        int y = CELL_SIZE * 23;
        int height = (int) (((double) this.ammo / 2) * CELL_SIZE);

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
        drawAmmoGauge(g);

        drawMessageBox(g);


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

        // Draw the currently falling piece
        drawPiece(g);

        // Show if game over
        g.setColor(Color.red);

        if (this.status == TGameStatus.GAME_OVER) {
            Font gameOverFont = new Font("Sans Serif", Font.PLAIN, 24);
            g.setFont(gameOverFont);

            g.drawString("GAME OVER", (int) (CELL_SIZE * 3.5), CELL_SIZE * BOARD_WIDTH_CELLS);
        }
    }

    public void bombBoard() {
        //From the tetris piece origin, erase surrounding area and send "debris" to random process
        //Other process must call clear line for sand
        if (bombCooldown < BOMB_COOLDOWN_LENGTH) {
            //System.out.println("Bomb on cooldown");
            setCurrentDisplayedMessage("BOMB ON COOLDOWN! (SPENDING AMMO)", 2);
        }

        if (bombCooldown >= BOMB_COOLDOWN_LENGTH) {
            bombCooldown = 0;
        } else {
            if (ammo < BOMB_AMMO_COST) {
                setCurrentDisplayedMessage("NOT ENOUGH AMMO FOR BOMB!", 2);
                return;
            }
            ammo -= BOMB_AMMO_COST;
        }

        Point bombPosition = pieceOrigin;
        bombPosition.x = bombPosition.x - 1;
        bombPosition.y = bombPosition.y - 1;

        for (int r = bombPosition.y; r < bombPosition.y + 5; r++) {
            for (int c = bombPosition.x; c < bombPosition.x + 5; c++) {
                if (!outOfBounds(c, r)) {
                    if (well[c][r] != TColor.OPEN) {
                        well[c][r] = TColor.OPEN;
                        if (new Random().nextDouble() <= BOMB_DEBRIS_ATTACK_CHANCE_PER_CELL) {
                            sendAttack(c, r, Rotation._0, Tetromino.SAND);
                        }
                    }
                }
            }
        }
        newPiece();
    }

    public void updateBombCooldown() {
        bombCooldown++;

        if (this.bombCooldown == BOMB_COOLDOWN_LENGTH) {
            setCurrentDisplayedMessage("BOMB READY", 3);
        }
    }

    public void updateCostCooldown() {
        ammoCostCooldown++;
        if (this.ammoCostCooldown == AMMO_COST_COOLDOWN_LENGTH) {
            AMMO_COST = 2;
        }
    }


    public boolean outOfBounds(int xloc, int yloc) {
        return xloc <= 0 || xloc >= 11 || yloc >= 22 || yloc <= 0;
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
                case KeyEvent.VK_SHIFT -> game.toggleMode();
                case KeyEvent.VK_Q -> game.bombBoard();
                case KeyEvent.VK_E -> game.dropToBottomAndBomb();
                case KeyEvent.VK_R -> game.init();
            }
        }

        public void keyReleased(KeyEvent e) {
        }
    }
}