import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class connection {
    Socket conn;
    BufferedReader in;
    PrintWriter out;

    public connection(Socket conn) throws IOException {
        this.conn = conn;
        this.in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        this.out = new PrintWriter(conn.getOutputStream(), true);
    }
}
