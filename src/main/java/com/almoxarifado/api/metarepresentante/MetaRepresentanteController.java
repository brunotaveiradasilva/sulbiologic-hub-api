package com.almoxarifado.api.metarepresentante;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import com.almoxarifado.api.ads.AdsSincronizacaoService;
import com.almoxarifado.api.auth.EscopoAcesso;
import com.almoxarifado.api.common.RecursoJaExisteException;
import com.almoxarifado.api.common.RecursoNaoEncontradoException;
import com.almoxarifado.api.meta.Meta;
import com.almoxarifado.api.meta.MetaRepository;
import com.almoxarifado.api.representante.Representante;
import com.almoxarifado.api.representante.RepresentanteRepository;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Valor de meta (alvo + realizado) atribuído a um representante, por mês. Mês que já acabou fica só
 * pra consulta (ver {@link Mes#garantirAberto}). Só admins editam; supervisor e representante só
 * consultam, e o representante só as dele (ver SecurityConfig e EscopoAcesso).
 */
@RestController
@RequestMapping("/api/metas-representante")
public class MetaRepresentanteController {

    private final MetaRepresentanteRepository repository;
    private final TotalVendidoMensalRepository totais;
    private final RepresentanteRepository representantes;
    private final MetaRepository metas;
    private final AdsSincronizacaoService sincronizacao;
    private final EscopoAcesso escopo;

    public MetaRepresentanteController(
            MetaRepresentanteRepository repository,
            TotalVendidoMensalRepository totais,
            RepresentanteRepository representantes,
            MetaRepository metas,
            AdsSincronizacaoService sincronizacao,
            EscopoAcesso escopo) {
        this.repository = repository;
        this.totais = totais;
        this.representantes = representantes;
        this.metas = metas;
        this.sincronizacao = sincronizacao;
        this.escopo = escopo;
    }

    /** Todos os meses, ou só um com ?mes=2026-09. Login de representante só recebe as dele. */
    @GetMapping
    public List<MetaRepresentante> listar(@RequestParam(required = false) String mes) {
        List<MetaRepresentante> todas = mes == null || mes.isBlank()
                ? repository.findAll()
                : repository.findByMes(Mes.ler(mes).toString());
        return escopo.representanteRestrito()
                .map(id -> todas.stream().filter(m -> id.equals(m.getRepresentante().getId())).toList())
                .orElse(todas);
    }

    /** Total vendido por representante e mês (card "Total vendido"), vindo da sincronização com a ADS. */
    @GetMapping("/totais-vendidos")
    public List<TotalVendidoMensal> totaisVendidos() {
        List<TotalVendidoMensal> todos = totais.findAll();
        return escopo.representanteRestrito()
                .map(id -> todos.stream().filter(t -> id.equals(t.getRepresentanteId())).toList())
                .orElse(todos);
    }

    /** Força agora o recálculo do realizado a partir da ADS, do mês pedido (?mes=2026-09) ou do atual. */
    @PostMapping("/sincronizar")
    public List<MetaRepresentante> sincronizar(@RequestParam(required = false) String mes) {
        return sincronizacao.sincronizarMes(Mes.ler(mes));
    }

    /**
     * Copia os valores de meta de um mês pro outro (ex.: agosto → setembro), só onde o mês de destino
     * ainda não tem valor (ou só tem a linha "sem meta", valor 0) — nunca sobrescreve o que já foi
     * cadastrado. O realizado vem na sincronização. Devolve as atribuições criadas ou preenchidas.
     */
    @PostMapping("/copiar")
    @Transactional
    public List<MetaRepresentante> copiar(@Valid @RequestBody CopiarMetasRequest corpo) {
        YearMonth de = Mes.ler(corpo.de());
        YearMonth para = Mes.ler(corpo.para());
        if (de.equals(para)) throw new MesInvalidoException("Escolha meses diferentes pra copiar");
        Mes.garantirAberto(para);

        String fornecedorId = corpo.fornecedorId();
        List<MetaRepresentante> criadas = new ArrayList<>();
        for (MetaRepresentante origem : repository.findByMes(de.toString())) {
            if (fornecedorId != null && !fornecedorId.isBlank()
                    && !fornecedorId.equals(origem.getMeta().getFornecedor().getId())) continue;

            // Valor 0 é "sem meta" (a sincronização cria só pra guardar o realizado): não tem o que copiar.
            if (origem.getValorMeta() <= 0) continue;
            // Meta oculta fica só no mês em que valeu: não vai pro seguinte.
            if (origem.getMeta().isOculta()) continue;

            // No destino, a linha "sem meta" da sincronização conta como vazia: ganha o valor e mantém o realizado.
            MetaRepresentante copia = repository.findByRepresentanteIdAndMetaIdAndMes(
                    origem.getRepresentante().getId(), origem.getMeta().getId(), para.toString()).orElse(null);
            if (copia != null && copia.getValorMeta() > 0) continue;

            if (copia == null) {
                copia = new MetaRepresentante();
                copia.setRepresentante(origem.getRepresentante());
                copia.setMeta(origem.getMeta());
                copia.setMes(para.toString());
            }
            copia.setValorMeta(origem.getValorMeta());
            criadas.add(repository.save(copia));
        }
        return criadas;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MetaRepresentante criar(@Valid @RequestBody MetaRepresentanteRequest corpo) {
        YearMonth mes = Mes.ler(corpo.mes());
        Mes.garantirAberto(mes);
        garantirAtribuicaoLivre(corpo.representanteId(), corpo.metaId(), mes.toString(), null);

        MetaRepresentante atribuicao = new MetaRepresentante();
        preencher(atribuicao, corpo, mes.toString());
        return repository.save(atribuicao);
    }

    /**
     * Troca o valor da meta. O realizado fica como está: ele só muda pela sincronização com a ADS.
     * Nem o mês de onde ela sai nem o mês pra onde vai podem estar fechados.
     */
    @PutMapping("/{id}")
    public MetaRepresentante atualizar(@PathVariable String id, @Valid @RequestBody MetaRepresentanteRequest corpo) {
        MetaRepresentante existente = buscarOuFalhar(id);
        Mes.garantirAberto(Mes.ler(existente.getMes()));
        // Sem mês no corpo (front antigo), mantém o mês que o registro já tinha.
        YearMonth mes = corpo.mes() == null || corpo.mes().isBlank() ? Mes.ler(existente.getMes()) : Mes.ler(corpo.mes());
        Mes.garantirAberto(mes);
        garantirAtribuicaoLivre(corpo.representanteId(), corpo.metaId(), mes.toString(), id);
        preencher(existente, corpo, mes.toString());
        return repository.save(existente);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable String id) {
        Mes.garantirAberto(Mes.ler(buscarOuFalhar(id).getMes()));
        repository.deleteById(id);
    }

    private void preencher(MetaRepresentante atribuicao, MetaRepresentanteRequest corpo, String mes) {
        atribuicao.setRepresentante(buscarRepresentante(corpo.representanteId()));
        atribuicao.setMeta(buscarMeta(corpo.metaId()));
        atribuicao.setMes(mes);
        atribuicao.setValorMeta(corpo.valorMeta());
    }

    /** Só pode existir uma atribuição por representante + meta + mês — a segunda vira uma edição da primeira. */
    private void garantirAtribuicaoLivre(String representanteId, String metaId, String mes, String idAtual) {
        repository.findByRepresentanteIdAndMetaIdAndMes(representanteId, metaId, mes).ifPresent(outra -> {
            if (!outra.getId().equals(idAtual)) {
                throw new RecursoJaExisteException("Esse representante já tem um valor atribuído pra essa meta nesse mês");
            }
        });
    }

    private Representante buscarRepresentante(String id) {
        return representantes.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Representante " + id + " não encontrado"));
    }

    private Meta buscarMeta(String id) {
        return metas.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Meta " + id + " não encontrada"));
    }

    private MetaRepresentante buscarOuFalhar(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Valor de meta " + id + " não encontrado"));
    }
}
