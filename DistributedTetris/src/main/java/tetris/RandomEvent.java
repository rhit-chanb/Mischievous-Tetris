package tetris;

import java.util.HashMap;

public enum RandomEvent {
    ADD_AMMO(0),
    REMOVE_AMMO(1),
    CLEAR_LINES(2),
    CLEAR_VERTICAL_LINE(3),
    ALL_ONE_PIECE(4), // TODO implement me
    NO_EVENT(100);
    private static final HashMap<Integer, RandomEvent> intToEvent;

    static {
        intToEvent = new HashMap<>();
        for (RandomEvent event : RandomEvent.values()) {
            intToEvent.put(event.num, event);
        }
    }

    public final int num;

    RandomEvent(int num) {
        this.num = num;
    }

    public static RandomEvent fromInt(int i) {
        return intToEvent.get(i);
    }
}
