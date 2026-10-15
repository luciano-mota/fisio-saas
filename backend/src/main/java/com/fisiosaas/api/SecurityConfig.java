package com.fisiosaas.api;

import com.fisiosaas.identity.DatabaseUserDetailsService;
import com.fisiosaas.platform.ClinicAccess;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Sessão HTTP-only + CSRF por cookie, no mesmo domínio do frontend.
 * Rotas /api/v1/public/** são a vitrine pública (somente leitura nesta versão).
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    LoginThrottle loginThrottle() {
        return new LoginThrottle();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginThrottle throttle, JdbcTemplate jdbc, ClinicAccess access) throws Exception {
        return http
            .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/public/**",
                    "/actuator/health").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole(DatabaseUserDetailsService.PLATFORM_ADMIN)
                .requestMatchers("/api/**").authenticated()
                // Todo o resto é o frontend estático (SPA): login, painel e páginas públicas.
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email").passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    throttle.succeeded(request);
                    // Clínica bloqueada pela plataforma: senha certa, mas não entra (os dados continuam guardados).
                    UUID clinicId = jdbc.query("SELECT clinic_id FROM users WHERE lower(email) = ?",
                            (rs, n) -> rs.getObject(1, UUID.class), authentication.getName().toLowerCase(Locale.ROOT))
                        .stream().findFirst().orElse(null);
                    if (clinicId != null && access.of(clinicId).blocked()) {
                        request.getSession().invalidate();
                        SecurityContextHolder.clearContext();
                        response.setStatus(403);
                        response.setCharacterEncoding("UTF-8");
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Acesso bloqueado. Fale com o suporte do PhysioEvo.\"}");
                        return;
                    }
                    response.setStatus(200);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"authenticated\":true}");
                })
                .failureHandler((request, response, error) -> {
                    throttle.failed(request);
                    response.sendError(401);
                }))
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .invalidateHttpSession(true)
                .deleteCookies("SESSION", "JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }
}
