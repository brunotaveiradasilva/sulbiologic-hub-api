package com.almoxarifado.api.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.almoxarifado.api.campanhawellpet.ClienteCampanhaWellpet;
import com.almoxarifado.api.campanhawellpet.ClienteCampanhaWellpetRepository;
import com.almoxarifado.api.common.RecursoEmUsoException;
import com.almoxarifado.api.metarepresentante.Mes;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Campanha Wellpet: quem da carteira nunca comprou Wellpet, e quem desses comprou no mês da campanha. */
class CampanhaWellpetTests {

    static final YearMonth OUTUBRO = YearMonth.of(2026, 10);

    ClienteCampanhaWellpetRepository clientes = mock(ClienteCampanhaWellpetRepository.class);
    AdsHistoricoVendasClient client = mock(AdsHistoricoVendasClient.class);
    AdsCampanhaWellpetService servico = new AdsCampanhaWellpetService(clientes, client);

    @Test
    void reconheceQualquerApresentacaoDoWellpet() {
        assertThat(AdsCampanhaWellpetService.ehWellpet(produto("WELLPET 400MG (20,1 A 40KG)"))).isTrue();
        assertThat(AdsCampanhaWellpetService.ehWellpet(produto("Well Pet 50mg"))).isTrue();
        assertThat(AdsCampanhaWellpetService.ehWellpet(produto("MAXICAM INJETAVEL 0,2 % 20 ML"))).isFalse();
    }

