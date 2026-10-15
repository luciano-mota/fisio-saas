package com.fisiosaas.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisiosaas.booking.BookingSlots.Busy;
import com.fisiosaas.booking.BookingSlots.Hours;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BookingSlotsTest {
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    static final LocalTime T8 = LocalTime.of(8, 0);

    private static List<LocalTime> times(String... values) {
        return java.util.Arrays.stream(values).map(LocalTime::parse).toList();
    }

    @Test
    void geraHorariosDoAtendimentoSemPassarDoFim() {
        Map<LocalDate, List<LocalTime>> free = BookingSlots.free(
            List.of(new Hours(1, T8, LocalTime.of(10, 0))), List.of(), 50, 30, MONDAY, MONDAY,
            MONDAY.minusDays(1).atStartOfDay(), MONDAY.plusDays(30));
        // 09:30 + 50 min passaria das 10:00.
        assertThat(free.get(MONDAY)).isEqualTo(times("08:00", "08:30", "09:00"));
    }

    @Test
    void tiraOQueEstaOcupadoEOsHorariosQueEncostam() {
        Map<LocalDate, List<LocalTime>> free = BookingSlots.free(
            List.of(new Hours(1, T8, LocalTime.of(12, 0))),
            List.of(new Busy(MONDAY.atTime(9, 0), MONDAY.atTime(9, 50))), 50, 30, MONDAY, MONDAY,
            MONDAY.minusDays(1).atStartOfDay(), MONDAY.plusDays(30));
        assertThat(free.get(MONDAY)).isEqualTo(times("08:00", "10:00", "10:30", "11:00"));
    }

    @Test
    void respeitaAntecedenciaLimiteDeDiasEDiaDaSemana() {
        List<Hours> hours = List.of(new Hours(1, T8, LocalTime.of(12, 0)), new Hours(1, LocalTime.of(14, 0), LocalTime.of(16, 0)),
            new Hours(3, T8, LocalTime.of(9, 0)));
        LocalDateTime earliest = MONDAY.atTime(10, 10);
        Map<LocalDate, List<LocalTime>> free = BookingSlots.free(hours, List.of(), 60, 60, MONDAY, MONDAY.plusDays(6),
            earliest, MONDAY.plusDays(1));
        assertThat(free.get(MONDAY)).isEqualTo(times("11:00", "14:00", "15:00"));
        // Quarta tem atendimento, mas passa do limite de dias à frente; terça não tem atendimento.
        assertThat(free).containsOnlyKeys(MONDAY);
    }

    @Test
    void semAtendimentoNaoHaHorarios() {
        assertThat(BookingSlots.free(List.of(), List.of(), 50, 30, MONDAY, MONDAY.plusDays(7),
            MONDAY.atStartOfDay(), MONDAY.plusDays(30))).isEmpty();
    }
}
