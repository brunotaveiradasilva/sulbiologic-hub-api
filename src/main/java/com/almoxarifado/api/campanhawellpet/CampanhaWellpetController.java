package com.almoxarifado.api.campanhawellpet;

import java.time.YearMonth;
import java.util.List;

import com.almoxarifado.api.ads.AdsCampanhaWellpetService;
import com.almoxarifado.api.auth.EscopoAcesso;
import com.almoxarifado.api.metarepresentante.Mes;
import com.almoxarifado.api.representante.Representante;
import com.almoxarifado.api.representante.RepresentanteRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campanha de positivação Wellpet: a lista de clientes que nunca compraram Wellpet, por representante,
 * e quem já comprou no mês da campanha — tudo a partir da ADS. Só admins montam a lista e sincronizam;
 * supervisor e representante só consultam, e o representante só os clientes dele (ver SecurityConfig e
 * EscopoAcesso).
 */
@RestController
@RequestMapping("/api/campanha-wellpet")
public class CampanhaWellpetController {

    private final ClienteCampanhaWellpetRepository repository;
    private final AdsCampanhaWellpetService servico;
    private final RepresentanteRepository representantes;
    private final EscopoAcesso escopo;

    public CampanhaWellpetController(
            ClienteCampanhaWellpetRepository repository,
            AdsCampanhaWellpetService servico,
            RepresentanteRepository representantes,
            EscopoAcesso escopo) {
        this.repository = repository;
        this.servico = servico;
        this.representantes = representantes;
        this.escopo = escopo;
    }

    /**
     * Todos os meses, ou só um com ?mes=2026-10. Login de representante só recebe os clientes do código
     * ADS do representante dele no cadastro.
     */
    @GetMapping
    public List<ClienteCampanhaWellpet> listar(@RequestParam(required = false) String mes) {
        List<ClienteCampanhaWellpet> todos = mes == null || mes.isBlank()
                ? repository.findAllByOrderByRepresentanteAscNomeAsc()
                : repository.findByMesOrderByRepresentanteAscNomeAsc(Mes.ler(mes).toString());
        return escopo.representanteRestrito()
                .map(id -> representantes.findById(id)
                        .map(Representante::getCodigoAds)
                        .filter(codigo -> codigo != null && !codigo.isBlank())
                        .map(codigo -> todos.stream().filter(c -> codigo.trim().equals(c.getRepresentanteCodigoAds())).toList())
                        .orElse(List.of()))
                .orElse(todos);
    }

    /**
     * percentual null = esse mês não está montando nem sincronizando agora. erro = por que a última
     * montagem do mês falhou (null se não falhou).
     */
    public record Progresso(Integer percentual, String erro) {
    }

    /** Quanto já foi da montagem ou sincronização em andamento do mês (0 a 100), e o erro se a montagem falhou. */
    @GetMapping("/progresso")
    public Progresso progresso(@RequestParam(required = false) String mes) {
        YearMonth ym = Mes.ler(mes);
        var p = servico.progresso(ym);
        return new Progresso(p.isPresent() ? p.getAsInt() : null, servico.erro(ym).orElse(null));
    }

    /**
     * Começa a montar (ou refazer) a lista do mês a partir do histórico da ADS e responde na hora (202):
     * montar leva minutos, e a tela acompanha por /progresso. Quando ele parar sem erro, a lista está
     * gravada, com a positivação zerada — o front-end chama /sincronizar em seguida.
     */
    @PostMapping("/montar")
    public ResponseEntity<Void> montar(@RequestParam(required = false) String mes) {
        servico.iniciarMontagem(Mes.ler(mes));
        return ResponseEntity.accepted().build();
    }

    /** Busca agora na ADS quem da lista já comprou Wellpet no mês (o mês atual também roda sozinho todo dia). */
    @PostMapping("/sincronizar")
    public List<ClienteCampanhaWellpet> sincronizar(@RequestParam(required = false) String mes) {
        YearMonth ym = Mes.ler(mes);
        servico.sincronizarMes(ym);
        return repository.findByMesOrderByRepresentanteAscNomeAsc(ym.toString());
    }
}
