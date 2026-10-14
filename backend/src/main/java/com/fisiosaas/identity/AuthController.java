package com.fisiosaas.identity;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final CurrentClinic current;
    private final JdbcTemplate jdbc;

    public AuthController(CurrentClinic current, JdbcTemplate jdbc) {
        this.current = current;
        this.jdbc = jdbc;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        var user = current.require(auth);
        String clinicName = jdbc.queryForObject(
            "SELECT display_name FROM clinics WHERE id = ?", String.class, user.clinicId());
        return Map.of(
            "userId", user.userId(),
            "email", user.email(),
            "role", user.role(),
            "clinicId", user.clinicId(),
            "clinicName", clinicName);
    }
}
