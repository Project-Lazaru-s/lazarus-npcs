package com.lazarus.npcs.ai;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.ai.Doctrine;
import java.util.Map;
import net.minecraft.world.entity.Mob;

/** The same doctrine lookup is used by ambient hostiles and faction scenario actors. */
public final class CombatProfiles {
    private static final Map<String, Doctrine> HOSTILE = Map.of(
            "looter", new Doctrine(Doctrine.Stance.MOBILE, 14, 1, 0.34, false, 0.45,
                    Doctrine.Formation.LOOSE, 4, true),
            "soldier", new Doctrine(Doctrine.Stance.HOLD, 20, 2, 0, true, 0.30,
                    Doctrine.Formation.WEDGE, 3, true));

    private CombatProfiles() {}

    public static Doctrine doctrine(Mob actor) {
        var identity = NpcApi.identity(actor);
        if (identity.isEmpty()) return Doctrine.DEFAULT;
        Doctrine hostile = HOSTILE.get(identity.get().role());
        if (hostile != null) return hostile;
        NpcFactions.Definition faction = NpcFactions.get(identity.get().faction());
        return faction == null ? Doctrine.DEFAULT : faction.doctrine();
    }
}
