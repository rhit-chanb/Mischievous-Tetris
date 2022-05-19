package networking;

import java.util.HashMap;
import java.util.Map;

public enum MessageType {
    SHUTDOWN("shut"),
    NORMAL("normal"),
    BROADCAST("broadcast"),
    HOST_ON("hostOn"),
    CONNECT_TO("connectTo"),
    SET_PROC_ID("setProcId"),
    TETRIS_EVENT("tetrisEvent"),
    UPDATE_BOARD_STATE("updateBoardState"),
    DEATH("death"),
    ATTACK("attack"),
    START_RANDOM_EVENT("startRandomEvent"),
    PROPOSE("propose"),
    UNKNOWN("UNKNOWN");

    private final static Map<String, MessageType> stringToMessageType;

    static {
        stringToMessageType = new HashMap<>();
        for (MessageType value : MessageType.values()) {
            stringToMessageType.put(value.toString(), value);
        }
    }

    private final String stringRep;

    MessageType(String stringRep) {
        this.stringRep = stringRep;
    }

    public static MessageType fromString(String input) {
        MessageType found = stringToMessageType.get(input);
        return (found == null) ? MessageType.UNKNOWN : found;
    }

    @Override
    public String toString() {
        return stringRep;
    }
}
