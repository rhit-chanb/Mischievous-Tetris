package tetris;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public enum TColor {
    CYAN("c", new Color(15, 155, 215)),
    ORANGE("o", new Color(227, 91, 2)),
    BLUE("b", new Color(33, 65, 198)),
    YELLOW("y", new Color(227, 159, 2)),
    GREEN("g", new Color(89, 177, 1)),
    PINK("p", new Color(175, 41, 138)),
    RED("r", new Color(215, 15, 55)),
    BLACK("*", Color.BLACK),
    BAR("|", Color.GRAY),
    EMPTY("N", Color.WHITE);

    public final Color color;
    public final String stringRep;

    private final static Map<String, TColor> charToColor;

    TColor(String stringRep, Color color) {
        this.stringRep = stringRep;
        this.color = color;
    }

    public Color toColor() {
        return this.color;
    }

    @Override
    public String toString() {
        return stringRep;
    }

    public static TColor fromString(String input) {
        TColor found = charToColor.get(input);
        return (found == null) ? TColor.EMPTY : found; 
    }

    static {
        charToColor = new HashMap<>();
        for (TColor value : TColor.values()) {
            charToColor.put(value.toString(),value);
        }
    }
}
