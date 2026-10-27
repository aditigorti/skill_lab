/** One piece of shared equipment, for example a camera or a development board. */
final class Equipment {

    /** Short code people type, e.g. CAM-01. Always stored in capitals. */
    final String id;

    /** Kind of item, e.g. Camera. Used to suggest a similar item when one is taken. */
    final String type;

    /** Plain description, e.g. Sony ZV-E10 mirrorless camera. */
    final String name;

    Equipment(String id, String type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
    }
}
