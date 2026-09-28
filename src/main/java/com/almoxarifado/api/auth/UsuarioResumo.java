package com.almoxarifado.api.auth;

/** Um login como a tela de Usuários mostra — nunca a senha/hash. representanteNome vazio se não é REPRESENTANTE. */
public record UsuarioResumo(String usuario, Role role, String representanteId, String representanteNome) {
}
