package com.fisiosaas.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Entrega o index.html do React para as rotas do frontend (navegação direta / F5). */
@Controller
public class SpaController {
    @GetMapping({"/login", "/cadastro", "/termos", "/privacidade", "/painel", "/painel/**", "/admin", "/admin/**", "/c/{slug}", "/c/{slug}/agendar"})
    public String spa() {
        return "forward:/index.html";
    }
}
