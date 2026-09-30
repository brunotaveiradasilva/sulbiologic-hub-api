package com.almoxarifado.api.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** A ADS às vezes responde 500 numa página qualquer: a página é pedida de novo em vez de derrubar a busca. */
class TentarDeNovoAdsTests {

    static final String PAGINA_VAZIA = """
            {"items": [], "page": 1, "pageSize": 100, "total": 0, "hasNext": false}
            """;

    MockRestServiceServer ads;
    List<Duration> esperas = new ArrayList<>();
    AdsHistoricoVendasClient client;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        ads = MockRestServiceServer.bindTo(builder).build();
        client = new AdsHistoricoVendasClient(builder, "https://ads.teste", "chave", "SulBiologic",
                "02668122000186", "074", esperas::add);
    }

    AdsHistoricoVendasPagina buscar() {
        return client.buscarPagina(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, 1, 100);
    }

    @Test
    void erro500PassageiroTentaDeNovoEDevolveAPagina() {
        ads.expect(times(2), requestTo(Matchers.containsString("/historico-de-vendas"))).andRespond(withServerError());
        ads.expect(requestTo(Matchers.containsString("/historico-de-vendas")))
                .andRespond(withSuccess(PAGINA_VAZIA, MediaType.APPLICATION_JSON));

        assertThat(buscar().items()).isEmpty();
        assertThat(esperas).hasSize(2);
        ads.verify();
    }

    @Test
    void erro500QueNaoPassaDesisteDepoisDasTentativas() {
        ads.expect(times(4), requestTo(Matchers.containsString("/historico-de-vendas"))).andRespond(withServerError());

        assertThatThrownBy(this::buscar).isInstanceOf(AdsApiException.class).hasMessageContaining("500");
        assertThat(esperas).hasSize(3);
        ads.verify();
    }

    @Test
    void erro400NaoTentaDeNovo() {
        ads.expect(requestTo(Matchers.containsString("/historico-de-vendas"))).andRespond(withBadRequest());

        assertThatThrownBy(this::buscar).isInstanceOf(AdsApiException.class).hasMessageContaining("400");
        assertThat(esperas).isEmpty();
        ads.verify();
    }
}
