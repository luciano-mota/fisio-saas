package com.fisiosaas.agenda;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecurrenceTest {
    static final Set<java.time.DayOfWeek> SEG_QUA_SEX = EnumSet.of(MONDAY, WEDNESDAY, FRIDAY);

    @Test
    void segQuaSexPorCincoSemanasDaQuinzeSessoes() {
        var dates = Recurrence.dates(LocalDate.parse("2030-01-07"), SEG_QUA_SEX, 5, null);
        assertThat(dates).hasSize(15);
        assertThat(dates.getFirst()).isEqualTo("2030-01-07");
        assertThat(dates.get(1)).isEqualTo("2030-01-09");
        assertThat(dates.getLast()).isEqualTo("2030-02-08");
    }

    @Test
    void semanasContamAPartirDaDataInicialMesmoNoMeioDaSemana() {
        // Começa numa quarta: 1 semana = qua e sex desta semana + seg da próxima.
        var dates = Recurrence.dates(LocalDate.parse("2030-01-09"), SEG_QUA_SEX, 1, null);
        assertThat(dates).containsExactly(LocalDate.parse("2030-01-09"), LocalDate.parse("2030-01-11"),
            LocalDate.parse("2030-01-14"));
    }

    @Test
    void quantidadeDeSessoesParaNoNumeroPedido() {
        var dates = Recurrence.dates(LocalDate.parse("2030-01-07"), SEG_QUA_SEX, null, 10);
        assertThat(dates).hasSize(10);
        assertThat(dates.getLast()).isEqualTo("2030-01-28");
        // Com semanas e quantidade, vale o que acabar primeiro.
        assertThat(Recurrence.dates(LocalDate.parse("2030-01-07"), SEG_QUA_SEX, 2, 10)).hasSize(6);
    }

    @Test
    void recusaRegraVaziaOuGrandeDemais() {
        assertThatThrownBy(() -> Recurrence.dates(LocalDate.parse("2030-01-07"), Set.of(), 5, null))
            .hasMessage("Escolha pelo menos um dia da semana");
        assertThatThrownBy(() -> Recurrence.dates(LocalDate.parse("2030-01-07"), SEG_QUA_SEX, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Recurrence.dates(LocalDate.parse("2030-01-07"), EnumSet.allOf(java.time.DayOfWeek.class), 52, null))
            .hasMessage("Máximo de 100 ocorrências por vez");
        assertThat(Recurrence.dates(LocalDate.parse("2030-01-07"), EnumSet.of(MONDAY), null, 100)).hasSize(100);
    }
}
