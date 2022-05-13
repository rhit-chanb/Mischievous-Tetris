import java.io.*;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Scanner;

public class Matchmaker {

    public static void invitePeerToGroup(Peer newPeer, ArrayList<Peer> peers) {
        for (Peer p : peers) {
            newPeer.send(MessageType.CONNECT_TO,p.addr + " " + p.port + " " + p.processID); // send special special connection message to the joining peer
        }
        peers.add(newPeer); // add new peer after giving new peer list of existing peers
        newPeer.send("done"); // tell peer that's all of the connections, not sure if necessary (it isn't)
    }

    public static void main(String args[]) {

        if (args.length != 4) {
            System.err.println("must specify port and address to host the matchmaking server on");
            System.err.println("usage: -port [portNumber] -addr [ipAddress] ");
            System.err.println("ex: -port 26000 -addr localhost");
            return;
        }
        for (String s : args) {
            System.out.println(s);
        }
        int port = Integer.parseInt(args[1]);
        String address = args[3];

        ArrayList<Peer> peers = new ArrayList<>();

        new Thread(new PollPeers(peers)).start();
        int connectionNumber = 0;
        try {
            ServerSocket serverSocket = new ServerSocket(port, 50, Inet4Address.getByName(address));
            System.out.println("Matchmaker listening on " + address + ", port " + port); // added to run configs for now
            while(true){

                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection request received from " + clientSocket.getInetAddress().toString().substring(1) + " at port " + clientSocket.getPort() + " (process number: " + connectionNumber + ")");
                OutputStream outStream = clientSocket.getOutputStream();
                InputStream inStream = clientSocket.getInputStream();
                Transceiver tr = new Transceiver(connectionNumber, inStream, outStream); // shiny stream handler

                String peerAddress = clientSocket.getInetAddress().toString().substring(1);
                int peerPort = clientSocket.getPort();

                Peer p = new Peer(tr, peerAddress, peerPort, connectionNumber); // generate new peer object to store in array

                String connectionRequestCommand = peerAddress + " " + peerPort + " " + connectionNumber; // should be "<ip address with '/' trimmed off> <port number>"

                p.send(MessageType.HOST_ON, connectionRequestCommand); // tell newly joined peer to host on ip + port that it joined the matchmaker with (it doesn't know by default)
                connectionNumber++;
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
        int processID;

        public Peer(Transceiver tr, String addr, int port, int processID) {
            this.tr = tr;
            this.addr = addr;
            this.port = port;
            this.processID = processID;
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
            return "ID: " + processID + ", Address: " + addr + ", Port: " + port;
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
