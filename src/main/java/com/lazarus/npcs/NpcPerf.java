package com.lazarus.npcs;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Cheap per-routine counters, read with /lazarusnpcs debug. Server thread only. */
@Mod.EventBusSubscriber(modid = LazarusNpcs.ID)
public final class NpcPerf {
    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();
    private static final long[] TICKS = new long[1200];
    private static int cursor, count;
    private static long thisTick;

    private NpcPerf() {}

    public static void record(String routine, long nanos) {
        long[] total = TOTALS.computeIfAbsent(routine, ignored -> new long[3]);
        total[0]++;
        total[1] += nanos;
        total[2] = Math.max(total[2], nanos);
        if (!routine.startsWith("AI budget denied")) thisTick += nanos;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        TICKS[cursor] = thisTick;
        cursor = (cursor + 1) % TICKS.length;
        count = Math.min(count + 1, TICKS.length);
        thisTick = 0;
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { reset(); }

    static String report() {
        if (TOTALS.isEmpty()) return "No NPC routines have run yet.";
        StringBuilder text = new StringBuilder();
        TOTALS.forEach((routine, total) -> text.append(routine).append(": ").append(total[0]).append(" runs, avg ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", total[1] / 1000.0 / total[0])).append(" us, max ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", total[2] / 1000.0)).append(" us\n"));
        if (count > 0) {
            long sum = 0, max = 0;
            for (int i = 0; i < count; i++) { sum += TICKS[i]; max = Math.max(max, TICKS[i]); }
            text.append("AI instrumented total, last ").append(count).append(" ticks: avg ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", sum / 1_000_000.0 / count))
                    .append(" ms/tick, max ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", max / 1_000_000.0)).append(" ms");
        }
        return text.toString().trim();
    }

    static long runs(String routine) {
        long[] total = TOTALS.get(routine);
        return total == null ? 0 : total[0];
    }

    static void reset() { TOTALS.clear(); java.util.Arrays.fill(TICKS, 0); cursor = count = 0; thisTick = 0; }
}
