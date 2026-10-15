package com.fisiosaas.pix;

import com.fisiosaas.identity.CurrentClinic;
import com.fisiosaas.pix.PixCode.KeyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

/** Chave Pix da clínica (para o pagamento no agendamento e a cobrança de quem está devendo). */
@RestController
@RequestMapping("/api/v1")
public class PixController {

    /** {@code keyType} nulo = sem Pix. Nome e cidade do recebedor vão no código (sem acento, até 25 e 15 letras). */
    public record PixSettings(KeyType keyType, @Size(max = 77) String key, @Size(max = 60) String name, @Size(max = 40) String city) {}

    public record ChargeRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount,
                                @Size(max = 25) String reference) {}

    public record Charge(String code, String qrSvg) {}

    private final JdbcTemplate jdbc;
    private final CurrentClinic current;
    private final Pix pix;

    public PixController(JdbcTemplate jdbc, CurrentClinic current, Pix pix) {
        this.jdbc = jdbc;
        this.current = current;
        this.pix = pix;
    }

    @GetMapping("/clinic/pix")
    public PixSettings get(Authentication auth) {
        return pix.settings(current.require(auth).clinicId());
    }

    @PutMapping("/clinic/pix")
    public PixSettings put(Authentication auth, @Valid @RequestBody PixSettings body) {
        UUID clinicId = current.requireOwner(auth).clinicId();
        if (body.keyType() == null) {
            jdbc.update("UPDATE clinics SET pix_key_type = NULL, pix_key = NULL, pix_name = NULL, pix_city = NULL WHERE id = ?", clinicId);
            return pix.settings(clinicId);
        }
        String key = PixCode.normalizeKey(body.keyType(), body.key());
        if (body.name() == null || body.name().isBlank()) throw new IllegalArgumentException("Informe o nome de quem recebe (como no banco)");
        if (body.city() == null || body.city().isBlank()) throw new IllegalArgumentException("Informe a cidade de quem recebe");
        jdbc.update("UPDATE clinics SET pix_key_type = ?, pix_key = ?, pix_name = ?, pix_city = ? WHERE id = ?",
            body.keyType().name(), key, PixCode.ascii(body.name(), 25, ""), PixCode.ascii(body.city(), 15, ""), clinicId);
        return pix.settings(clinicId);
    }

    /** Código Pix com valor (ex.: cobrar quem está devendo pelo WhatsApp). */
    @PostMapping("/finance/pix")
    public Charge charge(Authentication auth, @Valid @RequestBody ChargeRequest body) {
        UUID clinicId = current.require(auth).clinicId();
        String code = pix.code(clinicId, body.amount(), body.reference());
        return new Charge(code, PixCode.qrSvg(code));
    }

    /** Leitura da chave e geração do código, usados também pelo agendamento público. */
    @Component
    public static class Pix {
        private final JdbcTemplate jdbc;

        public Pix(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public PixSettings settings(UUID clinicId) {
            return jdbc.queryForObject("SELECT pix_key_type, pix_key, pix_name, pix_city FROM clinics WHERE id = ?",
                (rs, n) -> new PixSettings(rs.getString(1) == null ? null : KeyType.valueOf(rs.getString(1)), rs.getString(2),
                    rs.getString(3), rs.getString(4)), clinicId);
        }

        public boolean enabled(UUID clinicId) {
            return settings(clinicId).keyType() != null;
        }

        public String code(UUID clinicId, BigDecimal amount, String reference) {
            PixSettings s = settings(clinicId);
            if (s.keyType() == null) throw new IllegalArgumentException("Cadastre a chave Pix em Clínica → Pix");
            return PixCode.build(s.key(), s.name(), s.city(), amount, reference);
        }
    }
}
