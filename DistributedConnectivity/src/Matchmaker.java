import java.io.*;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Scanner;

public class Matchmaker {

    public static void invitePeerToGroup(Peer newPeer, ArrayList<Peer> peers) {
        for (Peer p : peers) {
            newPeer.send(MessageType.CONNECT_TO,p.addr + " " + p.port); // send special special connection message to the joining peer
        }
        peers.add(newPeer); // add new peer after giving new peer list of existing peers
        newPeer.send("done"); // tell peer that's all of the connections, not sure if necessary (it isn't)
    }

    public static void main(String args[]) {


        ArrayList<Peer> peers = new ArrayList<>();

        new Thread(new PollPeers(peers)).start();
        int connectionNumber = 0;
        try {
            ServerSocket serverSocket = new ServerSocket(5000, 50, Inet4Address.getByName("192.168.137.1"));
            System.out.println("Matchmaker listening on 192.168.137.1, port 5000"); // TODO: move this hardcoding to a config or smth
            while(true){

                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection request received from " + clientSocket.getInetAddress().toString().substring(1) + " at port " + clientSocket.getPort());
                OutputStream outStream = clientSocket.getOutputStream();
                InputStream inStream = clientSocket.getInputStream();
                Transceiver tr = new Transceiver(connectionNumber, inStream, outStream); // shiny stream handler

                String peerAddress = clientSocket.getInetAddress().toString().substring(1);
                int peerPort = clientSocket.getPort();

                Peer p = new Peer(tr, peerAddress, peerPort); // generate new peer object to store in array

                String connectionRequestCommand = peerAddress + " " + peerPort; // should be "<ip address with '/' trimmed off> <port number>"

                p.send(MessageType.HOST_ON, connectionRequestCommand); // tell newly joined peer to host on ip + port that it joined the matchmaker with (it doesn't know by default)

                invitePeerToGroup(p, peers); // give peer list of currently active peers

                PeerLeaveThread pLeave = new PeerLeaveThread(p, peers); // have the Matchmaker eavesdrop on broadcasts and take off peers from the connected peers array when they send SHUTDOWN messages
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
        // for debugging
        public String toString() {
            return "Address: " + addr + ", Port: " + port;
        }

    }
    // basically a receiver thread that only cares about SHUTDOWN, actual logic handled in Transceiver
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

    // debug command thread for showing currently connected peers
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
