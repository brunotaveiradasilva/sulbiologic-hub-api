package com.almoxarifado.api.especialistapet;

import java.text.Normalizer;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.almoxarifado.api.ads.AdsEspecialistaPetService;
import com.almoxarifado.api.auth.EscopoAcesso;
import com.almoxarifado.api.common.RecursoJaExisteException;
import com.almoxarifado.api.metarepresentante.Mes;
import com.almoxarifado.api.representante.RepresentanteRepository;

import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campanha Especialista Pet (PremieR): os clientes e metas vêm da planilha mensal da PremieR, o
 * realizado vem da ADS. Só admins importam e sincronizam; supervisor e representante só consultam, e o
 * representante só os clientes dele (ver SecurityConfig e EscopoAcesso).
 */
@RestController
@RequestMapping("/api/especialista-pet")
public class EspecialistaPetController {

    private final ClienteEspecialistaPetRepository repository;
    private final AdsEspecialistaPetService sincronizacao;
    private final RepresentanteRepository representantes;
    private final EscopoAcesso escopo;

    public EspecialistaPetController(
            ClienteEspecialistaPetRepository repository,
            AdsEspecialistaPetService sincronizacao,
            RepresentanteRepository representantes,
            EscopoAcesso escopo) {
        this.repository = repository;
        this.sincronizacao = sincronizacao;
        this.representantes = representantes;
        this.escopo = escopo;
    }

    /**
     * Todos os meses, ou só um com ?mes=2026-09. Login de representante só recebe os clientes dele: a
     * planilha traz o VENDEDOR como texto, então vale o nome do cadastro de representantes, sem
     * diferenciar maiúscula, acento e espaço sobrando.
     */
    @GetMapping
    public List<ClienteEspecialistaPet> listar(@RequestParam(required = false) String mes) {
        List<ClienteEspecialistaPet> todos = mes == null || mes.isBlank()
                ? repository.findAllByOrderByRepresentanteAscNomeAsc()
                : repository.findByMesOrderByRepresentanteAscNomeAsc(Mes.ler(mes).toString());
        return escopo.representanteRestrito()
                .map(id -> representantes.findById(id)
                        .map(r -> normalizar(r.getNome()))
                        .map(nome -> todos.stream().filter(c -> nome.equals(normalizar(c.getRepresentante()))).toList())
                        .orElse(List.of()))
                .orElse(todos);
    }

    /** "  Ana  Lúcia " -> "ANA LUCIA". */
    static String normalizar(String nome) {
        if (nome == null) return "";
        return Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Troca os clientes e metas do mês pelos da planilha (a planilha nova substitui a antiga inteira).
     * O realizado começa zerado; o front-end chama /sincronizar logo depois.
     */
    @PostMapping("/importar")
    @Transactional
    public List<ClienteEspecialistaPet> importar(@Valid @RequestBody ImportarEspecialistaPetRequest corpo) {
        YearMonth mes = Mes.ler(corpo.mes());
        Set<String> codigos = new HashSet<>();
        for (ImportarEspecialistaPetRequest.Linha linha : corpo.clientes()) {
            if (!codigos.add(linha.codigoCliente().trim())) {
                throw new RecursoJaExisteException("O cliente " + linha.codigoCliente() + " aparece duas vezes na planilha");
            }
        }

        repository.deleteByMes(mes.toString());
        repository.flush();
        repository.saveAll(corpo.clientes().stream().map(linha -> {
            ClienteEspecialistaPet cliente = new ClienteEspecialistaPet();
            cliente.setMes(mes.toString());
            cliente.setCodigoCliente(linha.codigoCliente().trim());
            cliente.setNome(linha.nome().trim());
            cliente.setRepresentante(linha.representante().trim());
            cliente.setClassificacao(linha.classificacao() == null ? "" : linha.classificacao().trim().toUpperCase());
            cliente.setMetaFoco(linha.metaFoco());
            cliente.setMetaTotal(linha.metaTotal());
            return cliente;
        }).toList());
        return repository.findByMesOrderByRepresentanteAscNomeAsc(mes.toString());
    }

    /** percentual null = esse mês não está sincronizando agora. */
    public record ProgressoSincronizacao(Integer percentual) {
    }

    /**
     * Quanto já foi da sincronização em andamento do mês (0 a 100), pra tela mostrar enquanto o POST
     * /sincronizar ainda não voltou.
     */
    @GetMapping("/sincronizar/progresso")
    public ProgressoSincronizacao progresso(@RequestParam(required = false) String mes) {
        var p = sincronizacao.progresso(Mes.ler(mes));
        return new ProgressoSincronizacao(p.isPresent() ? p.getAsInt() : null);
    }

    /** Força agora o recálculo do realizado a partir da ADS, do mês pedido (?mes=2026-09) ou do atual. */
    @PostMapping("/sincronizar")
    public List<ClienteEspecialistaPet> sincronizar(@RequestParam(required = false) String mes) {
        YearMonth ym = Mes.ler(mes);
        sincronizacao.sincronizarMes(ym);
        return repository.findByMesOrderByRepresentanteAscNomeAsc(ym.toString());
    }
}
