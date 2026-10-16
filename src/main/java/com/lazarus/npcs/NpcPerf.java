package com.lazarus.npcs;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cheap per-routine counters, read with /lazarusnpcs debug. Server thread only. */
final class NpcPerf {
    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();

    private NpcPerf() {}

    static void record(String routine, long nanos) {
        long[] total = TOTALS.computeIfAbsent(routine, ignored -> new long[3]);
        total[0]++;
        total[1] += nanos;
        total[2] = Math.max(total[2], nanos);
    }

    static String report() {
        if (TOTALS.isEmpty()) return "No NPC routines have run yet.";
        StringBuilder text = new StringBuilder();
        TOTALS.forEach((routine, total) -> text.append(routine).append(": ").append(total[0]).append(" runs, avg ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", total[1] / 1000.0 / total[0])).append(" us, max ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", total[2] / 1000.0)).append(" us\n"));
        return text.toString().trim();
    }

    static long runs(String routine) {
        long[] total = TOTALS.get(routine);
        return total == null ? 0 : total[0];
    }

    static void reset() { TOTALS.clear(); }
}
