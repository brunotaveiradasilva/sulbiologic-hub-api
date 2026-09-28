package com.almoxarifado.api.auth;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

/**
 * role vazio = USUARIO (o que o front antigo mandava). representanteId só vale (e é obrigatório) pra
 * REPRESENTANTE. Nome, sobrenome, e-mail e nascimento são opcionais pra API, pra não quebrar quem só
 * manda usuário e senha.
 */
public record CriarUsuarioRequest(
        @NotBlank(message = "Informe o usuário") String usuario,
        @NotBlank(message = "Informe a senha")
        @Size(min = 4, message = "A senha precisa ter pelo menos 4 caracteres") String senha,
        Role role,
        String representanteId,
        @Size(max = 80, message = "Nome grande demais") String nome,
        @Size(max = 80, message = "Sobrenome grande demais") String sobrenome,
        @Email(message = "E-mail inválido") @Size(max = 120, message = "E-mail grande demais") String email,
        @Past(message = "A data de nascimento precisa ser no passado") LocalDate dataNascimento
) {
}
