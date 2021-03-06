package networking;

import tetris.EnemyPiece;
import tetris.RandomEvent;
import tetris.Rotation;
import tetris.Tetris;
import tetris.TetrisThread;
import tetris.Tetromino;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public class RealClient {

    static Transceiver toMatchmaker;
    List<Transceiver> connections;
    ServerSocket pseudoServerSocket; // socket that this client is exposing for connections by other peers
    boolean active; // currently, unused since everything cleans up nicely, might want to update it when we add tetris on top of or below this
    Tetris underlying;
    int processID; // the id of this peer
    private boolean choosingRandomEvent;
    private ArrayList<Integer> proposals;
    Semaphore lock;
    static Map<String, Supplier<Boolean>> commands;

    public RealClient() {
        connections = new ArrayList<>();
        active = true;
        choosingRandomEvent = false;
        proposals = new ArrayList<>();
        lock = new Semaphore(1);
    }

    public static boolean displayHelp() {
        System.out.println("Available commands: " + commands.keySet());
        System.out.println("Anything not in this list will be sent as a raw message (for testing)");
        return false;
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("must specify port and address of the matchmaking server");
            System.err.println("usage: -port [portNumber] -addr [ipAddress] ");
            System.err.println("ex: -port 26000 -addr localhost ");
            return;
        }
        for (String s : args) {
            System.out.println(s);
        }
        int port = Integer.parseInt(args[1]);
        String address = args[3];
        RealClient client = new RealClient();

        try {
            Socket clientSocket;
            try {
                clientSocket = new Socket(address, port); // connect to Matchmaker
            } catch (ConnectException e) {
                System.err.println("Failed to connect to the specified address and port (is the matchmaking server running?)");
                return;
            }

            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();


            toMatchmaker = new Transceiver(-1, inStream, outStream); // Matchmaker gets a special value of -1 for it's ID
            Scanner scanner = new Scanner(System.in);

            Thread recvThread = new Thread(new ReceiverThread(toMatchmaker, client, clientSocket));
            recvThread.start();

            // commands should return true if they want to break out of the loop
            commands = new HashMap<>();
            commands.put("/exit", () -> {
                client.shutdownProcedure();
                return true;
            });
            commands.put("/start", () -> {
                client.startGame();
                return false;
            });
            commands.put("/random", () -> {
                client.startRandomEvent();
                return false;
            });
            Supplier<Boolean> listCommand = () -> {
                // list the peers we're currently connected to
                System.out.println("Currently connected to: ");
                if (client.connections.isEmpty()) {
                    System.out.println("(none currently connected)");
                }
                for (Transceiver t : client.connections) {
                    System.out.println("Process #" + t.contactID);
                }
                return false;
            };
            commands.put("/list", listCommand);
            commands.put("/ls", listCommand);
            commands.put("help", RealClient::displayHelp);

            displayHelp();

            // theoretically speaking we can technically treat the matchmaker just the same as any other peer
            // double edged sword, bad peers could try to force you to do bad CONNECT_TO and HOST_ON commands that make you crash :(
            // TODO: theoretically after the matchmaking server sends the NORMAL done message the client could stop listening to CONNECT_TO and HOST_ON commands
            while (true) {
                System.out.print(">");
                String entry = scanner.nextLine().toLowerCase();

                Supplier<Boolean> foundCommand =
                        entry.equals("") ? commands.get("help") : commands.get(entry);
                if (foundCommand != null) {
                    boolean shouldExit = foundCommand.get();
                    if (shouldExit) {
                        break;
                    }
                } else {
                    System.out.println("Sending string: " + entry);
                    toMatchmaker.send(MessageType.NORMAL, entry);
                    // another simple broadcast everything (temporary)
                    for (Transceiver toClient : client.connections) {
                        toClient.send(MessageType.NORMAL, entry);
                    }
                }
            }
            if (client.pseudoServerSocket != null) {
                client.pseudoServerSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void shutdownProcedure() {
        System.out.println("Exiting and signaling to close Transceiver objects"); // SCREAM OF DEATH
        toMatchmaker.send(MessageType.SHUTDOWN, " shut");
        // simple broadcast everything (permanent)
        for (Transceiver toClient : this.connections) {
            toClient.send(MessageType.SHUTDOWN, "shut");
        }
        //TODO: perhaps abstract this duplicated for loop into a method of RealClient, or maybe somewhere else
        toMatchmaker.close();
        // probably bad practice but whatever
        System.exit(0);
    }

    // host on addr and port based on whatever Matchmaker sent you
    public void startHosting(String addr, int port) {
        System.out.println("Peer (Process ID: " + processID + ") is starting hosting on ip " + addr + ", port " + port);
        // handle server socket and connection thread starting
        try {
            pseudoServerSocket = new ServerSocket(port, 50, Inet4Address.getByName(addr));
            ConnectionThread connThread = new ConnectionThread(this);
            new Thread(connThread).start(); // see thread for all connection logic
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void handleMessage(String message, int from) {
//        System.out.println("Received networking.Message: " + message + " from process: " + from);
        if (message == null) {
            return;
        }
        String[] argList = message.split(" "); // assume all messages are delimited with spaces

        if (argList.length < 1) {
            System.err.println("Received message without a message type: '" + message + "' from " + from);
            return;
        }
        MessageType type = MessageType.fromString(argList[0]);

        switch (type) {
            case SHUTDOWN -> {
                if (this.underlying != null) {
                    this.underlying.handleDisconnect(from);
                }
            }
            case NORMAL -> {
                // forward to underlying Tetris.Tetris object (if it exists)
                String toForward = message.substring(MessageType.NORMAL.toString().length()); // truncate off NORMAL header, Tetris.Tetris shouldn't care about that?
                if (this.underlying != null) {
                    this.underlying.handleMessageEvent(toForward);
                }
            }
            case BROADCAST -> {
                // TODO no handling written yet
            }
            case HOST_ON -> {
                String hostingAddr = argList[1];
                int hostingPort = Integer.parseInt(argList[2]);
                this.processID = Integer.parseInt(argList[3]);
                this.startHosting(hostingAddr, hostingPort);
            }
            case CONNECT_TO -> {
                String joinAddr = argList[1];
                int joinPort = Integer.parseInt(argList[2]);
                int peerProcessID = Integer.parseInt(argList[3]);

                this.joinPeer(joinAddr, joinPort, peerProcessID);
            }
            case SET_PROC_ID -> {
                int incomingID = Integer.parseInt(argList[1]);
                handleProcIDSet(incomingID);
            }
            case TETRIS_EVENT -> {
                // TODO no handling written yet for tetris events
            }
            case UPDATE_BOARD_STATE -> {
                // handle updating board
                if (this.underlying != null) {
                    this.underlying.handleRecvBoard(argList[1], from);
                }
            }
            case DEATH -> {
                // handle another peer losing
                if (this.underlying != null) {
                    this.underlying.handleDeath(from);
                }
            }
            case ATTACK -> {
                // handle attacks
                // message is of the form:
                // ATTACK <x pos> <y pos> <rotation state> <piece type>
                if (this.underlying != null) {
                    int x = Integer.parseInt(argList[1]);
                    int y = Integer.parseInt(argList[2]);
                    Point pieceOrigin = new Point(x, y);
                    Rotation rotation = Rotation.fromInt(Integer.parseInt(argList[3]));
                    Tetromino pieceType = Tetromino.fromInt(Integer.parseInt(argList[4]));

                    EnemyPiece attackingPiece = new EnemyPiece(pieceOrigin, rotation, pieceType);

                    this.underlying.handleAttack(attackingPiece, from);
                }
            }
            case START_RANDOM_EVENT -> {
                propose();
            }
            case PROPOSE -> {
                if (choosingRandomEvent) {
//                    System.out.println("Adding proposal to list");
                    int eventNum = Integer.parseInt(argList[1]);
                    proposals.add(eventNum);
                    decide();
                }
            }
            case UNKNOWN -> {
                System.err.println("Tried to handle unknown event " + message);
            }
            default -> {
                System.err.println("No handling set up for message type " + type + " '" + message + "'");
            }
        }
    }

    private void handleProcIDSet(int incomingID) {
        for (Transceiver t : connections) {
            if (t.contactID == -1) {
                t.contactID = incomingID;
                break;
            }
        }
    }

    // join existing peers after connected to Matchmaker and given the list of peers to connect to
    public void joinPeer(String connectAddr, int connectPort, int peerProcessID) {
        try {
            Socket clientSocket = new Socket(connectAddr, connectPort);
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();

            Transceiver tr = new Transceiver(peerProcessID, inStream, outStream);

            this.connections.add(tr);

            tr.send(MessageType.SET_PROC_ID, Integer.toString(this.processID));

            Thread recvThread = new Thread(new ReceiverThread(tr, this, clientSocket));
            recvThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void broadcast(MessageType type, String message) {
        for (Transceiver t : connections) {
            t.send(type, message);
        }
    }

    // creates an underlying Tetris game and passes a reference to this class for its use
    public void startGame() {
        // only initialize if a game is not already running
        if (this.underlying == null) {
            this.underlying = new Tetris();
            underlying.bindToClient(this);
            TetrisThread tetoThread = new TetrisThread(underlying, this);
            new Thread(tetoThread).start();
            System.out.println("Starting...");
        }
    }

    public void propose() {
        choosingRandomEvent = true;
        int proposal = this.underlying != null ? getRandomEventNum() : 100;
        proposals.add(proposal);
//        System.out.println("I am proposing event " + proposal);
        broadcast(MessageType.PROPOSE, Integer.toString(proposal));
    }

    public void decide() {
        try {
            lock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
//        System.out.println("Num Proposals: " + proposals.size() + " Needed amt: " + (connections.size() + 1));
        if (proposals.size() >= connections.size() + 1) {
            try {
//                System.out.println("Deciding on event " + RandomEvent.fromInt(mostFrequent(proposals)));
                if (this.underlying != null) underlying.triggerRandomEvent(RandomEvent.fromInt(mostFrequent(proposals)));
                proposals = new ArrayList<>();
                choosingRandomEvent = false;
            } catch (ConcurrentModificationException e) {
                System.err.println("Already deciding somehow");
            }
        }
        lock.release();
    }

    public void startRandomEvent() {
        if (!choosingRandomEvent) {
//            System.out.println("Starting Random Event polling");
            broadcast(MessageType.START_RANDOM_EVENT, "");
            propose();
            decide();
        }
    }

    public int getRandomEventNum() {
        return ((new Random()).nextInt(RandomEvent.values().length - 1));
    }

    static int mostFrequent(ArrayList<Integer> list) {
        int result = 0;
        int num = 0;
        HashMap<Integer, Integer> freq = new HashMap<>();

        for(Integer i : list){
            if(freq.containsKey(i)){
                freq.put(i,freq.get(i)+1);
            }
            else freq.put(i,1);
        }
        for(int i : freq.keySet()){
            if(freq.get(i) > result){
                result = freq.get(i);
                num = i;
            }
        }
        return num;
    }

    static class ConnectionThread implements Runnable {
        RealClient client;

        public ConnectionThread(RealClient client) {
            this.client = client;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Socket pseudoClientSocket = this.client.pseudoServerSocket.accept(); // accept connection from opposing peer
                    System.out.println("Peer received connection request from " + pseudoClientSocket.getInetAddress().toString().substring(1) + " at port " + pseudoClientSocket.getPort());
                    OutputStream outStream = pseudoClientSocket.getOutputStream();
                    InputStream inStream = pseudoClientSocket.getInputStream(); // grab streams
                    Transceiver tr = new Transceiver(-1, inStream, outStream); // shiny stream handler, again TODO: REASSIGN

                    // pass the completed Transceiver object over to the client's array
                    this.client.connections.add(tr);
                    ReceiverThread recvThread = new ReceiverThread(tr, client, pseudoClientSocket);
                    new Thread(recvThread).start(); //start listening to added transceiver

                } catch (SocketException e) {
                    // "workaround" for when a client's sockets get closed but they're still trying to accept connections
                    // basically a force close
                    System.out.println(e.getMessage());
                    System.out.println("Detected closed socket, stopping connection thread");
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    static class ReceiverThread implements Runnable {
        Transceiver tr;
        RealClient client;
        Socket pseudoClientSocket;

        public ReceiverThread(Transceiver tr, RealClient client, Socket pseudoClientSocket) {
            this.tr = tr;
            this.client = client;
            this.pseudoClientSocket = pseudoClientSocket;
        }

        @Override
        public void run() {
            while (!tr.isClosed) {
                String message = tr.receive();
                this.client.handleMessage(message, tr.contactID); // adding source ID, but not doing anything with it for now
            }
            int processID = tr.contactID;
            System.out.println("Detected dead Transceiver... closing it.");
            this.client.connections.remove(tr);

            try {
                this.pseudoClientSocket.close(); //properly take care of the loose socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
