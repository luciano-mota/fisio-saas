package com.fisiosaas.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aplica a situação da assinatura no painel: bloqueada não acessa nada; suspensa ou vencida só consulta
 * (trocar a própria senha, marcar aviso como lido e sair continuam liberados). Os dados ficam guardados.
 */
@Component
public class ClinicAccessInterceptor implements HandlerInterceptor {
    private static final Set<String> READ = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> ALWAYS_ALLOWED = Set.of("/api/v1/account/password");
    /** Marcar aviso da plataforma como lido também vale com o painel só para consulta. */
    private static final java.util.regex.Pattern NOTICE_READ = java.util.regex.Pattern.compile("^/api/v1/notices/[^/]+/read$");

    private final JdbcTemplate jdbc;
    private final ClinicAccess access;
    private final ObjectMapper json;

    public ClinicAccessInterceptor(JdbcTemplate jdbc, ClinicAccess access, ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        UUID clinicId = jdbc.query("SELECT clinic_id FROM users WHERE lower(email) = ? AND active",
                (rs, n) -> rs.getObject(1, UUID.class), auth.getName().trim().toLowerCase(Locale.ROOT))
            .stream().findFirst().orElse(null);
        if (clinicId == null) return true; // administrador da plataforma ou usuário inativo: os controllers decidem

        ClinicAccess.Access state = access.of(clinicId);
        if (state.blocked()) {
            return deny(response, "Acesso bloqueado. Fale com o suporte do PhysioEvo.");
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (state.readOnly() && !READ.contains(request.getMethod()) && !ALWAYS_ALLOWED.contains(path)
            && !NOTICE_READ.matcher(path).matches()) {
            return deny(response, "EXPIRED".equals(state.effective())
                ? "O período de acesso terminou: o painel está só para consulta. Seus dados continuam guardados. Fale com o suporte para renovar."
                : "Acesso suspenso: o painel está só para consulta. Seus dados continuam guardados. Fale com o suporte do PhysioEvo.");
        }
        return true;
    }

    private boolean deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(403);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getWriter(), Map.of("error", message));
        return false;
    }
}
