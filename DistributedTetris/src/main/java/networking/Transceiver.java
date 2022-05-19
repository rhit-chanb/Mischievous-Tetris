package networking;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.SocketException;

public class Transceiver {
    public int contactID;
    public BufferedReader in;
    public PrintWriter out;
    public boolean isClosed;

    public Transceiver(int contactID, InputStream in, OutputStream out) {
        this.contactID = contactID;
        this.in = new BufferedReader(new InputStreamReader(in));
        this.out = new PrintWriter(out, true);
        isClosed = false;
    }

    public String receive() {
        if (isClosed) {
            return null;
        }
        try {
            String message = in.readLine();
            if (message == null || message.startsWith(MessageType.SHUTDOWN + " ")) {
                if (message == null) {
                    System.out.println("Received null-message from contact " + contactID + ", so disconnecting...");
                } else {
                    System.out.println("Received shutdown message, contact " + contactID + " is exiting...");
                }
                this.close(); // mark Transceiver as closed, this propagates because other logic looks at this boolean
                return MessageType.SHUTDOWN.toString();
            } else {
                return message;
            }
        } catch (SocketException e) {
            // "workaround" for when a client's sockets get closed but they're still trying to accept connections
            // basically a force close
            System.out.println(e.getMessage());
            System.out.println("Detected connection reset, shutting down Transceiver object");
            this.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void send(MessageType type, String message) {
        if (isClosed) {
            return;
        }
        out.println(type + " " + message);
    }

    public void close() {
        try {
            in.close();
            out.close();
            isClosed = true;
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
