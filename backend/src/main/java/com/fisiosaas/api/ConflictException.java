package com.fisiosaas.api;

import java.util.List;

/**
 * Operação válida, mas que conflita com o estado atual (ex.: horário já ocupado). Responde 409 com a mensagem
 * e, quando houver, a lista de conflitos (ex.: dias de uma série recorrente que já estão ocupados).
 */
public class ConflictException extends RuntimeException {
    private final List<String> conflicts;

    public ConflictException(String message) {
        this(message, List.of());
    }

    public ConflictException(String message, List<String> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<String> conflicts() {
        return conflicts;
    }
}
