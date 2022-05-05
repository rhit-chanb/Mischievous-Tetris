import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

public class MatchMaker {
    public static void main(String args[]) throws IOException {
        System.out.println("Server listening on localhost on port 9999");
        ArrayList<MatchTuple> connections = new ArrayList<>();
        ServerSocket server = new ServerSocket(9999);
        Socket client;
        BufferedReader in;
        PrintWriter out;
        while(true){
            client = server.accept();
            in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            out = new PrintWriter(client.getOutputStream(), true);
            String addr = client.getInetAddress().toString().substring(1);
            int port = Integer.parseInt(in.readLine());
            System.out.println("Connected to " + addr);
            System.out.println("port: " + port);
            MatchTuple mt = new MatchTuple(addr,port);
            connections.add(mt);
            for(MatchTuple conc : connections){
                if(!mt.equals(conc)){
                    out.println(conc.addr);
                    out.println(conc.port);
                }
            }
            out.println("Done");
            client.close();
        }
    }
}
