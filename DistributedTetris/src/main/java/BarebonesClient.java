import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Scanner;

public class BarebonesClient {

    static class ReceiverThread implements Runnable{
        Transceiver tr;

        public ReceiverThread(Transceiver tr) {
            this.tr = tr;
        }

        @Override
        public void run() {
            while(!tr.isClosed){
                String message = tr.receive();
                System.out.println(message);
            }
            System.out.println("Detected dead Transceiver...");
            System.out.println("Receiver thread closing...");
            System.out.println("Removing Transceiver from connections array");
        }
    }

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
            Socket clientSocket = new Socket(address, port);
            OutputStream outStream = clientSocket.getOutputStream();
            InputStream inStream = clientSocket.getInputStream();



            Transceiver tr = new Transceiver(0, inStream, outStream);
            Scanner scanner = new Scanner(System.in);

            ReceiverThread receiverThread = new ReceiverThread(tr);

            new Thread(receiverThread).start();

            while (true) {
                switch (scanner.nextLine()) {
                    case "send":
                        System.out.println("String to send: ");
                        String toSend = scanner.nextLine();
                        tr.send(MessageType.NORMAL, toSend);
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