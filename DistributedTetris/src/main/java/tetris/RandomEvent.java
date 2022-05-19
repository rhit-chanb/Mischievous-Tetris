package tetris;

import java.awt.*;
import java.util.HashMap;
import java.util.Random;

public enum RandomEvent {
    ADD_AMMO(0),
    REMOVE_AMMO(1),
    CLEAR_LINES(2),
    CLEAR_VERTICAL_LINE(3),
    NO_EVENT(100)
    ;
    public final int num;
    private static final HashMap<Integer, RandomEvent> intToEvent;
    RandomEvent(int num) {
        this.num = num;
    }
    public static RandomEvent fromInt(int i){
        return intToEvent.get(i);
    }
    static {
        intToEvent = new HashMap<>();
        for(RandomEvent event: RandomEvent.values()){
            intToEvent.put(event.num,event);
        }
    }
}
