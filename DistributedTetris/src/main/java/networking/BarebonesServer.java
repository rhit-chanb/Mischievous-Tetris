package networking;

import java.io.*;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class BarebonesServer {

    public static void main(String args[]) {
        if (args.length != 4) {
            System.err.println("usage: -port [portNumber] -addr [ipAddress] ");
            return;
        }
        for (String s : args) {
            System.out.println(s);
        }
        int port = Integer.parseInt(args[1]);
        String address = args[3];

        try {
            ServerSocket serverSocket = new ServerSocket(port, 50, Inet4Address.getByName(address));
            int connectionNumber = 0;

            Socket clientSocket = serverSocket.accept();
            System.out.println("Connection request received from " + clientSocket.getInetAddress() + " at port " + clientSocket.getPort());
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();

            Transceiver tr = new Transceiver(connectionNumber, inStream, outStream);
            Scanner scanner = new Scanner(System.in);
            while (true) {
                switch (scanner.nextLine()) {
                    case "send":
                        System.out.println("String to send: ");
                        String toSend = scanner.nextLine();
                        tr.send(MessageType.NORMAL, toSend);
                        break;
                    case "recv":
                        System.out.println("Waiting for client response");
                        System.out.println("String from client: " + tr.receive());
                        break;
                    default:
                        System.out.println("invalid command, either 'send' or 'recv' are accepted");
                        break;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
