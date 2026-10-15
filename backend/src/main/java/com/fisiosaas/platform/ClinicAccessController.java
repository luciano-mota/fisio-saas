package com.fisiosaas.platform;

import com.fisiosaas.identity.CurrentClinic;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Situação do acesso da própria clínica, para o aviso de vencimento dentro do painel. */
@RestController
public class ClinicAccessController {

    /**
     * warning: NONE, SOON (vence em até 7 dias), EXPIRED (prazo acabou: só consulta) ou SUSPENDED (só consulta).
     */
    public record AccessView(String status, String effectiveStatus, LocalDate accessUntil, Integer daysLeft,
                             boolean readOnly, String warning, String contactEmail) {}

    private final CurrentClinic current;
    private final ClinicAccess access;
    private final String contactEmail;

    public ClinicAccessController(CurrentClinic current, ClinicAccess access,
                                  @Value("${fisio.contact-email:}") String contactEmail) {
        this.current = current;
        this.access = access;
        this.contactEmail = contactEmail == null || contactEmail.isBlank() ? null : contactEmail.trim();
    }

    @GetMapping("/api/v1/clinic/access")
    public AccessView get(Authentication auth) {
        ClinicAccess.Access state = access.of(current.require(auth).clinicId());
        String warning = "NONE";
        if ("SUSPENDED".equals(state.effective())) warning = "SUSPENDED";
        else if ("EXPIRED".equals(state.effective())) warning = "EXPIRED";
        else if (state.daysLeft() != null && state.daysLeft() <= ClinicAccess.WARN_DAYS) warning = "SOON";
        return new AccessView(state.status(), state.effective(), state.accessUntil(), state.daysLeft(), state.readOnly(),
            warning, contactEmail);
    }
}
