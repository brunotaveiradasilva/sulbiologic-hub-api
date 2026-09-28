package com.almoxarifado.api.auth;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * O que o login da requisição atual pode enxergar. ADMIN e SUPERVISOR veem todos os representantes;
 * REPRESENTANTE só o dele. Quem só lê daqui são as listagens — a edição é barrada antes, no SecurityConfig.
 */
@Component
public class EscopoAcesso {

    /** Id que nunca existe: REPRESENTANTE sem vínculo não enxerga nada, em vez de enxergar tudo. */
    private static final String NENHUM = "-";

    private final UsuarioRepository usuarios;

    public EscopoAcesso(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Vazio = vê todos os representantes. Presente = só o representante com esse id. */
    public Optional<String> representanteRestrito() {
        // Sem autenticação só acontece em chamada interna (a requisição HTTP sem token já parou no
        // SecurityConfig), então não restringe.
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null) return Optional.empty();
        boolean representante = autenticacao.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + Role.REPRESENTANTE.name()));
        if (!representante) return Optional.empty();

        // Busca o vínculo no banco a cada requisição: se o admin trocar o representante do login,
        // vale na hora, sem esperar o token expirar.
        return Optional.of(usuarios.findByUsuarioIgnoreCase(autenticacao.getName())
                .map(Usuario::getRepresentanteId)
                .filter(id -> !id.isBlank())
                .orElse(NENHUM));
    }
}
