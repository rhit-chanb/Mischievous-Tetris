package tetris;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PieceBag implements Comparable<PieceBag> {
    final List<Tetromino> contents;
    final long creationEpoch;
    final int creatorProcessId;

    private PieceBag(List<Tetromino> contents, long creationEpoch, int creatorProcessId) {
        this.contents = contents;
        this.creationEpoch = creationEpoch;
        this.creatorProcessId = creatorProcessId;
    }

    public static PieceBag generateNewBag(int processId) {
        // Needs double list since List.of() is immutable
        List<Tetromino> pieces = new ArrayList<>(List.of(Tetromino.values()));
        Collections.shuffle(pieces);
        return new PieceBag(pieces, System.currentTimeMillis(), processId);
    }

    @Override
    public int compareTo(PieceBag other) {
        if (creationEpoch == other.creationEpoch) {
            return (creatorProcessId > other.creatorProcessId) ? 1 : -1;
        } else {
            return (creationEpoch > other.creationEpoch) ? 1 : -1;
        }
    }
}
