package com.almoxarifado.api.ads;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A mesma sincronização diária dos @Scheduled, mas chamada de fora — pelo Cloud Scheduler, já que
 * no Cloud Run a instância pode estar desligada às 6h. Não usa o login (JWT): autentica pelo header
 * X-Cron-Token, que tem que bater com CRON_TOKEN. Sem CRON_TOKEN configurado a rota fica desligada
 * (404). Roda tudo antes de responder: no Cloud Run só há CPU garantida enquanto a requisição está
 * aberta.
 */
@RestController
public class SincronizacaoDiariaController {

    private final AdsSincronizacaoService metas;
    private final AdsEspecialistaPetService especialistaPet;
    private final AdsCampanhaWellpetService campanhaWellpet;
    private final byte[] token;

    public SincronizacaoDiariaController(
            AdsSincronizacaoService metas,
            AdsEspecialistaPetService especialistaPet,
            AdsCampanhaWellpetService campanhaWellpet,
            @Value("${app.cron-token:}") String token) {
        this.metas = metas;
        this.especialistaPet = especialistaPet;
        this.campanhaWellpet = campanhaWellpet;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/interno/sincronizacao-diaria")
    public ResponseEntity<Void> sincronizar(@RequestHeader(name = "X-Cron-Token", required = false) String recebido) {
        if (token.length == 0) return ResponseEntity.notFound().build();
        if (recebido == null || !MessageDigest.isEqual(token, recebido.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        metas.sincronizarAgendado();
        especialistaPet.sincronizarAgendado();
        campanhaWellpet.sincronizarAgendado();
        return ResponseEntity.noContent().build();
    }
}
