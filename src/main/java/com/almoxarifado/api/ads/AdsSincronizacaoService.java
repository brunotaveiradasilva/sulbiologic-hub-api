package com.almoxarifado.api.ads;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.almoxarifado.api.fornecedor.Fornecedor;
import com.almoxarifado.api.meta.Meta;
import com.almoxarifado.api.meta.MetaRepository;
import com.almoxarifado.api.meta.UnidadeMeta;
import com.almoxarifado.api.metarepresentante.MetaRepresentante;
import com.almoxarifado.api.metarepresentante.MetaRepresentanteRepository;
import com.almoxarifado.api.metarepresentante.Mes;
import com.almoxarifado.api.metarepresentante.TotalVendidoMensal;
import com.almoxarifado.api.metarepresentante.TotalVendidoMensalRepository;
import com.almoxarifado.api.representante.Representante;
import com.almoxarifado.api.representante.RepresentanteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recalcula o valorRealizado das metas de um mês a partir do histórico de vendas da ADS desse mês
 * (dia 1 até o fim do mês, ou até hoje no mês atual). Para cada representante com {@link Representante#getCodigoAds()} preenchido,
 * busca o histórico uma vez só e soma, por meta, de um dos dois jeitos:
 *
 * <ul>
 *   <li>{@link Meta#getCnpjAdsFornecedor()} preenchido — soma tudo vendido desse fornecedor
 *   (todas as divisões), pra metas "catch-all" tipo "Geral". Tem prioridade sobre codigoAdsDivisao.</li>
 *   <li>{@link Meta#getCodigoAdsDivisao()} preenchido — soma só os itens cuja divisão bate com um
 *   dos códigos (aceita vários separados por vírgula, ex: "112,113").</li>
 * </ul>
 *
 * Cada um soma quantidade (UNIDADE), peso bruto (KG — confirmado batendo com o valor esperado
 * pelo usuário; peso líquido dava um número menor) ou valor do produto (REAL), considerando o
 * tipo de operação de cada pedido (ver {@link #sinal(AdsVenda)}): venda soma, devolução desconta,
 * bonificação não conta. Metas CLIENTES (positivação) em vez de somar contam quantos clientes
 * diferentes ficaram com saldo positivo em R$ nesses mesmos itens. Representante ou meta sem nenhum
 * dos dois códigos (nem produtos incluídos) cadastrado fica de fora, sem erro. Com
 * {@link Meta#getProdutosIncluidos()} só esses produtos contam (sozinho ou junto com CNPJ/divisão), e
 * itens que batem com {@link Meta#getProdutosExcluidos()} nunca contam — os dois aceitam código do
 * produto ou trecho do nome. Um incluído com fator ("4931*3") faz cada unidade vendida contar N nas
 * metas UNIDADE (kits que a ADS manda como quantidade 1).
 *
 * Também grava um {@link TotalVendidoMensal}: a soma de TODO o histórico de vendas do
 * representante no mês (todos os fornecedores/divisões, não só o que está mapeado em alguma meta)
 * — é o número que a tela usa pro card "Total vendido".
 */
@Service
public class AdsSincronizacaoService {

    private static final Logger log = LoggerFactory.getLogger(AdsSincronizacaoService.class);

    /** Folga pra arredondamento: cliente com venda e devolução que se anulam fica com ~0, não positivado. */
    private static final double SALDO_MINIMO_POSITIVADO = 0.01;

    private final MetaRepresentanteRepository metasRepresentante;
    private final TotalVendidoMensalRepository totais;
    private final RepresentanteRepository representantes;
    private final MetaRepository metas;
    private final AdsHistoricoVendasClient client;

    public AdsSincronizacaoService(
            MetaRepresentanteRepository metasRepresentante,
            TotalVendidoMensalRepository totais,
            RepresentanteRepository representantes,
            MetaRepository metas,
            AdsHistoricoVendasClient client) {
        this.metasRepresentante = metasRepresentante;
        this.totais = totais;
        this.representantes = representantes;
        this.metas = metas;
        this.client = client;
    }

    /**
     * Todo dia sincroniza o mês atual. Nos primeiros dias do mês também refaz o mês anterior, pra
     * fechar ele com as vendas e devoluções que a ADS ainda lança com atraso.
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "America/Sao_Paulo")
    public void sincronizarAgendado() {
        YearMonth atual = Mes.atual();
        sincronizarMes(atual);
        if (LocalDate.now(Mes.FUSO).getDayOfMonth() <= 5) {
            sincronizarMes(atual.minusMonths(1));
        }
    }

    /**
     * Recalcula e salva o valorRealizado das atribuições do mês cujo representante e meta têm código
     * ADS cadastrado, com as vendas do dia 1 ao último dia do mês (ou até hoje, se for o mês atual).
     * Mês futuro não tem venda: não faz nada.
     *
     * <p>Roda pra todo representante com código ADS, mesmo sem nenhuma meta no mês: as metas dos
     * fornecedores dele que ainda não têm valor ganham uma atribuição com valorMeta 0 ("sem meta"),
     * só pra guardar o realizado — assim a tela mostra quanto ele vendeu mesmo sem meta definida.
     */
    public List<MetaRepresentante> sincronizarMes(YearMonth mes) {
        LocalDate hoje = LocalDate.now(Mes.FUSO);
        LocalDate inicio = mes.atDay(1);
        if (inicio.isAfter(hoje)) return List.of();
        LocalDate fim = mes.atEndOfMonth().isAfter(hoje) ? hoje : mes.atEndOfMonth();
        boolean mesAtual = mes.equals(Mes.atual());

        Map<String, List<MetaRepresentante>> porRepresentanteId = metasRepresentante.findByMes(mes.toString()).stream()
                .filter(mv -> temCodigo(mv.getRepresentante().getCodigoAds()) && metaTemCodigo(mv.getMeta()))
                .collect(Collectors.groupingBy(mv -> mv.getRepresentante().getId(), HashMap::new, Collectors.toList()));

        Map<String, Representante> aSincronizar = new HashMap<>();
        porRepresentanteId.values().forEach(lista -> aSincronizar.put(lista.get(0).getRepresentante().getId(), lista.get(0).getRepresentante()));
        representantes.findAll().stream()
                .filter(r -> temCodigo(r.getCodigoAds()))
                .forEach(r -> aSincronizar.putIfAbsent(r.getId(), r));
        // Meta oculta só sincroniza onde já tem atribuição: não ganha linha "sem meta" nova.
        List<Meta> todasAsMetas = metas.findAll().stream().filter(m -> !m.isOculta()).filter(this::metaTemCodigo).toList();

        List<MetaRepresentante> atualizadas = new ArrayList<>();
        aSincronizar.forEach((representanteId, representante) -> {
            List<MetaRepresentante> atribuicoes = new ArrayList<>(porRepresentanteId.getOrDefault(representanteId, List.of()));
            Set<String> fornecedorIds = representante.getFornecedores().stream().map(Fornecedor::getId).collect(Collectors.toSet());
            Set<String> jaTem = atribuicoes.stream().map(mv -> mv.getMeta().getId()).collect(Collectors.toSet());
            for (Meta meta : todasAsMetas) {
                if (!fornecedorIds.contains(meta.getFornecedor().getId()) || jaTem.contains(meta.getId())) continue;
                MetaRepresentante semMeta = new MetaRepresentante();
                semMeta.setRepresentante(representante);
                semMeta.setMeta(meta);
                semMeta.setMes(mes.toString());
                atribuicoes.add(semMeta);
            }
            try {
                List<AdsVenda> vendas = client.buscarTudo(inicio, fim, representante.getCodigoAds());
                for (MetaRepresentante atribuicao : atribuicoes) {
                    Meta meta = atribuicao.getMeta();
                    atribuicao.setValorRealizado(somar(vendas, meta, meta.getUnidade()));
                    // Meta em kg também mostra quanto isso deu em R$, com os mesmos itens.
                    atribuicao.setRealizadoEmReais(meta.getUnidade() == UnidadeMeta.KG
                            ? somar(vendas, meta, UnidadeMeta.REAL)
                            : null);
                    atualizadas.add(metasRepresentante.save(atribuicao));
                }

                double totalVendido = vendas.stream()
                        .mapToDouble(v -> sinal(v) * v.itens().stream()
                                .mapToDouble(item -> item.valores().valorProduto())
                                .sum())
                        .sum();
                TotalVendidoMensal total = totais.findByRepresentanteIdAndMes(representanteId, mes.toString())
                        .orElseGet(() -> new TotalVendidoMensal(representanteId, mes.toString()));
                total.setTotal(totalVendido);
                totais.save(total);

                // Campo antigo, sem mês: continua sendo o total do mês atual, pra quem ainda lê ele.
                if (mesAtual) {
                    representante.setTotalVendidoAds(totalVendido);
                    representantes.save(representante);
                }
            } catch (AdsApiException e) {
                log.warn("Não foi possível sincronizar o representante {} ({}) com a ADS: {}",
                        representante.getNome(), representante.getCodigoAds(), e.getMessage());
            }
        });
        return atualizadas;
    }

    /** Soma os itens da meta na unidade pedida — normalmente a da meta; REAL pro "em R$" das metas em kg. */
    private double somar(List<AdsVenda> vendas, Meta meta, UnidadeMeta unidade) {
        Predicate<AdsItemVenda> daMeta = itemDaMeta(meta);
        List<AdsVenda> doFornecedor = vendas.stream()
                .filter(v -> noPeriodo(v, meta))
                .filter(v -> !temCodigo(meta.getCnpjAdsFornecedor()) || meta.getCnpjAdsFornecedor().equals(v.fornecedor().cnpj()))
                .toList();

        if (unidade == UnidadeMeta.CLIENTES) {
            return contarClientesPositivados(doFornecedor, daMeta);
        }
        List<FiltroProduto> incluidos = filtrosProduto(meta.getProdutosIncluidos());
        return doFornecedor.stream()
                .mapToDouble(v -> sinal(v) * v.itens().stream()
                        .filter(daMeta)
                        .mapToDouble(item -> valor(item, unidade, fator(item, incluidos)))
                        .sum())
                .sum();
    }

    /**
     * Venda faturada num dia do período da meta (ex: 1 a 19)? Meta sem período aceita o mês todo; venda
     * sem data só conta em meta sem período, já que não dá pra saber em qual parte do mês ela caiu.
     */
    private boolean noPeriodo(AdsVenda venda, Meta meta) {
        if (meta.getDiaInicio() == null || meta.getDiaFim() == null) return true;
        return venda.dataFaturamento() != null && meta.noPeriodo(venda.dataFaturamento().getDayOfMonth());
    }

    /**
     * Quantas unidades cada unidade vendida vale na meta: o maior fator entre os produtos incluídos que
     * batem com o item (ex: "4931*3" — kit com 3 flaconetes que a ADS manda como quantidade 1). Sem fator, 1.
     */
    private int fator(AdsItemVenda item, List<FiltroProduto> incluidos) {
        return incluidos.stream().filter(f -> f.bate(item)).mapToInt(FiltroProduto::fator).max().orElse(1);
    }

    /**
     * Com cnpjAdsFornecedor todo item do pedido conta (o filtro é no pedido); com codigoAdsDivisao, só os
     * das divisões da meta. Em cima disso, se a meta tem produtos incluídos só eles contam, e os produtos
     * excluídos sempre ficam de fora.
     */
    private Predicate<AdsItemVenda> itemDaMeta(Meta meta) {
        Predicate<AdsItemVenda> daMeta = itemDaDivisao(meta);
        if (temCodigo(meta.getProdutosIncluidos())) daMeta = daMeta.and(produtoEm(meta.getProdutosIncluidos()));
        if (temCodigo(meta.getProdutosExcluidos())) daMeta = daMeta.and(produtoEm(meta.getProdutosExcluidos()).negate());
        return daMeta;
    }

    /** Sem CNPJ nem divisão (meta só por produtos incluídos), todo item passa por aqui. */
    private Predicate<AdsItemVenda> itemDaDivisao(Meta meta) {
        if (temCodigo(meta.getCnpjAdsFornecedor()) || !temCodigo(meta.getCodigoAdsDivisao())) return item -> true;
        Set<String> divisoes = separarPorVirgula(meta.getCodigoAdsDivisao());
        return item -> divisoes.contains(item.divisao().id());
    }

    /** Item cujo produto bate com algum da lista (separada por vírgula) — ver {@link FiltroProduto}. */
    private Predicate<AdsItemVenda> produtoEm(String lista) {
        List<FiltroProduto> filtros = filtrosProduto(lista);
        return item -> filtros.stream().anyMatch(f -> f.bate(item));
    }

    private List<FiltroProduto> filtrosProduto(String lista) {
        if (!temCodigo(lista)) return List.of();
        return separarPorVirgula(lista.toUpperCase()).stream().map(FiltroProduto::de).toList();
    }

    /**
     * Um produto da lista de incluídos/excluídos. Só dígitos é o código do produto na ADS (tem que ser
     * igual); texto é um trecho do nome, sem diferenciar maiúscula. Pode terminar com "*N" (ou "xN"
     * depois de um código, ex: "4931x3"): cada unidade vendida conta N nas metas UNIDADE.
     */
    private record FiltroProduto(String termo, int fator) {

        private static final Pattern COM_FATOR = Pattern.compile("^(.+?)\\s*\\*\\s*(\\d+)$");
        private static final Pattern CODIGO_X_FATOR = Pattern.compile("^(\\d+)\\s*X\\s*(\\d+)$");

        static FiltroProduto de(String texto) {
            Matcher m = COM_FATOR.matcher(texto);
            if (!m.matches()) m = CODIGO_X_FATOR.matcher(texto);
            if (m.matches()) return new FiltroProduto(m.group(1).trim(), Integer.parseInt(m.group(2)));
            return new FiltroProduto(texto, 1);
        }

        boolean bate(AdsItemVenda item) {
            if (item.produto() == null) return false;
            if (termo.chars().allMatch(Character::isDigit)) return termo.equals(item.produto().id());
            String descricao = item.produto().descricao() == null ? "" : item.produto().descricao().toUpperCase();
            return descricao.contains(termo);
        }
    }

    private Set<String> separarPorVirgula(String texto) {
        return Arrays.stream(texto.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Positivação: quantos clientes diferentes ficaram com saldo positivo em R$ nos itens da meta
     * (vendas menos devoluções, sem bonificação). Quem comprou e devolveu tudo não conta.
     */
    private double contarClientesPositivados(List<AdsVenda> vendas, Predicate<AdsItemVenda> daMeta) {
        Map<String, Double> saldoPorCliente = new HashMap<>();
        for (AdsVenda venda : vendas) {
            if (venda.cliente() == null || venda.cliente().id() == null) continue;
            double valor = sinal(venda) * venda.itens().stream()
                    .filter(daMeta)
                    .mapToDouble(item -> item.valores().valorProduto())
                    .sum();
            saldoPorCliente.merge(venda.cliente().id(), valor, Double::sum);
        }
        return saldoPorCliente.values().stream().filter(saldo -> saldo > SALDO_MINIMO_POSITIVADO).count();
    }

    /**
     * Nem todo pedido da ADS é uma venda de verdade: bonificação (brinde/troca promocional) não
     * conta pro realizado, e devolução desconta o que tinha sido contado antes. Só
     * "VENDA DE MERCADORIA" soma; o que tem "DEV" no nome da operação subtrai; bonificação e
     * qualquer operação não reconhecida ficam de fora (soma zero), pra nunca contar algo errado
     * por engano.
     */
    static int sinal(AdsVenda venda) {
        String operacao = venda.operacao() == null ? "" : venda.operacao().toUpperCase();
        if (operacao.contains("BONIFICA")) return 0;
        if (operacao.contains("DEV")) return -1;
        if (operacao.contains("VENDA")) return 1;
        return 0;
    }

    /** O fator (ver {@link #fator}) só muda UNIDADE: R$ e peso já vêm certos pro kit inteiro. */
    private double valor(AdsItemVenda item, UnidadeMeta unidade, int fator) {
        return switch (unidade) {
            case REAL -> item.valores().valorProduto();
            case KG -> item.peso().bruto();
            case UNIDADE -> item.quantidade() * fator;
            case CLIENTES -> throw new IllegalArgumentException("CLIENTES é contado por cliente, não somado por item");
        };
    }

    private boolean metaTemCodigo(Meta meta) {
        return temCodigo(meta.getCnpjAdsFornecedor()) || temCodigo(meta.getCodigoAdsDivisao())
                || temCodigo(meta.getProdutosIncluidos());
    }

    private boolean temCodigo(String codigo) {
        return codigo != null && !codigo.isBlank();
    }
}
