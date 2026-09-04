package hainanjong.game;

/**
 * 座位。顺序为东→南→西→北，逆时针（下家为 next）。
 */
public enum Seat {
    EAST("东"),
    SOUTH("南"),
    WEST("西"),
    NORTH("北");

    public final String cn;

    Seat(String cn) {
        this.cn = cn;
    }

    /** 下家（逆时针下一家）。 */
    public Seat next() {
        return values()[(ordinal() + 1) % 4];
    }

    /** 相对 from 的下家步数：下家=1，对家=2，上家=3。 */
    public int stepsAfter(Seat from) {
        return (ordinal() - from.ordinal() + 4) % 4;
    }

    @Override
    public String toString() {
        return cn + "位";
    }
}
