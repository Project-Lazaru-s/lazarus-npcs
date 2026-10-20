package com.lazarus.npcs;

/** The initial political map. Neutral factions never inherit a rival's reputation change. */
final class FactionRelations {
    private FactionRelations() {}

    static boolean rivals(String first, String second) {
        return com.lazarus.npcs.api.NpcFactions.rivals(first, second);
    }
}
