package tetris;

import networking.MessageType;
import networking.RealClient;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.Serial;
import java.util.*;
import java.util.List;

import static tetris.RandomEvent.*;

public class Tetris extends JPanel {
    public static final String BOARD_ROW_SEPARATOR = "S";
    @Serial
    private static final long serialVersionUID = -8715353373678321308L;
    private final int softLockConstant = 2;
    private final List<Tetromino> nextPieces = new ArrayList<>();
    private final Map<Integer, TColor[][]> opponentBoards = new HashMap<>();
    private final double RANDOM_EVENT_CHANCE = 0.2;
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

    @Deprecated(since = "Use RealClient instead") // probably bad Since usage?
    public static void main(String[] args) {
        JFrame f = new JFrame("Mischievous Tetris");
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
                    case KeyEvent.VK_J -> game.rotate(-1);
                    case KeyEvent.VK_K -> game.rotate(+1);
                    case KeyEvent.VK_L -> game.rotate(2);
                    case KeyEvent.VK_A -> game.move(-1);
                    case KeyEvent.VK_D -> game.move(+1);
                    case KeyEvent.VK_S -> {
                        game.dropDown();
                        game.score += 1;
                    }
                    case KeyEvent.VK_SPACE -> game.dropToBottom();
                    case KeyEvent.VK_R -> game.init();
                }
            }

            public void keyReleased(KeyEvent e) {
            }
        });

        // Make the falling piece drop every second
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    game.dropDown();
                    game.attemptRandomEvent();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void attemptRandomEvent(){
        Random rand = new Random();
        double roll = rand.nextDouble();
        if(roll <= this.RANDOM_EVENT_CHANCE){
            if(this.client != null){
                this.client.startRandomEvent();
            }
        }
    }

    public void bindToClient(RealClient client) {
        this.client = client;
    }

    public void triggerRandomEvent(RandomEvent event){
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

    public void handleDisconnect(int fromProcess){
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
        for(EnemyPiece p: attackQueue){
            System.out.println(piece);
        }
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
        TColor[][] result = new TColor[12][24];
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
        well = new TColor[12][24];
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 23; j++) {
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
        this.ammo = 8;
        newPiece();
        this.status = TGameStatus.PLAYING;

    }
    // accidental on purpose recursion here
    public boolean takeAttackFromQueue(){
        boolean success = false;
        if(!attackQueue.isEmpty()){
            EnemyPiece toTake = attackQueue.remove(0);
            if(ammo > 0){
                ammo--;
                return true;
            }


            System.out.println("Taking attack: " + toTake);

            System.out.println("Remaining attack queue: ");
            for(EnemyPiece p: attackQueue){
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
        if(this.status == TGameStatus.GAME_OVER){
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
    public void toggleMode(){
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

        this.broadcastMessage(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));

        checkForTopOut();
        if (this.status == TGameStatus.PLAYING) {
            newPiece();
        } else {
            this.broadcastMessage(MessageType.DEATH, "");
        }
    }
    public void fixToWellNoNewPiece(){
        for (Point p : currentPiece.inRotation(rotation)) {
            well[pieceOrigin.x + p.x][pieceOrigin.y + p.y] = currentPiece.tcolor;
        }
        clearRows();

        // TODO probably abstract broadcasts elsewhere so we don't need to null check
        //  or maybe make a fake client
        //  client was null (maybe testing offline?)

        this.broadcastMessage(MessageType.UPDATE_BOARD_STATE, this.BoardToString(this.well));

        checkForTopOut();

        if(this.status == TGameStatus.GAME_OVER){
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
        if(this.ammo > 0){
            if(this.attacking){
                g.setColor(new Color(242, 20, 8, 50)); // should be like transparent reddish
            } else {
                g.setColor(new Color(0, 255, 0, 75)); // should be transparent greenish
            }
        }

        for (Point p : currentPiece.inRotation(rotation)) {
            g.fillRect((p.x + pieceOrigin.x) * 26,
                    (p.y + checkTheoreticalPos()) * 26,
                    25, 25);
        }

        g.setColor(currentPiece.tcolor.color);
        for (Point p : currentPiece.inRotation(rotation)) {

            g.fillRect((p.x + pieceOrigin.x) * 26,
                    (p.y + pieceOrigin.y) * 26,
                    25, 25);
        }
    }

    private void drawDamageGauge(Graphics g) {
        if(this.attackQueue.isEmpty()){
            return;
        }
        g.setColor(Color.red);
        int x = 13-5;
        int y = 26 * 23;
        int height = (this.attackQueue.size() * 26) + 26;

        g.fillRect(x, y-height, 10, height);


    }

    @Override
    public void paintComponent(Graphics g) {
        // Paint the well
        Color boardBackground = g.getColor();
        g.fillRect(0, 0, 26 * 12, 26 * 23);
        g.setColor(Color.red);
        g.fillRect(0, (26 * 4) - 2, 26 * 12, 2);

        g.setColor(Color.white);
        g.fillRect((26 * 12) + 10,0,1000,26*23);
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 23; j++) {
                g.setColor(well[i][j].color);
                g.fillRect(26 * i, 26 * j, 25, 25);
            }
        }

        drawDamageGauge(g);

        g.setColor(boardBackground);

        int offset = 1;
        for (TColor[][] board : opponentBoards.values()) {
            g.fillRect(offset * (312), 0, 26 * 12, 26 * 23);
            for (int i = 0; i < 12; i++) {
                for (int j = 0; j < 23; j++) {
                    g.setColor(board[i][j].color);
                    g.fillRect((26 * i) + offset * (312), 26 * j, 25, 25);
                }
            }
            offset++;
        }

        // Display the score
        g.setColor(Color.WHITE);
        g.drawString("score: " + score, 19 * 12, 25);

        g.setColor(Color.WHITE);
        g.drawString("ammo: " + ammo, 19 * 12, 45);

        // Show if game over
        g.setColor(Color.red);

        if (this.status == TGameStatus.GAME_OVER) {
            Font gameOverFont = new Font("Sans Serif", Font.PLAIN, 24);
            g.setFont(gameOverFont);

            g.drawString("GAME OVER", (int) (26 * 3.5), 26 * 12);
        }


        // Draw the currently falling piece
        drawPiece(g);
    }
}