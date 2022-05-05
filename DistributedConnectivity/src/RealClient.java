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
    ServerSocket psuedoServerSocket;
    boolean active;
    int nextConnectionID;

    public RealClient(){
        connections = new ArrayList<>();
        active = true;
        nextConnectionID = 0;
    }
    public void startHosting(String addr, int port){
        System.out.println("Peer is starting hosting on ip " + addr + ", port " + port);
        // handle server socket and connection thread starting
        try {
            psuedoServerSocket = new ServerSocket(port, 50, Inet4Address.getByName(addr));
            ConnectionThread connThread = new ConnectionThread(this);
            new Thread(connThread).start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void handleMessage(String message, int from){
        System.out.println("Received Message: " + message);
        String[] argList = message.split(" "); // assume all messages are delimited with spaces
        if(message.startsWith(MessageType.HOST_ON.toString())){
            String hostingAddr = argList[1];
            int hostingPort = Integer.parseInt(argList[2]);
            this.startHosting(hostingAddr, hostingPort);
        } else if(message.startsWith(MessageType.CONNECT_TO.toString())){
            String joinAddr = argList[1];
            int joinPort = Integer.parseInt(argList[2]);
            this.joinPeer(joinAddr, joinPort);
        }
    }
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


    static class ConnectionThread implements Runnable {
        RealClient client;

        public ConnectionThread(RealClient client) {
            this.client = client;
        }
        @Override
        public void run() {
            while(true){
                try {
                    Socket pseudoClientSocket = this.client.psuedoServerSocket.accept();
                    System.out.println("Peer received connection request from " + pseudoClientSocket.getInetAddress().toString().substring(1) + " at port " + pseudoClientSocket.getPort());
                    OutputStream outStream = pseudoClientSocket.getOutputStream();
                    InputStream inStream = pseudoClientSocket.getInputStream();
                    Transceiver tr = new Transceiver(this.client.nextConnectionID, inStream, outStream);

                    // pass the completed Transceiver object over to the client's array
                    this.client.connections.add(tr);
                    ReceiverThread recvThread = new ReceiverThread(tr, client, pseudoClientSocket);
                    new Thread(recvThread).start(); //start listening to added transceiver
                    this.client.nextConnectionID++; // increment counter of connections to this client for next connection's id

                } catch (SocketException e){
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
                this.client.handleMessage(message, tr.contactID);
            }
            System.out.println("Detected dead Transceiver...");
            System.out.println("Receiver thread closing...");
            System.out.println("Removing Transceiver from connections array");
            this.client.connections.remove(tr);
            try {
                this.pseudoClientSocket.close(); //properly take care of the loose socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String args[]) {
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
            Socket clientSocket = new Socket(address, port);
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();


            Transceiver tr = new Transceiver(-1, inStream, outStream); // matchmaker gets a special value of -1 for it's ID
            Scanner scanner = new Scanner(System.in);

            Thread recvThread = new Thread(new ReceiverThread(tr, client, clientSocket));
            recvThread.start();
            while (true) {
                System.out.print(">");
                String toSend = scanner.nextLine();
                if(toSend.equalsIgnoreCase("exit")){
                    System.out.println("Exiting and signaling to close Transceiver objects");
                    tr.send(MessageType.SHUTDOWN, " shut");
                    // simple broadcast everything (permanent)
                    for(Transceiver t : client.connections){
                        t.send(MessageType.SHUTDOWN, "shut");
                    }
                    tr.close();
                    break;
                } else {

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
