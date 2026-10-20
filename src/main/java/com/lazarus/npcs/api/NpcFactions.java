package com.lazarus.npcs.api;

import com.lazarus.npcs.api.ai.Doctrine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared faction definitions. Add a faction here before adding its optional content in Events. */
public final class NpcFactions {
    public record Definition(String id, String label, int color, List<Integer> survivorVariants,
                             Set<String> rivals, boolean professions, Doctrine doctrine) {
        /** Kept for integrations compiled against the original six-field definition. */
        public Definition(String id, String label, int color, List<Integer> survivorVariants,
                          Set<String> rivals, boolean professions) {
            this(id, label, color, survivorVariants, rivals, professions, Doctrine.DEFAULT);
        }

        public Definition {
            if (id == null || id.length() > 16 || !id.matches("[a-z0-9_]+") || label == null || label.isBlank()
                    || color < 0 || color > 0xFFFFFF || survivorVariants == null || rivals == null
                    || doctrine == null || rivals.contains(id))
                throw new IllegalArgumentException("Invalid faction definition: " + id);
            survivorVariants = List.copyOf(survivorVariants);
            rivals = Set.copyOf(rivals);
        }

        public int variant(int index) {
            if (survivorVariants.isEmpty()) throw new IllegalStateException("No survivor variant for " + id);
            return survivorVariants.get(Math.floorMod(index, survivorVariants.size()));
        }
    }

    private static final List<Definition> ALL = List.of(
            // Haven has a refuge and professions but no rivals, so it never takes part in faction conflict.
            new Definition("haven", "Haven", 0x8FCB91, List.of(1, 2, 3), Set.of(), true,
                    new Doctrine(Doctrine.Stance.HOLD, 14, 2, 0, false, 0.45,
                            Doctrine.Formation.FILE, 3, true)),
            new Definition("rovers", "Rovers", 0xE0AE67, List.of(4, 5, 6), Set.of("wardens"), true,
                    new Doctrine(Doctrine.Stance.MOBILE, 14, 2, 0.34, false, 0.25,
                            Doctrine.Formation.LOOSE, 4, true)),
            new Definition("wardens", "Wardens", 0x84B5D6, List.of(7, 8), Set.of("rovers"), true,
                    new Doctrine(Doctrine.Stance.HOLD, 20, 2, 0, true, 0.20,
                            Doctrine.Formation.WEDGE, 3, true)),
            new Definition("military", "Military", 0xB5C48B, List.of(), Set.of(), false));
    private static final Map<String, Definition> BY_ID = ALL.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Definition::id, definition -> definition));

    static {
        for (Definition definition : ALL)
            for (String rival : definition.rivals())
                if (!BY_ID.containsKey(rival)) throw new IllegalStateException("Unknown rival " + rival);
    }

    private NpcFactions() {}

    public static List<Definition> all() { return ALL; }
    public static Definition get(String id) { return BY_ID.get(id); }
    public static boolean rivals(String first, String second) {
        Definition definition = get(first);
        return definition != null && definition.rivals().contains(second);
    }
    public static String forVariant(int variant) {
        for (Definition definition : ALL)
            if (definition.survivorVariants().contains(variant)) return definition.id();
        return "independent";
    }
}
