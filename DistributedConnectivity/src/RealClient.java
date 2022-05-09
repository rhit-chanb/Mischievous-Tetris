import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Scanner;

public class RealClient {

    ArrayList<Transceiver> connections;
    ServerSocket psuedoServerSocket; // socket that this client is exposing for connections by other peers
    boolean active; // currently unused since everything cleans up nicely, might want to update it when we add tetris on top of or below this
    int nextConnectionID; // "auto" incrementing id to give to peers in order of their connection (TODO: relative numbering might bite us later, could use absolute numbering given by the matchmaking server, or alternatively something like GUIDs)
    Tetris underlying;


    public RealClient(){
        connections = new ArrayList<>();
        active = true;
        nextConnectionID = 0;
    }
    // host on addr and port based on whatever Matchmaker sent you
    public void startHosting(String addr, int port){
        System.out.println("Peer is starting hosting on ip " + addr + ", port " + port);
        // handle server socket and connection thread starting
        try {
            psuedoServerSocket = new ServerSocket(port, 50, Inet4Address.getByName(addr));
            ConnectionThread connThread = new ConnectionThread(this);
            new Thread(connThread).start(); // see thread for all connection logic
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    // TODO: overload the crap out of this, probably the only method we care about really
    public void handleMessage(String message, int from){
        System.out.println("Received Message: " + message);
        if(message == null){
            return;
        }
        String[] argList = message.split(" "); // assume all messages are delimited with spaces
        if(message.startsWith(MessageType.HOST_ON.toString())){
            String hostingAddr = argList[1];
            int hostingPort = Integer.parseInt(argList[2]);
            this.startHosting(hostingAddr, hostingPort);
        } else if(message.startsWith(MessageType.CONNECT_TO.toString())){
            String joinAddr = argList[1];
            int joinPort = Integer.parseInt(argList[2]);
            this.joinPeer(joinAddr, joinPort);
        } else if(message.startsWith(MessageType.NORMAL.toString())){
            // forward to underlying Tetris object (if it exists)
            String toForward = message.substring(7); // truncate off NORMAL header, Tetris shouldn't care about that?

        }
    }
    // join existing peers after connected to Matchmaker and given the list of peers to connect to
    public void joinPeer(String connectAddr, int connectPort){
        try {
            Socket clientSocket = new Socket(connectAddr, connectPort);
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();

            Transceiver tr = new Transceiver(0, inStream, outStream);

            this.connections.add(tr);

            Thread recvThread = new Thread(new ReceiverThread(tr, this, clientSocket));
            recvThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void broadcast(MessageType type, String message){
        for(Transceiver t : connections){
            t.send(type, message);
        }
    }
    // creates an underlying Tetris game and passes a reference to this class for its use
    public void startGame(){
        // only initialize if a game is not already running
        if(this.underlying == null){
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
            while(true){
                try {
                    Socket pseudoClientSocket = this.client.psuedoServerSocket.accept(); // accept connection from opposing peer
                    System.out.println("Peer received connection request from " + pseudoClientSocket.getInetAddress().toString().substring(1) + " at port " + pseudoClientSocket.getPort());
                    OutputStream outStream = pseudoClientSocket.getOutputStream();
                    InputStream inStream = pseudoClientSocket.getInputStream(); // grab streams
                    Transceiver tr = new Transceiver(this.client.nextConnectionID, inStream, outStream); // shiny stream handler, again

                    // pass the completed Transceiver object over to the client's array
                    this.client.connections.add(tr);
                    ReceiverThread recvThread = new ReceiverThread(tr, client, pseudoClientSocket);
                    new Thread(recvThread).start(); //start listening to added transceiver
                    this.client.nextConnectionID++; // increment counter of connections to this client for next connection's id

                } catch (SocketException e){
                    // "workaround" for when a client's sockets get closed but they're still trying to accept connections
                    // basically a force close
                    if(e.getMessage().equals("socket closed")){
                        System.out.println("Detected closed socket, stopping connection thread");
                        break;
                    } else {
                        e.printStackTrace();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    static class ReceiverThread implements Runnable{
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
            while(!tr.isClosed){
                String message = tr.receive();
                this.client.handleMessage(message, tr.contactID); // adding source ID, but not doing anything with it for now
            }
            System.out.println("Detected dead Transceiver...");
            System.out.println("Receiver thread closing...");
            System.out.println("Removing Transceiver from connections array");
            this.client.connections.remove(tr); // when aforementioned boolean in tr is read as true, remove it from arraylist
            try {
                this.pseudoClientSocket.close(); //properly take care of the loose socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String args[]) {
        // args = the port and address of the matchmaking server
        if (args.length != 4) {
            System.out.println("usage: -port [portNumber] -addr [ipAddress] ");
            return;
        }
        for (String s : args) {
            System.out.println(s);
        }
        int port = Integer.parseInt(args[1]);
        String address = args[3];
        RealClient client = new RealClient();


        try {
            Socket clientSocket = new Socket(address, port); // connect to Matchmaker
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();


            Transceiver tr = new Transceiver(-1, inStream, outStream); // Matchmaker gets a special value of -1 for it's ID
            Scanner scanner = new Scanner(System.in);

            Thread recvThread = new Thread(new ReceiverThread(tr, client, clientSocket));
            recvThread.start();

            // theoretically speaking we can technically treat the matchmaker just the same as any other peer
            // double edged sword, bad peers could try to force you to do bad CONNECT_TO and HOST_ON commands that make you crash :(
            // TODO: theoretically after the matchmaking server sends the NORMAL done message the client could stop listening to CONNECT_TO and HOST_ON commands
            while (true) {
                System.out.print(">");
                String toSend = scanner.nextLine();
                if(toSend.equalsIgnoreCase("exit")){
                    System.out.println("Exiting and signaling to close Transceiver objects"); // SCREAM OF DEATH
                    tr.send(MessageType.SHUTDOWN, " shut");
                    // simple broadcast everything (permanent)
                    for(Transceiver t : client.connections){
                        t.send(MessageType.SHUTDOWN, "shut");
                    } //TODO: perhaps abstract this duplicated for loop into a method of RealClient, or maybe somewhere else
                    tr.close();
                    break;
                } else if(toSend.equalsIgnoreCase("/start")){
                    client.startGame();
                }
                else {
                    System.out.println("Sending string: " + toSend);
                    tr.send(MessageType.NORMAL, toSend);
                    // another simple broadcast everything (temporary)
                    for(Transceiver t : client.connections){
                        t.send(MessageType.NORMAL, toSend);
                    }
                }

            }
            if(client.psuedoServerSocket != null){
                client.psuedoServerSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
