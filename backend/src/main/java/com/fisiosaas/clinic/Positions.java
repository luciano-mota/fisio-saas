package com.fisiosaas.clinic;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ordem dos itens do site (fotos da galeria, depoimentos, perguntas): "subir" e "descer" trocam com o vizinho
 * e renumeram tudo de 0 a n-1. Tabela e filtro são constantes do código, nunca vêm do cliente.
 */
final class Positions {
    private Positions() {}

    /** Próxima posição livre (fim da lista). */
    static int next(JdbcTemplate jdbc, String table, String scope, UUID clinicId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(position) + 1, 0) FROM " + table
            + " WHERE clinic_id = ? AND " + scope, Integer.class, clinicId);
    }

    /** Chamar dentro de transação. Item fora do filtro ou de outra clínica: 404. */
    static void move(JdbcTemplate jdbc, String table, String scope, UUID clinicId, UUID id, String direction) {
        // Trava a clínica para duas mudanças de ordem simultâneas não se embaralharem.
        jdbc.queryForObject("SELECT id FROM clinics WHERE id = ? FOR UPDATE", UUID.class, clinicId);
        List<UUID> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE clinic_id = ? AND " + scope
            + " ORDER BY position, created_at, id", UUID.class, clinicId);
        int index = ids.indexOf(id);
        if (index < 0) throw new NoSuchElementException();
        int target = switch (direction) {
            case "up" -> index - 1;
            case "down" -> index + 1;
            default -> throw new IllegalArgumentException("Direção inválida");
        };
        if (target < 0 || target >= ids.size()) return;
        ids.set(index, ids.get(target));
        ids.set(target, id);
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE " + table + " SET position = ? WHERE id = ? AND clinic_id = ?", i, ids.get(i), clinicId);
        }
    }
}
