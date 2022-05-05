import java.io.*;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Scanner;

public class Matchmaker {

    public static void invitePeerToGroup(Peer newPeer, ArrayList<Peer> peers) {
        for (Peer p : peers) {
            newPeer.send(MessageType.CONNECT_TO,p.addr + " " + p.port);
        }
        peers.add(newPeer); // add new peer after giving new peer list of existing peers
        newPeer.send("done"); // tell peer that's all of the connections, not sure if necessary
    }

    public static void main(String args[]) {


        ArrayList<Peer> peers = new ArrayList<>();

        new Thread(new PollPeers(peers)).start();
        int connectionNumber = 0;
        try {
            ServerSocket serverSocket = new ServerSocket(5000, 50, Inet4Address.getByName("192.168.137.1"));
            System.out.println("Matchmaker listening on 192.168.137.1, port 5000");
            while(true){

                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection request received from " + clientSocket.getInetAddress().toString().substring(1) + " at port " + clientSocket.getPort());
                OutputStream outStream = clientSocket.getOutputStream();
                InputStream inStream = clientSocket.getInputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(inStream));
                PrintWriter out = new PrintWriter(outStream, true);
                Transceiver tr = new Transceiver(connectionNumber, inStream, outStream);

                String peerAddress = clientSocket.getInetAddress().toString().substring(1);
                int peerPort = clientSocket.getPort();

                Peer p = new Peer(tr, peerAddress, peerPort);

                String connectionRequestCommand = peerAddress + " " + peerPort; // should be "<ip address with '/' trimmed off> <port number>

                p.send(MessageType.HOST_ON, connectionRequestCommand); // tell newly joined peer to host on ip+port reflected to server

                invitePeerToGroup(p, peers); // give peer list of currently active peers

                PeerLeaveThread pLeave = new PeerLeaveThread(p, peers);
                new Thread(pLeave).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class Peer {
        Transceiver tr;
        String addr;
        int port;

        public Peer(Transceiver tr, String addr, int port) {
            this.tr = tr;
            this.addr = addr;
            this.port = port;
        }

        public boolean hasLeft() {
            tr.receive(); // matchmaker itself should never care about messages sent from peers
                        // besides SHUTDOWN messages, but those are handled in Transceiver automatically
            return tr.isClosed;
        }
        // overloaded send method, optional type, default to MessageType.NORMAL
        public void send(MessageType type, String message) {
            tr.send(type, message);
        }
        public void send(String message){
            tr.send(MessageType.NORMAL, message);
        }

        public String toString() {
            return "Address: " + addr + ", Port: " + port;
        }

    }

    static class PeerLeaveThread implements Runnable {
        Peer peer;
        ArrayList<Peer> peers;

        public PeerLeaveThread(Peer peer, ArrayList<Peer> peers) {
            this.peer = peer;
            this.peers = peers;
        }

        @Override
        public void run() {
            while (true) {
                if (peer.hasLeft()) {
                    peers.remove(peer);
                    return;
                }
            }
        }
    }

    static class PollPeers implements Runnable {
        ArrayList<Peer> peers;

        public PollPeers(ArrayList<Peer> peers) {
            this.peers = peers;
        }

        @Override
        public void run() {
            Scanner s = new Scanner(System.in);

            while (true) {
                if (s.nextLine().startsWith("p")) {
                    System.out.println("LISTING CURRENTLY CONNECTED/ACTIVE PEERS");
                    if (peers.isEmpty()) {
                        System.out.println("(none currently connected)");
                    } else {
                        for (Peer p : peers) {
                            System.out.println(p);
                        }
                    }
                }
            }
        }
    }

}
