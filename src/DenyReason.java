/** Why a request was turned down. The label is used in reports, the short label in the history. */
enum DenyReason {

    OUTSIDE_LAB_HOURS("Outside lab hours", "outside lab hours"),
    IN_THE_PAST("Time already passed", "time passed"),
    CLASH("Already booked by another group", "clash"),
    OWN_OVERLAP("Group already holds that time", "own overlap"),
    BACK_TO_BACK_LIMIT("Back-to-back limit", "back-to-back limit"),
    DAILY_LIMIT("Daily limit", "daily limit");

    final String label;
    final String shortLabel;

    DenyReason(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }
}
