package com.fisiosaas.booking;

import com.fisiosaas.agenda.Occupancy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Horários livres para agendar pelo site (função pura, sem banco): parte do horário de atendimento, anda de
 * {@code step} em {@code step} minutos, tira o que está bloqueado ou já lotado (sessões e pedidos pendentes contam até
 * o limite de atendimentos simultâneos) e respeita a antecedência mínima e o limite de dias à frente. Tudo no horário
 * local da clínica.
 */
public final class BookingSlots {
    private BookingSlots() {}

    /** Intervalo de atendimento; weekday ISO (1 = segunda … 7 = domingo). */
    public record Hours(int weekday, LocalTime start, LocalTime end) {}

    /** Período ocupado (local). {@code block}: bloqueio de horário, fecha para todos. */
    public record Busy(LocalDateTime start, LocalDateTime end, boolean block) {}

    public static Map<LocalDate, List<LocalTime>> free(List<Hours> hours, List<Busy> busy, int durationMinutes,
                                                       int stepMinutes, LocalDate from, LocalDate to,
                                                       LocalDateTime earliest, LocalDate lastDay, int capacity) {
        List<Busy> blocks = busy.stream().filter(Busy::block).toList();
        List<Occupancy.Busy> taken = busy.stream().filter(b -> !b.block()).map(b -> new Occupancy.Busy(b.start(), b.end())).toList();
        Map<LocalDate, List<LocalTime>> result = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to) && !day.isAfter(lastDay); day = day.plusDays(1)) {
            TreeSet<LocalTime> times = new TreeSet<>();
            int weekday = day.getDayOfWeek().getValue();
            for (Hours h : hours) {
                if (h.weekday() != weekday) continue;
                LocalDateTime end = day.atTime(h.end());
                for (LocalDateTime start = day.atTime(h.start());
                     !start.plusMinutes(durationMinutes).isAfter(end);
                     start = start.plusMinutes(stepMinutes)) {
                    LocalDateTime slotEnd = start.plusMinutes(durationMinutes);
                    if (start.isBefore(earliest) || overlaps(blocks, start, slotEnd)
                        || !Occupancy.fits(taken, start, slotEnd, capacity)) continue;
                    times.add(start.toLocalTime());
                }
            }
            if (!times.isEmpty()) result.put(day, new ArrayList<>(times));
        }
        return result;
    }

    private static boolean overlaps(List<Busy> busy, LocalDateTime start, LocalDateTime end) {
        for (Busy b : busy) {
            if (b.start().isBefore(end) && b.end().isAfter(start)) return true;
        }
        return false;
    }
}
