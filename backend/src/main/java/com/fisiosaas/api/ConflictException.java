package com.fisiosaas.api;

/** Operação válida, mas que conflita com o estado atual (ex.: horário já ocupado). Responde 409 com a mensagem. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
