package com.fisiosaas.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloqueio simples em memória contra tentativa de senha por força bruta:
 * após MAX_FAILURES falhas para o mesmo IP + e-mail, bloqueia por LOCK_TIME.
 */
public class LoginThrottle {
    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TIME = Duration.ofMinutes(15);

    private record Attempts(int failures, Instant lockedUntil) {}

    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(HttpServletRequest request) {
        Attempts current = attempts.get(key(request));
        return current != null && current.lockedUntil() != null
            && current.lockedUntil().isAfter(clock.instant());
    }

    public void failed(HttpServletRequest request) {
        attempts.compute(key(request), (k, current) -> {
            int failures = (current == null ? 0 : current.failures()) + 1;
            Instant lock = failures >= MAX_FAILURES ? clock.instant().plus(LOCK_TIME) : null;
            return new Attempts(lock == null ? failures : 0, lock);
        });
        if (attempts.size() > 10_000) {
            attempts.clear(); // limite defensivo de memória
        }
    }

    public void succeeded(HttpServletRequest request) {
        attempts.remove(key(request));
    }

    private static String key(HttpServletRequest request) {
        String email = request.getParameter("email");
        return request.getRemoteAddr() + "|" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }
}
