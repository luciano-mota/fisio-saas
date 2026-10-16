package com.fisiosaas.agenda;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Quantos atendimentos acontecem ao mesmo tempo dentro de um horário (função pura). Conta só quem está junto no
 * mesmo instante: 08:00–08:30 e 08:30–09:00 não somam. Intervalos meio abertos [início, fim).
 */
public final class Occupancy {
    private Occupancy() {}

    public record Busy(LocalDateTime start, LocalDateTime end) {}

    /** Maior número de itens simultâneos em algum instante de [start, end). */
    public static int peak(List<Busy> items, LocalDateTime start, LocalDateTime end) {
        int peak = 0;
        // O máximo acontece no início do horário ou no início de algum item dentro dele.
        for (Busy point : items) {
            LocalDateTime at = point.start().isAfter(start) ? point.start() : start;
            if (!at.isBefore(end) || !point.end().isAfter(at)) continue;
            int count = 0;
            for (Busy b : items) {
                if (!b.start().isAfter(at) && b.end().isAfter(at)) count++;
            }
            peak = Math.max(peak, count);
        }
        return peak;
    }

    /** Cabe mais um atendimento em [start, end) sem passar de {@code capacity}? */
    public static boolean fits(List<Busy> items, LocalDateTime start, LocalDateTime end, int capacity) {
        return peak(items, start, end) < capacity;
    }
}
