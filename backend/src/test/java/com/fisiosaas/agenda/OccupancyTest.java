package com.fisiosaas.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisiosaas.agenda.Occupancy.Busy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OccupancyTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    private static LocalDateTime at(int hour, int minute) {
        return DAY.atTime(hour, minute);
    }

    @Test
    void contaSoQuemEstaJuntoNoMesmoInstante() {
        // 08:00–08:30 e 08:30–09:00 encostam, mas não acontecem ao mesmo tempo.
        List<Busy> items = List.of(new Busy(at(8, 0), at(8, 30)), new Busy(at(8, 30), at(9, 0)));
        assertThat(Occupancy.peak(items, at(8, 0), at(9, 0))).isEqualTo(1);
        assertThat(Occupancy.fits(items, at(8, 0), at(9, 0), 2)).isTrue();
        assertThat(Occupancy.fits(items, at(8, 0), at(9, 0), 1)).isFalse();
    }

    @Test
    void sobreposicaoParcialSomaNoTrechoEmComum() {
        List<Busy> items = List.of(new Busy(at(8, 0), at(9, 0)), new Busy(at(8, 30), at(9, 30)), new Busy(at(10, 0), at(11, 0)));
        assertThat(Occupancy.peak(items, at(8, 0), at(10, 0))).isEqualTo(2);
        assertThat(Occupancy.peak(items, at(9, 0), at(10, 0))).isEqualTo(1);
        // Fora do horário pedido não conta.
        assertThat(Occupancy.peak(items, at(11, 0), at(12, 0))).isZero();
        assertThat(Occupancy.peak(List.of(), at(8, 0), at(9, 0))).isZero();
    }
}
