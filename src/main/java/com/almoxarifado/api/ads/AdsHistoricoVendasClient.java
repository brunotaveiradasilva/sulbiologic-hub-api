package com.almoxarifado.api.ads;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Consulta o histórico de vendas da ADS (`GET /api/v1/{cnpjDistribuidora}/historico-de-vendas`).
 *
 * Autentica só com os headers `x-api-key` e `User-Agent` — confirmado testando direto contra a
 * API de produção (`adsapi.com.br`; o ambiente de homologação usado pela doc não aceita essa
 * autenticação, e a doc/spec não documenta esses headers). `dtinicio`/`dtfinal` são obrigatórios
 * na prática, mesmo a doc marcando como opcionais (sem eles a API responde 400). `especificoid` é
 * um valor fixo da conta (não um filtro — outros valores dão 400), por isso vem só da config,
 * nunca de quem chama.
 */
@Service
public class AdsHistoricoVendasClient {

    private static final Logger log = LoggerFactory.getLogger(AdsHistoricoVendasClient.class);

    private static final int MAX_PAGINAS = 1000;
    /**
     * O maior tamanho de página que a ADS aceita (acima disso responde 400). Cada página leva uns 10s
     * pra voltar, quase sem depender do tamanho — páginas grandes deixam a busca bem mais rápida.
     */
    private static final int TAMANHO_PAGINA = 500;
    /**
     * Espera antes de cada nova tentativa de uma página que falhou. A ADS às vezes responde 500 numa
     * página qualquer no meio de uma busca longa (uma página diferente a cada vez) — sem tentar de
     * novo, uma página ruim derrubava a busca do mês inteiro.
     */
    private static final List<Duration> ESPERAS_ENTRE_TENTATIVAS = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(8));

    private final RestClient restClient;
    private final String cnpjDistribuidora;
    private final String especificoId;
    private final Consumer<Duration> esperar;

    @Autowired
    public AdsHistoricoVendasClient(
            @Value("${app.ads.base-url}") String baseUrl,
            @Value("${app.ads.api-key}") String apiKey,
            @Value("${app.ads.user-agent}") String userAgent,
            @Value("${app.ads.cnpj-distribuidora}") String cnpjDistribuidora,
            @Value("${app.ads.especifico-id}") String especificoId) {
        this(RestClient.builder(), baseUrl, apiKey, userAgent, cnpjDistribuidora, especificoId,
                AdsHistoricoVendasClient::dormir);
    }

    /** Pros testes: um builder ligado a um servidor falso e uma espera que não dorme de verdade. */
    AdsHistoricoVendasClient(RestClient.Builder builder, String baseUrl, String apiKey, String userAgent,
            String cnpjDistribuidora, String especificoId, Consumer<Duration> esperar) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.cnpjDistribuidora = cnpjDistribuidora;
        this.especificoId = especificoId;
        this.esperar = esperar;
    }

    /**
     * Busca todas as páginas do período informado e devolve a lista completa de vendas.
     * `reprId` filtra por representante (opcional — vazio busca de todos).
     */
    public List<AdsVenda> buscarTudo(LocalDate dtInicio, LocalDate dtFinal, String reprId) {
        return buscarTudo(dtInicio, dtFinal, reprId, (lidas, total) -> { });
    }

    /**
     * Igual ao de cima, avisando {@code aoLerPagina} depois de cada página com quantas vendas já vieram e
     * quantas a ADS diz que o período tem no total — pra mostrar o progresso de buscas longas.
     */
    public List<AdsVenda> buscarTudo(LocalDate dtInicio, LocalDate dtFinal, String reprId, BiConsumer<Integer, Integer> aoLerPagina) {
        List<AdsVenda> todas = new ArrayList<>();
        int pagina = 1;
        AdsHistoricoVendasPagina resultado;
        do {
            resultado = buscarPagina(dtInicio, dtFinal, reprId, pagina, TAMANHO_PAGINA);
            todas.addAll(resultado.items());
            aoLerPagina.accept(todas.size(), resultado.total());
            pagina++;
        } while (resultado.hasNext() && pagina <= MAX_PAGINAS);
        return todas;
    }

    /**
     * Busca uma página, tentando de novo (com espera crescente) quando a falha parece passageira: erro
     * 5xx, 429 ou falha de rede. Erro 4xx (chave recusada, parâmetro inválido) ou resposta que não dá
     * pra ler falham na hora — tentar de novo não mudaria nada.
     */
    public AdsHistoricoVendasPagina buscarPagina(LocalDate dtInicio, LocalDate dtFinal, String reprId, int page, int pageSize) {
        for (int tentativa = 0; ; tentativa++) {
            try {
                return buscarPaginaUmaVez(dtInicio, dtFinal, reprId, page, pageSize);
            } catch (AdsApiException e) {
                if (!e.isPassageira() || tentativa >= ESPERAS_ENTRE_TENTATIVAS.size()) throw e;
                Duration espera = ESPERAS_ENTRE_TENTATIVAS.get(tentativa);
                log.info("ADS falhou na página {} ({}); tentando de novo em {}s", page, e.getMessage(), espera.toSeconds());
                esperar.accept(espera);
            }
        }
    }

    private AdsHistoricoVendasPagina buscarPaginaUmaVez(LocalDate dtInicio, LocalDate dtFinal, String reprId, int page, int pageSize) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/v1/{cnpjDistribuidora}/historico-de-vendas")
                                .queryParam("especificoid", especificoId)
                                .queryParam("dtinicio", dtInicio)
                                .queryParam("dtfinal", dtFinal)
                                .queryParam("page", page)
                                .queryParam("pageSize", pageSize);
                        if (reprId != null && !reprId.isBlank()) uriBuilder.queryParam("repr_id", reprId);
                        return uriBuilder.build(cnpjDistribuidora);
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        HttpStatusCode status = resp.getStatusCode();
                        throw new AdsApiException("API da ADS respondeu " + status + " para " + req.getURI(),
                                status.is5xxServerError() || status.value() == 429);
                    })
                    .body(AdsHistoricoVendasPagina.class);
        } catch (AdsApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new AdsApiException("Não foi possível falar com a API da ADS: " + e.getMessage(), e, true);
        } catch (RestClientException e) {
            throw new AdsApiException("Não foi possível falar com a API da ADS: " + e.getMessage(), e, false);
        }
    }

    private static void dormir(Duration espera) {
        try {
            Thread.sleep(espera);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdsApiException("Busca na ADS interrompida", e, false);
        }
    }
}
