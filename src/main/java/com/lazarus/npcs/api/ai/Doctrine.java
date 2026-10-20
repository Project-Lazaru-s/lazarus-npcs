package com.lazarus.npcs.api.ai;

/** Faction combat and travel preferences. Movement goals interpret these values on the server. */
public record Doctrine(Stance stance, int preferredRange, int repositionAfterBursts, double flankShare,
                       boolean bounding, double retreatHealth, Formation formation, int spacing,
                       boolean scavenges) {
    public enum Stance { MOBILE, HOLD }
    public enum Formation { FILE, WEDGE, LOOSE }

    public static final Doctrine DEFAULT = new Doctrine(Stance.HOLD, 16, 2, 0, false, 0.35,
            Formation.FILE, 3, true);

    public Doctrine {
        if (stance == null || formation == null || preferredRange < 4 || preferredRange > 32
                || repositionAfterBursts < 1 || repositionAfterBursts > 4
                || !Double.isFinite(flankShare) || flankShare < 0 || flankShare > 1
                || !Double.isFinite(retreatHealth) || retreatHealth < 0 || retreatHealth > 1
                || spacing < 1 || spacing > 8)
            throw new IllegalArgumentException("Invalid NPC doctrine");
    }
}
