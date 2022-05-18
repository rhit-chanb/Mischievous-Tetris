package tetris;

import java.awt.*;
import java.util.HashMap;
import java.util.Random;

public enum RandomEvent {
    EVENT0(0),
    EVENT1(1),
    EVENT2(2),
    EVENT3(3),
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
