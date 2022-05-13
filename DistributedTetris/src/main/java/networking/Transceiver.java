package networking;

import java.io.*;
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

    public String receive(){
        if(isClosed){
            //System.out.println("Streams closed, cannot recv");
            return null;
        }
        String message = "";
        try {
            message = in.readLine();
            //System.out.println("Transceiver received message: " + message);
            if(message == null || message.startsWith(MessageType.SHUTDOWN + " ")){
                System.out.println("Received shutdown message, contact is exiting...");
                this.close(); // mark Transceiver as closed, this propagates because other logic looks at this boolean
                return MessageType.SHUTDOWN.toString();
            } else {
                return message;
            }
        }catch (SocketException e){
            // "workaround" for when a client's sockets get closed but they're still trying to accept connections
            // basically a force close
            if(e.getMessage().equalsIgnoreCase("connection reset")){
                System.out.println("Detected connection reset, shutting down Transceiver object");
                this.close();
            } else {
                e.printStackTrace();
            }

        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void send(MessageType type, String message){
        if(isClosed){
            //System.out.println("Streams closed, cannot send");
            return;
        }

        //System.out.println("Transceiver sending message: ");
        //System.out.println(type + " " + message);

        out.println(type + " " + message);
    }

    public void close(){
        try {
            in.close();
            out.close();
            isClosed = true;
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
