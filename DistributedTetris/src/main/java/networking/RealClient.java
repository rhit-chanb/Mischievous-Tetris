package networking;

import tetris.*;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Supplier;

public class RealClient {

    List<Transceiver> connections;
    ServerSocket pseudoServerSocket; // socket that this client is exposing for connections by other peers
    boolean active; // currently, unused since everything cleans up nicely, might want to update it when we add tetris on top of or below this
    Tetris underlying;
    int processID; // the id of this peer

    public RealClient() {
        connections = new ArrayList<>();
        active = true;
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


            Transceiver tr = new Transceiver(-1, inStream, outStream); // Matchmaker gets a special value of -1 for it's ID
            Scanner scanner = new Scanner(System.in);

            Thread recvThread = new Thread(new ReceiverThread(tr, client, clientSocket));
            recvThread.start();

            // commands should return true if they want to break out of the loop
            Map<String, Supplier<Boolean>> commands = new HashMap<>();
            commands.put("/exit", () -> {
                System.out.println("Exiting and signaling to close Transceiver objects"); // SCREAM OF DEATH
                tr.send(MessageType.SHUTDOWN, " shut");
                // simple broadcast everything (permanent)
                for (Transceiver t : client.connections) {
                    t.send(MessageType.SHUTDOWN, "shut");
                } //TODO: perhaps abstract this duplicated for loop into a method of RealClient, or maybe somewhere else
                tr.close();
                // probably bad practice but whatever
                System.exit(0);
                return true;
            });
            commands.put("/start", () -> {
                client.startGame();
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
            commands.put("help", () -> {
                System.out.println("Available commands: " + commands.keySet());
                System.out.println("Anything not in this list will be sent as a raw message (for testing)");
                return false;
            });

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
                    tr.send(MessageType.NORMAL, entry);
                    // another simple broadcast everything (temporary)
                    for (Transceiver t : client.connections) {
                        t.send(MessageType.NORMAL, entry);
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

    // TODO: overload the crap out of this, probably the only method we care about really
    public void handleMessage(String message, int from) {
        System.out.println("Received networking.Message: " + message + " from process: " + from);
        if (message == null) {
            return;
        }
        String[] argList = message.split(" "); // assume all messages are delimited with spaces
        // handle updating board
        if (argList[0].equals(MessageType.UPDATE_BOARD_STATE.toString())) {
            if (this.underlying != null) {
                this.underlying.handleRecvBoard(argList[1], from);
            }
        }
        // handle another peer losing
        if (argList[0].equals(MessageType.DEATH.toString())){
            if (this.underlying != null) {
                this.underlying.handleDeath(from);
            }
        }
        // handle attacks
        // message is of the form:
        // ATTACK <x pos> <y pos> <rotation state> <piece type>
        if (argList[0].equals(MessageType.ATTACK.toString())){
            if (this.underlying != null) {
                int x = Integer.parseInt(argList[1]);
                int y = Integer.parseInt(argList[2]);
                Point pieceOrigin = new Point(x, y);
                Rotation rotation = Rotation.fromInt(Integer.parseInt(argList[3]));
                Tetromino pieceType = Tetromino.fromInt(Integer.parseInt(argList[4]));

                EnemyPiece attackingPiece = new EnemyPiece(pieceOrigin, rotation, pieceType);

                this.underlying.handleAttack(attackingPiece);
            }
        }




        if (message.startsWith(MessageType.HOST_ON.toString())) {
            String hostingAddr = argList[1];
            int hostingPort = Integer.parseInt(argList[2]);
            this.processID = Integer.parseInt(argList[3]);
            this.startHosting(hostingAddr, hostingPort);
        } else if (message.startsWith(MessageType.CONNECT_TO.toString())) {
            String joinAddr = argList[1];
            int joinPort = Integer.parseInt(argList[2]);
            int peerProcessID = Integer.parseInt(argList[3]);

            this.joinPeer(joinAddr, joinPort, peerProcessID);
        } else if (message.startsWith(MessageType.SET_PROC_ID.toString())) {
            int incomingID = Integer.parseInt(argList[1]);
            handleProcIDSet(incomingID);
        } else if (message.startsWith(MessageType.NORMAL.toString())) {
            // forward to underlying Tetris.Tetris object (if it exists)
            String toForward = message.substring(7); // truncate off NORMAL header, Tetris.Tetris shouldn't care about that?
            if (this.underlying != null) {
                this.underlying.handleMessageEvent(toForward);
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

    // creates an underlying Tetris.Tetris game and passes a reference to this class for its use
    public void startGame() {
        // only initialize if a game is not already running
        if (this.underlying == null) {
            this.underlying = new Tetris();
            underlying.bindToClient(this);
            TetrisThread tetoThread = new TetrisThread(underlying);
            new Thread(tetoThread).start();
        }
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
            System.out.println("Detected dead Transceiver...");
            System.out.println("Receiver thread closing...");
            System.out.println("Removing Transceiver from connections array");
            this.client.connections.remove(tr); // when aforementioned boolean in tr is read as true, remove it from arraylist

            // TODO: @DAVID also remove whatever board is associated with processID


            try {
                this.pseudoClientSocket.close(); //properly take care of the loose socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
