/** Lab hours and fairness rules. Every value can be changed from menu 9. */
final class Policy {

    static final int DEFAULT_OPEN = 8;
    static final int DEFAULT_CLOSE = 20;
    static final int DEFAULT_MAX_IN_A_ROW = 3;
    static final int DEFAULT_MAX_PER_DAY = 4;

    int openHour = DEFAULT_OPEN;
    int closeHour = DEFAULT_CLOSE;

    /** Longest unbroken stretch one group may hold one item. 0 means the rule is off. */
    int maxHoursInARow = DEFAULT_MAX_IN_A_ROW;

    /** Most hours one group may hold one item on one day. 0 means the rule is off. */
    int maxHoursPerDay = DEFAULT_MAX_PER_DAY;

    /** true: a freed slot goes to waiting groups in turn. false: first come, first served. */
    boolean rotatingPriority = true;

    int hoursPerDay() {
        return closeHour - openHour;
    }

    void copyFrom(Policy other) {
        openHour = other.openHour;
        closeHour = other.closeHour;
        maxHoursInARow = other.maxHoursInARow;
        maxHoursPerDay = other.maxHoursPerDay;
        rotatingPriority = other.rotatingPriority;
    }

    void reset() {
        openHour = DEFAULT_OPEN;
        closeHour = DEFAULT_CLOSE;
        maxHoursInARow = DEFAULT_MAX_IN_A_ROW;
        maxHoursPerDay = DEFAULT_MAX_PER_DAY;
        rotatingPriority = true;
    }
}
