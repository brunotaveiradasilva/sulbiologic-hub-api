package com.almoxarifado.api.ads;

/** Erro falando com a API da ADS: login recusado, resposta de erro, ou falha de rede. */
public class AdsApiException extends RuntimeException {

    /** Falha que pode sumir tentando de novo (5xx, 429, rede), ao contrário de um 4xx ou resposta ilegível. */
    private final boolean passageira;

    public AdsApiException(String message) {
        this(message, false);
    }

    public AdsApiException(String message, boolean passageira) {
        super(message);
        this.passageira = passageira;
    }

    public AdsApiException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public AdsApiException(String message, Throwable cause, boolean passageira) {
        super(message, cause);
        this.passageira = passageira;
    }

    public boolean isPassageira() {
        return passageira;
    }
}
