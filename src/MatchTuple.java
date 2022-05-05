public class MatchTuple {
    String addr;
    int port;

    public MatchTuple(String addr, int port) {
        this.addr = addr;
        this.port = port;
    }
    public boolean equals(MatchTuple mt){
        return (mt.addr.equals(addr)) && (mt.port == port);
    }
}
