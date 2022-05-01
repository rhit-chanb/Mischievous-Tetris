import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Scanner;

public class BarebonesClient {


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

        try {
            Socket clientSocket = new Socket(address, port);
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();



            Transceiver tr = new Transceiver(0, inStream, outStream);
            Scanner scanner = new Scanner(System.in);
            while (true) {
                switch (scanner.nextLine()) {
                    case "send":
                        System.out.println("String to send: ");
                        String toSend = scanner.nextLine();
                        tr.send(MessageType.NORMAL, toSend);
                        break;
                    case "recv":
                        System.out.println("Waiting for server response");
                        System.out.println("String from server: " + tr.receive());
                        break;
                    case "exit":
                        System.out.println("Exiting and signaling to close Transceiver objects");
                        tr.send(MessageType.SHUTDOWN, " shut");
                        tr.close();
                        return;
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
