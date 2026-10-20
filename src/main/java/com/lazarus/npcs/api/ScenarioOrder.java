package com.lazarus.npcs.api;

/** Temporary behaviours an encounter can give an NPC. Combat, contracts and surrender always take precedence. */
public enum ScenarioOrder {
    /** Stay within the radius of the anchor and fight threats inside it. */
    GUARD_AREA,
    /** Stay close to the target entity (NPC or player) and fight whoever hurts it. */
    DEFEND_ALLY,
    /** Follow the target player a few steps behind, without pushing or teleporting. */
    FOLLOW_PLAYER,
    /** Stand at the anchor. Still defends itself. */
    WAIT,
    /** Move to the anchor (a safe point) and stay there. */
    RETREAT,
    /** Follow a finite route with the encounter's other actors. */
    TRAVEL;

    public boolean needsTarget() { return this == DEFEND_ALLY || this == FOLLOW_PLAYER; }
}
