public class Message {
    public MessageType type;
    public String content;
    public int from;

    public Message(MessageType type, String content, int from) {
        this.type = type;
        this.content = content;
        this.from = from;
    }
}
