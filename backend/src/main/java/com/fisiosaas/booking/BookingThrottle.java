package com.fisiosaas.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Limite simples em memória contra abuso do agendamento pelo site: no máximo {@code MAX} pedidos por IP por hora. */
@Component
public class BookingThrottle {
    static final int MAX = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public BookingThrottle() {
        this(Clock.systemUTC());
    }

    BookingThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String key) {
        Instant now = clock.instant();
        if (hits.size() > 10_000) hits.clear(); // limite defensivo de memória
        Deque<Instant> list = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (list) {
            while (!list.isEmpty() && list.peekFirst().isBefore(now.minus(WINDOW))) list.pollFirst();
            if (list.size() >= MAX) return false;
            list.addLast(now);
            return true;
        }
    }

    /** Usado pelos testes para começar do zero. */
    public void reset() {
        hits.clear();
    }
}