    @Test
    void listaQuemDaCarteiraNuncaComprouWellpet() {
        LocalDate inicioCarteira = LocalDate.of(2025, 10, 1);
        AdsCampanhaWellpetService.Historico h = AdsCampanhaWellpetService.resumir(List.of(
                venda("VENDA DE MERCADORIA", "1", "003", "2026-09-10", produto("MAXICAM")),
                venda("VENDA DE MERCADORIA", "2", "003", "2026-08-01", produto("MAXICAM")),
                venda("VENDA DE MERCADORIA", "2", "003", "2025-09-15", produto("WELLPET 400MG")), // comprou no lançamento
                venda("BONIFICACAO CREDITO", "3", "003", "2026-05-01", produto("WELLPET 400MG")), // só ganhou: continua
                venda("VENDA DE MERCADORIA", "3", "003", "2026-05-01", produto("MAXICAM")),
                venda("VENDA DE MERCADORIA", "4", "003", "2025-09-20", produto("MAXICAM"))), // fora da carteira
                inicioCarteira);

        List<ClienteCampanhaWellpet> lista = h.semWellpet(OUTUBRO);

        assertThat(lista).extracting(ClienteCampanhaWellpet::getCodigoCliente).containsExactlyInAnyOrder("1", "3");
        ClienteCampanhaWellpet um = lista.stream().filter(c -> c.getCodigoCliente().equals("1")).findFirst().orElseThrow();
        assertThat(um.getNome()).isEqualTo("CLIENTE 1");
        assertThat(um.getRepresentanteCodigoAds()).isEqualTo("003");
        assertThat(um.getUltimaCompra()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(um.getMes()).isEqualTo("2026-10");
    }

    @Test
    void clienteDeDoisRepresentantesFicaComODaUltimaCompra() {
        AdsCampanhaWellpetService.Historico h = AdsCampanhaWellpetService.resumir(List.of(
                venda("VENDA DE MERCADORIA", "1", "003", "2026-09-10", produto("MAXICAM"))), LocalDate.of(2025, 10, 1));
        // Outro mês, buscado em paralelo e juntado depois.
        h.juntar(AdsCampanhaWellpetService.resumir(List.of(
                venda("VENDA DE MERCADORIA", "1", "007", "2026-03-02", produto("MAXICAM"))), LocalDate.of(2025, 10, 1)));

        assertThat(h.semWellpet(OUTUBRO)).singleElement()
                .extracting(ClienteCampanhaWellpet::getRepresentanteCodigoAds).isEqualTo("003");
    }

    @Test
    void contasDaCasaNaoFormamCarteiraMasOWellpetDelasConta() {
        AdsCampanhaWellpetService.Historico h = AdsCampanhaWellpetService.resumir(List.of(
                venda("VENDA DE MERCADORIA", "1", "073", "2026-09-10", produto("MAXICAM")), // só VIP: fora
                venda("VENDA DE MERCADORIA", "2", "003", "2026-03-02", produto("MAXICAM")),
                venda("VENDA DE MERCADORIA", "2", "001", "2026-09-20", produto("MAXICAM")), // mais recente, mas é da casa
                venda("VENDA DE MERCADORIA", "3", "003", "2026-04-02", produto("MAXICAM")),
                venda("VENDA DE MERCADORIA", "3", "013", "2026-05-02", produto("WELLPET 400MG"))), // venda direta de Wellpet
                LocalDate.of(2025, 10, 1));

        assertThat(h.semWellpet(OUTUBRO)).singleElement()
                .satisfies(c -> {
                    assertThat(c.getCodigoCliente()).isEqualTo("2");
                    assertThat(c.getRepresentanteCodigoAds()).isEqualTo("003");
                    assertThat(c.getUltimaCompra()).isEqualTo(LocalDate.of(2026, 3, 2));
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void montarBuscaDoLancamentoAteOFimDoMesAnteriorMesAMes() {
        when(client.buscarTudo(any(), any(), isNull())).thenAnswer(inv -> {
            LocalDate de = inv.getArgument(0);
            if (de.equals(LocalDate.of(2025, 9, 1))) {
                return List.of(venda("VENDA DE MERCADORIA", "2", "003", "2025-09-15", produto("WELLPET 400MG")));
            }
            if (de.equals(LocalDate.of(2026, 9, 1))) {
                return List.of(
                        venda("VENDA DE MERCADORIA", "1", "003", "2026-09-10", produto("MAXICAM")),
                        venda("VENDA DE MERCADORIA", "2", "003", "2026-09-11", produto("MAXICAM")));
            }
            return List.of();
        });

        servico.montarLista(OUTUBRO);

        ArgumentCaptor<LocalDate> inicios = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> fins = ArgumentCaptor.forClass(LocalDate.class);
        verify(client, org.mockito.Mockito.times(13)).buscarTudo(inicios.capture(), fins.capture(), isNull());
        assertThat(inicios.getAllValues().stream().min(Comparator.naturalOrder())).contains(LocalDate.of(2025, 9, 1));
        assertThat(fins.getAllValues().stream().max(Comparator.naturalOrder())).contains(LocalDate.of(2026, 9, 30));

        verify(clientes).deleteByMes("2026-10");
        ArgumentCaptor<List<ClienteCampanhaWellpet>> salvos = ArgumentCaptor.forClass(List.class);
        verify(clientes).saveAll(salvos.capture());
        assertThat(salvos.getValue()).extracting(ClienteCampanhaWellpet::getCodigoCliente).containsExactly("1");
        assertThat(servico.progresso(OUTUBRO)).isEmpty();
    }

    @Test
    void mesQueFalhouNaAdsNaoApagaALista() {
        when(client.buscarTudo(any(), any(), isNull())).thenThrow(new AdsApiException("ADS respondeu 500", true));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> servico.montarLista(OUTUBRO)).isInstanceOf(AdsApiException.class);

        verify(clientes, never()).deleteByMes(any());
    }

    @Test
    void montagemEmSegundoPlanoVoltaNaHoraEGuardaOErro() throws Exception {
        CountDownLatch liberar = new CountDownLatch(1);
        when(client.buscarTudo(any(), any(), isNull())).thenAnswer(inv -> {
            liberar.await(5, TimeUnit.SECONDS);
            throw new AdsApiException("ADS respondeu 500", true);
        });

        servico.iniciarMontagem(OUTUBRO);

        // Voltou antes de buscar: o mês aparece montando, e não dá pra começar outra por cima.
        assertThat(servico.progresso(OUTUBRO)).isPresent();
        assertThatThrownBy(() -> servico.iniciarMontagem(OUTUBRO)).isInstanceOf(RecursoEmUsoException.class);

        liberar.countDown();
        esperar(() -> servico.progresso(OUTUBRO).isEmpty());
        assertThat(servico.erro(OUTUBRO)).contains("ADS respondeu 500");
        verify(clientes, never()).deleteByMes(any());
    }

    @Test
    void montagemEmSegundoPlanoGravaALista() throws Exception {
        when(client.buscarTudo(any(), any(), isNull()))
                .thenReturn(List.of(venda("VENDA DE MERCADORIA", "1", "003", "2026-09-10", produto("MAXICAM"))));

        servico.iniciarMontagem(OUTUBRO);
        esperar(() -> servico.progresso(OUTUBRO).isEmpty());

        verify(clientes).deleteByMes("2026-10");
        verify(clientes).saveAll(any());
        assertThat(servico.erro(OUTUBRO)).isEmpty();
    }

    private static void esperar(BooleanSupplier condicao) throws InterruptedException {
        for (int i = 0; i < 500 && !condicao.getAsBoolean(); i++) Thread.sleep(10);
        assertThat(condicao.getAsBoolean()).isTrue();
    }

    @Test
    void positivaQuemComprouWellpetNoMesDescontandoDevolucao() {
        Map<String, AdsCampanhaWellpetService.CompraWellpet> r = AdsCampanhaWellpetService.somarWellpet(List.of(
                venda("VENDA DE MERCADORIA", "1", "007", "2026-10-08", item("WELLPET 400MG", 3, 90), item("MAXICAM", 1, 50)),
                venda("VENDA DE MERCADORIA", "1", "007", "2026-10-03", item("WELLPET 50MG", 1, 40)),
                venda("DEV. VENDA", "1", "007", "2026-10-09", item("WELLPET 400MG", 1, 30)),
                venda("BONIFICACAO CREDITO", "2", "003", "2026-10-02", item("WELLPET 400MG", 1, 30))));

        assertThat(r.get("1").reais).isCloseTo(90 + 40 - 30, within(1e-9));
        assertThat(r.get("1").unidades).isCloseTo(3, within(1e-9));
        assertThat(r.get("1").primeiraCompra).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(r).doesNotContainKey("2");
    }

    @Test
    void sincronizarZeraQuemNaoComprou() {
        ClienteCampanhaWellpet comprou = cliente("1");
        ClienteCampanhaWellpet devolveuTudo = cliente("2");
        devolveuTudo.setWellpetReais(50); // de uma sincronização anterior
        when(clientes.findByMes(Mes.atual().toString())).thenReturn(List.of(comprou, devolveuTudo));
        String hoje = LocalDate.now(Mes.FUSO).toString();
        when(client.buscarTudo(any(), any(), isNull(), any())).thenReturn(List.of(
                venda("VENDA DE MERCADORIA", "1", "003", hoje, item("WELLPET 400MG", 2, 180)),
                venda("VENDA DE MERCADORIA", "2", "003", hoje, item("WELLPET 400MG", 1, 90)),
                venda("DEV. VENDA", "2", "003", hoje, item("WELLPET 400MG", 1, 90))));

        servico.sincronizarMes(Mes.atual());

        assertThat(comprou.isPositivado()).isTrue();
        assertThat(comprou.getPrimeiraCompraWellpet()).isEqualTo(LocalDate.now(Mes.FUSO));
        assertThat(devolveuTudo.isPositivado()).isFalse();
        assertThat(devolveuTudo.getPrimeiraCompraWellpet()).isNull();
    }

    @Test
    void mesSemListaNaoChamaAAds() {
        when(clientes.findByMes(any())).thenReturn(List.of());
        servico.sincronizarMes(Mes.atual());
        verify(client, never()).buscarTudo(any(), any(), any(), any());
    }

    private static ClienteCampanhaWellpet cliente(String codigo) {
        ClienteCampanhaWellpet c = new ClienteCampanhaWellpet();
        c.setCodigoCliente(codigo);
        return c;
    }

    private static AdsVenda venda(String operacao, String clienteId, String representante, String data, AdsItemVenda... itens) {
        return new AdsVenda(LocalDate.parse(data).atStartOfDay(), 1, operacao, new AdsVenda.AdsFornecedor("1"),
                new AdsVenda.AdsCliente(clienteId, "000", "CLIENTE " + clienteId, "VETERINARIOS"),
                new AdsVenda.AdsRepresentante(representante, null, "REPR " + representante), new ArrayList<>(List.of(itens)));
    }

    private static AdsItemVenda produto(String descricao) {
        return item(descricao, 1, 10);
    }

    private static AdsItemVenda item(String descricao, double quantidade, double valor) {
        return new AdsItemVenda(new AdsItemVenda.AdsProduto("1", descricao), null, quantidade,
                new AdsItemVenda.AdsValoresItem(valor), new AdsItemVenda.AdsPeso(0, 0));
    }
}
