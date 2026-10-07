package com.almoxarifado.api.meta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.almoxarifado.api.common.RecursoNaoEncontradoException;
import com.almoxarifado.api.fornecedor.Fornecedor;
import com.almoxarifado.api.fornecedor.FornecedorRepository;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CRUD de metas. Só admins acessam (ver SecurityConfig). */
@RestController
@RequestMapping("/api/metas")
public class MetaController {

    private static final Comparator<Meta> NA_ORDEM = Comparator
            .comparing(Meta::getOrdem, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Meta::getNome, String.CASE_INSENSITIVE_ORDER);

    private final MetaRepository repository;
    private final FornecedorRepository fornecedores;

    public MetaController(MetaRepository repository, FornecedorRepository fornecedores) {
        this.repository = repository;
        this.fornecedores = fornecedores;
    }

    /** Na ordem escolhida pelo admin; metas sem ordem (de antes dela existir) vão pro fim, por nome. */
    @GetMapping
    public List<Meta> listar() {
        return repository.findAll().stream().sorted(NA_ORDEM).toList();
    }

    /** Meta nova entra no fim da lista. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Meta criar(@Valid @RequestBody MetaRequest corpo) {
        Meta meta = new Meta();
        preencher(meta, corpo);
        meta.setOrdem(listar().size());
        return repository.save(meta);
    }

    /**
     * Grava a ordem das metas: cada id recebe a sua posição na lista. As que não vierem na lista vão
     * pro fim, mantendo a ordem que já tinham. Devolve todas as metas já na ordem nova.
     */
    @PutMapping("/ordem")
    public List<Meta> ordenar(@Valid @RequestBody OrdenarMetasRequest corpo) {
        Map<String, Meta> porId = listar().stream()
                .collect(Collectors.toMap(Meta::getId, m -> m, (a, b) -> a, LinkedHashMap::new));
        List<Meta> ordenadas = new ArrayList<>();
        for (String id : corpo.ids()) {
            Meta meta = porId.remove(id);
            if (meta != null) ordenadas.add(meta);
        }
        ordenadas.addAll(porId.values());
        for (int i = 0; i < ordenadas.size(); i++) ordenadas.get(i).setOrdem(i);
        return repository.saveAll(ordenadas);
    }

    @PutMapping("/{id}")
    public Meta atualizar(@PathVariable String id, @Valid @RequestBody MetaRequest corpo) {
        Meta existente = buscarOuFalhar(id);
        preencher(existente, corpo);
        return repository.save(existente);
    }

    /** Oculta ou volta a mostrar a meta, sem mexer no resto dela nem nas atribuições. */
    @PutMapping("/{id}/oculta")
    public Meta ocultar(@PathVariable String id, @Valid @RequestBody OcultarMetaRequest corpo) {
        Meta existente = buscarOuFalhar(id);
        existente.setOculta(corpo.oculta());
        return repository.save(existente);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable String id) {
        buscarOuFalhar(id);
        repository.deleteById(id);
    }

    private void preencher(Meta meta, MetaRequest corpo) {
        meta.setNome(corpo.nome());
        meta.setUnidade(corpo.unidade());
        meta.setCodigoAdsDivisao(corpo.codigoAdsDivisao());
        meta.setCnpjAdsFornecedor(corpo.cnpjAdsFornecedor());
        meta.setProdutosExcluidos(corpo.produtosExcluidos());
        meta.setProdutosIncluidos(corpo.produtosIncluidos());
        meta.setDescricao(corpo.descricao() == null || corpo.descricao().isBlank() ? null : corpo.descricao().trim());
        meta.setDiaInicio(corpo.diaInicio());
        meta.setDiaFim(corpo.diaFim());
        meta.setFornecedor(buscarFornecedor(corpo.fornecedorId()));
    }

    private Fornecedor buscarFornecedor(String fornecedorId) {
        return fornecedores.findById(fornecedorId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Fornecedor " + fornecedorId + " não encontrado"));
    }

    private Meta buscarOuFalhar(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Meta " + id + " não encontrada"));
    }
}
