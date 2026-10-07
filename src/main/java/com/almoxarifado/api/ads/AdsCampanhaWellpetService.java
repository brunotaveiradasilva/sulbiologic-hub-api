package com.almoxarifado.api.ads;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.almoxarifado.api.campanhawellpet.ClienteCampanhaWellpet;
import com.almoxarifado.api.campanhawellpet.ClienteCampanhaWellpetRepository;
import com.almoxarifado.api.common.RecursoEmUsoException;
import com.almoxarifado.api.metarepresentante.Mes;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Campanha de positivação Wellpet: clientes que nunca compraram Wellpet, de cada representante, e
 * quem deles comprou no mês da campanha.
 *
 * <ul>
 *   <li><b>Lista</b> ({@link #montarLista}): a carteira do representante são os clientes que compraram
 *   qualquer coisa com ele nos {@value #MESES_DE_CARTEIRA} meses antes da campanha; desses, fica quem
 *   não tem nenhuma compra de Wellpet (com qualquer representante) desde o lançamento, em
 *   {@link #LANCAMENTO_WELLPET}. Cliente atendido por mais de um representante fica com o da última
 *   compra. Só os {@link #REPRESENTANTES_DA_CAMPANHA} têm carteira.</li>
 *   <li><b>Positivação</b> ({@link #sincronizarMes}): o Wellpet que cada cliente da lista comprou no mês
 *   da campanha, com qualquer representante — o que vale é o cliente.</li>
 * </ul>
 *
 * Wellpet é qualquer produto com "WELLPET" no nome (todas as apresentações). Venda soma, devolução
 * desconta e bonificação não conta, igual a {@link AdsSincronizacaoService#sinal(AdsVenda)} — e
 * Wellpet que o cliente só ganhou de bonificação não tira ele da lista.
 */
@Service
public class AdsCampanhaWellpetService {

    private static final Logger log = LoggerFactory.getLogger(AdsCampanhaWellpetService.class);

    /** O Wellpet foi lançado em setembro/2025: antes disso ninguém tinha como ter comprado. */
    static final LocalDate LANCAMENTO_WELLPET = LocalDate.of(2025, 9, 1);
    static final int MESES_DE_CARTEIRA = 12;
    /**
     * Os representantes que participam da campanha, pelo código na ADS: 005 Marcelo Albuquerque,
     * 021 Emerson Alexandre, 030 Julio Cezar, 043 Paulo Cesar Duarte, 053 Josenias Lopes,
     * 077 Emanuel Cirilo, 079 Nayara Nunes e 080 Sabrina Nantes. Só as vendas deles formam carteira:
     * cliente que só comprou com outros fica fora da lista, e quem comprou com um deles e com outro fica
     * com ele. O Wellpet vendido por qualquer um continua contando (pra "já comprou" e pra positivação),
     * porque o que vale é o cliente.
     */
    static final Set<String> REPRESENTANTES_DA_CAMPANHA = Set.of("005", "021", "030", "043", "053", "077", "079", "080");

    /** Montar a lista varre mais de um ano da ADS: alguns meses ao mesmo tempo, sem exagerar pra ela não recusar. */
    private static final int BUSCAS_SIMULTANEAS = 4;

    private final ClienteCampanhaWellpetRepository clientes;
    private final AdsHistoricoVendasClient client;
    private final ExecutorService buscas = Executors.newFixedThreadPool(BUSCAS_SIMULTANEAS);
    /** Montagens iniciadas pela tela: uma de cada vez, fora da requisição (ver {@link #iniciarMontagem}). */
    private final ExecutorService montagens = Executors.newSingleThreadExecutor();
    private final Map<String, Integer> progressoPorMes = new ConcurrentHashMap<>();
    /** Por que a última montagem em segundo plano de cada mês falhou; some quando outra começa. */
    private final Map<String, String> erroPorMes = new ConcurrentHashMap<>();

    public AdsCampanhaWellpetService(ClienteCampanhaWellpetRepository clientes, AdsHistoricoVendasClient client) {
        this.clientes = clientes;
        this.client = client;
    }

    @PreDestroy
    void encerrar() {
        buscas.shutdownNow();
        montagens.shutdownNow();
    }

    /** Junto com as outras sincronizações: todo dia o mês atual, e nos primeiros dias também o anterior. */
    @Scheduled(cron = "0 20 6 * * *", zone = "America/Sao_Paulo")
    public void sincronizarAgendado() {
        YearMonth atual = Mes.atual();
        sincronizarMes(atual);
        if (LocalDate.now(Mes.FUSO).getDayOfMonth() <= 5) {
            sincronizarMes(atual.minusMonths(1));
        }
    }

    /** Quanto (0 a 100) já foi feito da montagem ou sincronização em andamento do mês; vazio se nada está rodando. */
    public OptionalInt progresso(YearMonth mes) {
        Integer p = progressoPorMes.get(mes.toString());
        return p == null ? OptionalInt.empty() : OptionalInt.of(p);
    }

    /** Por que a última montagem em segundo plano do mês falhou; vazio se não falhou (ou ainda está rodando). */
    public Optional<String> erro(YearMonth mes) {
        return Optional.ofNullable(erroPorMes.get(mes.toString()));
    }

    /**
     * Começa a montar a lista do mês e volta na hora. Montar leva minutos, e uma requisição aberta esse
     * tempo todo cai no caminho (o proxy do Railway fecha a conexão antes da resposta): a tela acompanha
     * por {@link #progresso} e {@link #erro}. Se o mês já está montando ou sincronizando, não começa outra.
     */
    public void iniciarMontagem(YearMonth mes) {
        if (progressoPorMes.putIfAbsent(mes.toString(), 0) != null) {
            throw new RecursoEmUsoException("A lista de " + mes + " já está sendo montada ou sincronizada. Espere terminar.");
        }
        erroPorMes.remove(mes.toString());
        try {
            montagens.submit(() -> {
                try {
                    montarLista(mes);
                } catch (RuntimeException e) {
                    log.warn("Não foi possível montar a lista da campanha Wellpet de {}", mes, e);
                    erroPorMes.put(mes.toString(), e.getMessage() == null ? "Erro ao montar a lista. Tente de novo." : e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            progressoPorMes.remove(mes.toString());
            throw e;
        }
    }

    /**
     * Troca a lista do mês da campanha pela que sai do histórico da ADS até o fim do mês anterior. A
     * positivação começa zerada — chame {@link #sincronizarMes} depois.
     */
    public void montarLista(YearMonth mes) {
        progressoPorMes.put(mes.toString(), 0);
        try {
            List<ClienteCampanhaWellpet> lista = calcularLista(mes);
            clientes.deleteByMes(mes.toString());
            clientes.saveAll(lista);
        } finally {
            progressoPorMes.remove(mes.toString());
        }
    }

    private List<ClienteCampanhaWellpet> calcularLista(YearMonth mes) {
        LocalDate fim = mes.minusMonths(1).atEndOfMonth();
        LocalDate inicioCarteira = mes.minusMonths(MESES_DE_CARTEIRA).atDay(1);
        LocalDate inicio = inicioCarteira.isBefore(LANCAMENTO_WELLPET) ? inicioCarteira : LANCAMENTO_WELLPET;

        // Um mês de cada vez, já resumido por cliente: mais de um ano de vendas inteiro não cabe com folga na memória.
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = YearMonth.from(inicio); !m.isAfter(YearMonth.from(fim)); m = m.plusMonths(1)) meses.add(m);
        AtomicInteger feitos = new AtomicInteger();
        List<CompletableFuture<Historico>> pendentes = meses.stream()
                .map(m -> CompletableFuture.supplyAsync(() -> {
                    LocalDate de = m.atDay(1).isBefore(inicio) ? inicio : m.atDay(1);
                    LocalDate ate = m.atEndOfMonth().isAfter(fim) ? fim : m.atEndOfMonth();
                    Historico h = resumir(client.buscarTudo(de, ate, null), inicioCarteira);
                    progressoPorMes.put(mes.toString(), Math.min(99, feitos.incrementAndGet() * 100 / meses.size()));
                    return h;
                }, buscas))
                .toList();

        Historico total = new Historico();
        try {
            pendentes.forEach(f -> total.juntar(f.join()));
        } catch (CompletionException e) {
            // Lista montada com um mês faltando sairia com cliente que já comprou Wellpet: melhor não montar.
            pendentes.forEach(f -> f.cancel(true));
            log.warn("Não foi possível montar a lista da campanha Wellpet de {}: {}", mes, e.getMessage());
            if (e.getCause() instanceof RuntimeException causa) throw causa;
            throw e;
        }
        return total.semWellpet(mes);
    }

    /**
     * Do pedaço do histórico: quem comprou Wellpet e, das compras a partir de {@code inicioCarteira}, a
     * última de cada cliente (com quem e o cadastro dele na ADS).
     */
    static Historico resumir(List<AdsVenda> vendas, LocalDate inicioCarteira) {
        Historico h = new Historico();
        for (AdsVenda venda : vendas) {
            if (venda.cliente() == null || venda.cliente().id() == null) continue;
            if (AdsSincronizacaoService.sinal(venda) <= 0) continue;
            String clienteId = venda.cliente().id();
            if (venda.itens().stream().anyMatch(AdsCampanhaWellpetService::ehWellpet)) h.compraramWellpet.add(clienteId);

            LocalDate data = venda.dataFaturamento() == null ? null : venda.dataFaturamento().toLocalDate();
            if (data == null || data.isBefore(inicioCarteira) || venda.representante() == null || venda.representante().id() == null
                    || !REPRESENTANTES_DA_CAMPANHA.contains(venda.representante().id().trim())) {
                continue;
            }
            h.registrarCompra(clienteId, new UltimaCompra(data, venda.cliente(), venda.representante()));
        }
        return h;
    }

    record UltimaCompra(LocalDate data, AdsVenda.AdsCliente cliente, AdsVenda.AdsRepresentante representante) {
    }

    static final class Historico {
        final Set<String> compraramWellpet = new HashSet<>();
        final Map<String, UltimaCompra> carteira = new HashMap<>();

        void registrarCompra(String clienteId, UltimaCompra compra) {
            carteira.merge(clienteId, compra, (atual, nova) -> nova.data().isAfter(atual.data()) ? nova : atual);
        }

        void juntar(Historico outro) {
            compraramWellpet.addAll(outro.compraramWellpet);
            outro.carteira.forEach(this::registrarCompra);
        }

        List<ClienteCampanhaWellpet> semWellpet(YearMonth mes) {
            return carteira.entrySet().stream()
                    .filter(e -> !compraramWellpet.contains(e.getKey()))
                    .map(e -> {
                        UltimaCompra u = e.getValue();
                        ClienteCampanhaWellpet c = new ClienteCampanhaWellpet();
                        c.setMes(mes.toString());
                        c.setCodigoCliente(e.getKey());
                        c.setNome(texto(u.cliente().nome(), "Cliente " + e.getKey()));
                        c.setCnpjCpf(texto(u.cliente().cnpjCpf(), ""));
                        c.setSegmento(texto(u.cliente().segmento(), ""));
                        c.setRepresentanteCodigoAds(u.representante().id().trim());
                        c.setRepresentante(texto(u.representante().nome(), u.representante().id()));
                        c.setUltimaCompra(u.data());
                        return c;
                    })
                    .toList();
        }
    }

    /** Recalcula o Wellpet que os clientes da lista compraram no mês. Mês sem lista montada não chama a ADS. */
    public void sincronizarMes(YearMonth mes) {
        progressoPorMes.put(mes.toString(), 0);
        try {
            sincronizarMesComProgresso(mes);
        } finally {
            progressoPorMes.remove(mes.toString());
        }
    }

    private void sincronizarMesComProgresso(YearMonth mes) {
        LocalDate hoje = LocalDate.now(Mes.FUSO);
        LocalDate inicio = mes.atDay(1);
        if (inicio.isAfter(hoje)) return;
        LocalDate fim = mes.atEndOfMonth().isAfter(hoje) ? hoje : mes.atEndOfMonth();

        List<ClienteCampanhaWellpet> participantes = clientes.findByMes(mes.toString());
        if (participantes.isEmpty()) return;

        List<AdsVenda> vendas;
        try {
            vendas = client.buscarTudo(inicio, fim, null, (lidas, total) ->
                    progressoPorMes.put(mes.toString(), total > 0 ? Math.min(99, lidas * 100 / total) : 0));
        } catch (AdsApiException e) {
            log.warn("Não foi possível sincronizar a campanha Wellpet de {} com a ADS: {}", mes, e.getMessage());
            throw e;
        }

        Map<String, CompraWellpet> porCliente = somarWellpet(vendas);
        for (ClienteCampanhaWellpet cliente : participantes) {
            CompraWellpet w = porCliente.getOrDefault(cliente.getCodigoCliente(), new CompraWellpet());
            cliente.setWellpetReais(w.reais);
            cliente.setWellpetUnidades(w.unidades);
            cliente.setPrimeiraCompraWellpet(cliente.isPositivado() ? w.primeiraCompra : null);
        }
        clientes.saveAll(participantes);
    }

    static Map<String, CompraWellpet> somarWellpet(List<AdsVenda> vendas) {
        Map<String, CompraWellpet> porCliente = new HashMap<>();
        for (AdsVenda venda : vendas) {
            if (venda.cliente() == null || venda.cliente().id() == null) continue;
            int sinal = AdsSincronizacaoService.sinal(venda);
            if (sinal == 0) continue;
            List<AdsItemVenda> wellpet = venda.itens().stream().filter(AdsCampanhaWellpetService::ehWellpet).toList();
            if (wellpet.isEmpty()) continue;

            CompraWellpet w = porCliente.computeIfAbsent(venda.cliente().id(), id -> new CompraWellpet());
            for (AdsItemVenda item : wellpet) {
                w.reais += sinal * item.valores().valorProduto();
                w.unidades += sinal * item.quantidade();
            }
            LocalDate data = venda.dataFaturamento() == null ? null : venda.dataFaturamento().toLocalDate();
            if (sinal > 0 && data != null && (w.primeiraCompra == null || data.isBefore(w.primeiraCompra))) {
                w.primeiraCompra = data;
            }
        }
        return porCliente;
    }

    static final class CompraWellpet {
        double reais;
        double unidades;
        LocalDate primeiraCompra;
    }

    /** "WELLPET 400MG (20,1 A 40KG)", "Well Pet 50mg"... — qualquer apresentação. */
    static boolean ehWellpet(AdsItemVenda item) {
        if (item.produto() == null || item.produto().descricao() == null) return false;
        String nome = Normalizer.normalize(item.produto().descricao(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
        return nome.contains("WELLPET");
    }

    private static String texto(String valor, String seVazio) {
        return valor == null || valor.isBlank() ? seVazio : valor.trim();
    }
}
