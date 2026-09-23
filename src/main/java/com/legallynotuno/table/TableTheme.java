package com.legallynotuno.table;

import java.util.Locale;

/**
 * A table's looks, as data: which block goes in each cell of the 3×3 top, and which block
 * each of the four seats is made of.
 *
 * <p>This is what replaced the hardcoded {@code UnoTable.Type} enum. A theme used to be four
 * materials and a Java constant, so a server that wanted its own table had to ask for a new
 * build; it is now nine cells and four seats read out of {@code themes.yml}, which players can
 * also write from the in-game editor.
 *
 * <h2>Orientation</h2>
 * The grid is stored in TABLE space, not world space: {@code grid[0]} is the far row (the side
 * away from whoever placed the table) and {@code grid[row][0]} is the left column. {@link
 * TableManager} rotates it into the world by the table's yaw when it builds.
 *
 * <p>That rotation is new, and it is why the old "the top is never rotated" note no longer
 * holds. It was true when every theme was a symmetric chequer — rotating a chequer is a no-op
 * — but an author who draws an asymmetric pattern means it to face them the same way every
 * time. A 90° step maps the 3×3 onto itself exactly, so nothing lands off the block grid.
 */
public record TableTheme(String id, String displayName,
                         BlockSpec[][] grid, BlockSpec[] seats, boolean builtIn) {

    /** The top is 3×3. Fixed for now; the editor and the file format both assume it. */
    public static final int SIZE = 3;

    /**
     * Seat order, matching {@code UnoTable.computeSeats()} exactly.
     *
     * <p>Named in table space rather than by compass point, because a table can be placed
     * facing any way and "the near seat" means the same thing every time while "the north
     * seat" does not.
     */
    public enum Seat {
        NEAR("near"), FAR("far"), LEFT("left"), RIGHT("right");

        private final String key;

        Seat(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Seat byKey(String raw) {
            if (raw == null) {
                return null;
            }
            String needle = raw.toLowerCase(Locale.ROOT);
            for (Seat s : values()) {
                if (s.key.equals(needle)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** The cell at this row/column of the top, in table space. */
    public BlockSpec cell(int row, int col) {
        return grid[row][col];
    }

    /** The block the given seat is built from. */
    public BlockSpec seat(Seat seat) {
        return seats[seat.ordinal()];
    }

    /** A theme's id as players type it — lowercase, no spaces. */
    public static String normaliseId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
