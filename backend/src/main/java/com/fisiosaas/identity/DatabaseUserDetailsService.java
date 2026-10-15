package com.fisiosaas.identity;

import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Login de quem usa o painel de uma clínica (OWNER/STAFF) e da equipe da plataforma (PLATFORM_ADMIN). */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private final JdbcTemplate jdbc;

    public DatabaseUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        if (email == null || email.isBlank()) throw new UsernameNotFoundException("Credenciais inválidas");
        String key = email.trim().toLowerCase(Locale.ROOT);
        List<UserDetails> users = jdbc.query(
            "SELECT email, password_hash, active, role FROM users WHERE lower(email) = ?",
            (rs, row) -> User.withUsername(rs.getString("email"))
                .password(rs.getString("password_hash"))
                .disabled(!rs.getBoolean("active"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + rs.getString("role")))
                .build(),
            key);
        if (users.isEmpty()) {
            users = jdbc.query("SELECT email, password_hash FROM platform_admins WHERE lower(email) = ?",
                (rs, row) -> User.withUsername(rs.getString("email"))
                    .password(rs.getString("password_hash"))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + PLATFORM_ADMIN))
                    .build(),
                key);
        }
        if (users.size() != 1) throw new UsernameNotFoundException("Credenciais inválidas");
        return users.getFirst();
    }
}
