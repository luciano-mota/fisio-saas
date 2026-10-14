package com.fisiosaas.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
    SecurityFilterChain filterChain(HttpSecurity http, LoginThrottle throttle) throws Exception {
        return http
            .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/public/**",
                    "/actuator/health").permitAll()
                .requestMatchers("/api/**").authenticated()
                // Todo o resto é o frontend estático (SPA): login, painel e páginas públicas.
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email").passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    throttle.succeeded(request);
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
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }
}
