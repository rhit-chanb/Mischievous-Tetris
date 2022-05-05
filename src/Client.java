import javax.naming.ldap.SortKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Scanner;

public class Client {
    public static void main(String[] args) throws IOException {
        System.out.println("Server listening on localhost on port " + args[0]);
        ServerSocket server = new ServerSocket(Integer.parseInt(args[0]));

        ArrayList<connection> conns = new ArrayList<>();
        Socket match = new Socket("localhost", 9999);
        PrintWriter out = new PrintWriter(match.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(match.getInputStream()));
        out.println(args[0]);
        String addr;
        int port;
        while(true){
            addr = in.readLine();
            if(addr.equals("Done")) break;
            port = Integer.parseInt(in.readLine());
            System.out.println("Got " + addr + " " + port);
            connection conn = new connection(new Socket(addr, port));
            conns.add(conn);
            new recvThread(conn).start();
        }
        new connectThread(server,conns).start();
        while (true){
            Scanner sc = new Scanner(System.in);
            String input = sc.nextLine();
            for(connection conn : conns){
                conn.out.println(input);
            }
        }
    }

    public static class connectThread extends Thread{
        ArrayList<connection> conns;
        ServerSocket server;
        public connectThread(ServerSocket server, ArrayList<connection> conns) {
            this.conns = conns;
            this.server = server;
        }

        public void run(){
            while(true){
                try {
                    connection conn = new connection(server.accept());
                    new recvThread(conn).start();
                    conns.add(conn);
                    System.out.println("Connected to " + conn.conn.getInetAddress());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    public static class recvThread extends Thread{
        connection conn;
        public recvThread(connection conn) {
            this.conn = conn;
        }
        public void run(){
            while(true){
                try {
                    System.out.println(conn.in.readLine());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
