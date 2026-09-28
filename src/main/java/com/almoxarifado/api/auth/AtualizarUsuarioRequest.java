package com.almoxarifado.api.auth;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

/**
 * Edição de um login pelo admin. O nome de usuário não muda (é a chave do login). novaSenha em branco
 * mantém a senha atual; preenchida, redefine — é como o admin destrava quem esqueceu a senha.
 */
public record AtualizarUsuarioRequest(
        Role role,
        String representanteId,
        @Size(max = 80, message = "Nome grande demais") String nome,
        @Size(max = 80, message = "Sobrenome grande demais") String sobrenome,
        @Email(message = "E-mail inválido") @Size(max = 120, message = "E-mail grande demais") String email,
        @Past(message = "A data de nascimento precisa ser no passado") LocalDate dataNascimento,
        String novaSenha
) {
}
