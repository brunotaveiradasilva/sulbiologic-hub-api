package com.almoxarifado.api.auth;

/** Pedido de criação de login que não fecha — ex.: perfil REPRESENTANTE sem dizer qual representante. */
public class UsuarioInvalidoException extends RuntimeException {

    public UsuarioInvalidoException(String mensagem) {
        super(mensagem);
    }
}
