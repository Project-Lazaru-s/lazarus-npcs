package com.lazarus.npcs.api;

import java.util.UUID;

/**
 * Read-only identity of a human NPC.
 *
 * @param entityId    runtime entity id (changes on reload; prefer {@code uuid})
 * @param uuid        persistent entity UUID
 * @param name        persistent display name ("Alex Shaw"); hostile humans use their kind ("Looter")
 * @param role        survivor, hunter, medic, military, or for hostile humans looter, soldier, cannibal
 * @param faction     haven, rovers, wardens, military, independent, or hostile
 * @param interactive whether empty-hand interaction currently opens a screen (alive, not surrendered,
 *                    not a cannibal)
 */
public record NpcIdentityView(int entityId, UUID uuid, String name, String role, String faction, boolean interactive) {}
