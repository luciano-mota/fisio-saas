package com.fisiosaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoginThrottleTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static MockHttpServletRequest login(String email) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.setParameter("email", email);
        return request;
    }

    @Test
    void bloqueiaAposCincoFalhasELiberaDepoisDoTempo() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) throttle.failed(login("ana@x.com"));
        assertThat(throttle.isLocked(login("ana@x.com"))).isFalse();

        throttle.failed(login("ANA@x.com"));
        assertThat(throttle.isLocked(login("ana@x.com"))).isTrue();
        assertThat(throttle.isLocked(login("outra@x.com"))).isFalse();

        clock.now = clock.now.plus(LoginThrottle.LOCK_TIME).plusSeconds(1);
        assertThat(throttle.isLocked(login("ana@x.com"))).isFalse();
    }

    @Test
    void sucessoZeraAsFalhas() {
        var throttle = new LoginThrottle(new MutableClock());
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) throttle.failed(login("ana@x.com"));
        throttle.succeeded(login("ana@x.com"));
        throttle.failed(login("ana@x.com"));
        assertThat(throttle.isLocked(login("ana@x.com"))).isFalse();
    }
}
