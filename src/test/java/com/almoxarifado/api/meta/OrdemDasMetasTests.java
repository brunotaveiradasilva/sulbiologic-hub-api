package com.almoxarifado.api.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.almoxarifado.api.fornecedor.Fornecedor;
import com.almoxarifado.api.fornecedor.FornecedorRepository;
import com.almoxarifado.api.metarepresentante.MetaRepresentanteRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** A ordem das metas na tela é a que o admin escolher; metas sem ordem vão pro fim, por nome. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ordemdasmetas;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrdemDasMetasTests {

    @Autowired MetaController controller;
    @Autowired MetaRepository metas;
    @Autowired MetaRepresentanteRepository atribuicoes;
    @Autowired FornecedorRepository fornecedores;

    Fornecedor ourofino;

    @BeforeEach
    void preparar() {
        atribuicoes.deleteAll();
        metas.deleteAll();
        fornecedores.deleteAll();
        Fornecedor f = new Fornecedor();
        f.setNome("Ourofino");
        ourofino = fornecedores.save(f);
    }

    @Test
    void metaNovaEntraNoFim() {
        criar("Wellpet");
        criar("Banni - Unidade");
        criar("Ourofino Linha Base");

        assertThat(nomes(controller.listar())).containsExactly("Wellpet", "Banni - Unidade", "Ourofino Linha Base");
    }

    @Test
    void ordenarGravaAOrdemEscolhida() {
        Meta wellpet = criar("Wellpet");
        Meta base = criar("Ourofino Linha Base");
        Meta banni = criar("Banni - Unidade");

        List<Meta> resposta = controller.ordenar(new OrdenarMetasRequest(List.of(banni.getId(), base.getId(), wellpet.getId())));

        assertThat(nomes(resposta)).containsExactly("Banni - Unidade", "Ourofino Linha Base", "Wellpet");
        assertThat(nomes(controller.listar())).containsExactly("Banni - Unidade", "Ourofino Linha Base", "Wellpet");
    }

    @Test
    void quemFicaDeForaDaListaVaiProFimEIdDesconhecidoEIgnorado() {
        Meta wellpet = criar("Wellpet");
        criar("Ourofino Linha Base");
        Meta banni = criar("Banni - Unidade");

        controller.ordenar(new OrdenarMetasRequest(List.of(banni.getId(), "nao-existe", wellpet.getId())));

        assertThat(nomes(controller.listar())).containsExactly("Banni - Unidade", "Wellpet", "Ourofino Linha Base");
    }

    @Test
    void metasAntigasSemOrdemVaoProFimPorNome() {
        criar("Wellpet");
        salvarSemOrdem("Zeta");
        salvarSemOrdem("Alfa");

        assertThat(nomes(controller.listar())).containsExactly("Wellpet", "Alfa", "Zeta");
    }

    @Test
    void ocultarEMostrarNaoTiraAMetaDaLista() {
        Meta wellpet = criar("Wellpet");
        assertThat(wellpet.isOculta()).isFalse();

        assertThat(controller.ocultar(wellpet.getId(), new OcultarMetaRequest(true)).isOculta()).isTrue();
        assertThat(nomes(controller.listar())).containsExactly("Wellpet");
        assertThat(controller.listar().get(0).isOculta()).isTrue();

        assertThat(controller.ocultar(wellpet.getId(), new OcultarMetaRequest(false)).isOculta()).isFalse();
    }

    private Meta criar(String nome) {
        return controller.criar(new MetaRequest(nome, ourofino.getId(), UnidadeMeta.REAL, "", "", "", "", null, null, null));
    }

    private void salvarSemOrdem(String nome) {
        Meta meta = new Meta();
        meta.setNome(nome);
        meta.setFornecedor(ourofino);
        meta.setUnidade(UnidadeMeta.REAL);
        metas.save(meta);
    }

    private static List<String> nomes(List<Meta> lista) {
        return lista.stream().map(Meta::getNome).toList();
    }
}
