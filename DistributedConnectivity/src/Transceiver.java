import java.io.*;


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
            System.out.println("Streams closed, cannot recv");
            return null;
        }
        String message = "";
        try {
            message = in.readLine();
            System.out.println("Received message: " + message);
            if(message.startsWith(MessageType.SHUTDOWN + " ")){
                System.out.println("Received shutdown message, contact is exiting...");
                this.close();
                return MessageType.SHUTDOWN.toString();
            } else {
                return message;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void send(MessageType type, String message){
        if(isClosed){
            System.out.println("Streams closed, cannot send");
            return;
        }

        System.out.println("Sending message: ");
        System.out.println(type + " " + message);
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
