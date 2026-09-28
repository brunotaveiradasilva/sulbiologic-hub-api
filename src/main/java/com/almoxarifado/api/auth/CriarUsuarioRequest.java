package com.almoxarifado.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** role vazio = USUARIO (o que o front antigo mandava). representanteId só vale (e é obrigatório) pra REPRESENTANTE. */
public record CriarUsuarioRequest(
        @NotBlank(message = "Informe o usuário") String usuario,
        @NotBlank(message = "Informe a senha")
        @Size(min = 4, message = "A senha precisa ter pelo menos 4 caracteres") String senha,
        Role role,
        String representanteId
) {
}
