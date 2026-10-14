package com.fisiosaas.agenda;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Datas de uma série recorrente: dias da semana a partir de uma data, por N semanas e/ou até N ocorrências. */
public final class Recurrence {
    public static final int MAX_OCCURRENCES = 100;

    private Recurrence() {}

    /**
     * "Por N semanas" conta a partir da data inicial (ex.: começando numa quarta, 1 semana vai até a terça seguinte).
     * Com semanas e quantidade, para no que vier primeiro.
     */
    public static List<LocalDate> dates(LocalDate start, Set<DayOfWeek> weekdays, Integer weeks, Integer occurrences) {
        if (weekdays == null || weekdays.isEmpty()) throw new IllegalArgumentException("Escolha pelo menos um dia da semana");
        if (weeks == null && occurrences == null) throw new IllegalArgumentException("Informe por quantas semanas ou quantas vezes");
        LocalDate end = weeks == null ? start.plusWeeks(MAX_OCCURRENCES) : start.plusWeeks(weeks);
        int limit = occurrences == null ? Integer.MAX_VALUE : occurrences;

        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate day = start; day.isBefore(end) && dates.size() < limit; day = day.plusDays(1)) {
            if (weekdays.contains(day.getDayOfWeek())) {
                if (dates.size() == MAX_OCCURRENCES) {
                    throw new IllegalArgumentException("Máximo de " + MAX_OCCURRENCES + " ocorrências por vez");
                }
                dates.add(day);
            }
        }
        if (dates.isEmpty()) throw new IllegalArgumentException("Nenhuma data nesse período");
        return dates;
    }
}
