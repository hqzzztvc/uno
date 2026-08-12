package com.unoplugin.game;

/**
 * An immutable UNO card. Maps to/from the resource-pack texture names used everywhere
 * else (e.g. {@code "red_5"}, {@code "green_skip"}, {@code "wild_draw4"}).
 */
public final class Card {

    public enum Color {
        RED, GREEN, BLUE, YELLOW, WILD;

        public String lower() {
            return name().toLowerCase();
        }
    }

    public enum Kind { NUMBER, SKIP, REVERSE, DRAW2, WILD, WILD_DRAW4 }

    public static final Card WILD = new Card(Color.WILD, Kind.WILD, -1);
    public static final Card WILD_DRAW4 = new Card(Color.WILD, Kind.WILD_DRAW4, -1);

    private final Color color;
    private final Kind kind;
    private final int number; // 0-9 for NUMBER, else -1

    private Card(Color color, Kind kind, int number) {
        this.color = color;
        this.kind = kind;
        this.number = number;
    }

    public static Card number(Color c, int n) {
        return new Card(c, Kind.NUMBER, n);
    }

    public static Card action(Color c, Kind k) {
        return new Card(c, k, -1);
    }

    public Color color() {
        return color;
    }

    public Kind kind() {
        return kind;
    }

    public int number() {
        return number;
    }

    public boolean isWild() {
        return color == Color.WILD;
    }

    /** Resource-pack / hand-display name, e.g. {@code "red_5"}, {@code "wild_draw4"}. */
    public String name() {
        return switch (kind) {
            case NUMBER -> color.lower() + "_" + number;
            case SKIP -> color.lower() + "_skip";
            case REVERSE -> color.lower() + "_reverse";
            case DRAW2 -> color.lower() + "_draw2";
            case WILD -> "wild";
            case WILD_DRAW4 -> "wild_draw4";
        };
    }

    /** A short human label, e.g. {@code "Red 5"}, {@code "Blue Skip"}, {@code "Wild +4"}. */
    public String label() {
        String c = isWild() ? "" : capitalize(color.lower()) + " ";
        return switch (kind) {
            case NUMBER -> c + number;
            case SKIP -> c + "Skip";
            case REVERSE -> c + "Reverse";
            case DRAW2 -> c + "+2";
            case WILD -> "Wild";
            case WILD_DRAW4 -> "Wild +4";
        };
    }

    /** Can this card be legally played on top of {@code top}, given the {@code active} colour? */
    public boolean playableOn(Card top, Color active) {
        if (isWild()) {
            return true;
        }
        if (color == active) {
            return true;
        }
        if (kind == Kind.NUMBER) {
            return top.kind == Kind.NUMBER && number == top.number;
        }
        return kind == top.kind; // matching action symbol (skip/reverse/+2)
    }

    /** Parse a resource-pack name back into a Card. */
    public static Card parse(String name) {
        if (name.equals("wild")) {
            return WILD;
        }
        if (name.equals("wild_draw4")) {
            return WILD_DRAW4;
        }
        int us = name.indexOf('_');
        Color c = Color.valueOf(name.substring(0, us).toUpperCase());
        String rest = name.substring(us + 1);
        return switch (rest) {
            case "skip" -> action(c, Kind.SKIP);
            case "reverse" -> action(c, Kind.REVERSE);
            case "draw2" -> action(c, Kind.DRAW2);
            default -> number(c, Integer.parseInt(rest));
        };
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Value equality — two Red 5s are the same card, however they were constructed. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Card other
                && color == other.color && kind == other.kind && number == other.number;
    }

    @Override
    public int hashCode() {
        return (color.ordinal() * 31 + kind.ordinal()) * 31 + number;
    }

    @Override
    public String toString() {
        return name();
    }
}
