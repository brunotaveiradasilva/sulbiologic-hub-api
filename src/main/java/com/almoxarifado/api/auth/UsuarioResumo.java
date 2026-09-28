package com.almoxarifado.api.auth;

import java.time.LocalDate;

/** Um login como a tela de Usuários mostra — nunca a senha/hash. representanteNome vazio se não é REPRESENTANTE. */
public record UsuarioResumo(
        String usuario,
        Role role,
        String representanteId,
        String representanteNome,
        String nome,
        String sobrenome,
        String email,
        LocalDate dataNascimento) {
}
