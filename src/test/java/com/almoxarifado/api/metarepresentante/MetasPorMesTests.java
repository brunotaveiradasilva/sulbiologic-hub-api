package com.almoxarifado.api.metarepresentante;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.almoxarifado.api.ads.AdsSincronizacaoService;
import com.almoxarifado.api.fornecedor.Fornecedor;
import com.almoxarifado.api.fornecedor.FornecedorRepository;
import com.almoxarifado.api.meta.Meta;
import com.almoxarifado.api.meta.MetaRepository;
import com.almoxarifado.api.meta.UnidadeMeta;
import com.almoxarifado.api.representante.Representante;
import com.almoxarifado.api.representante.RepresentanteRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaspormes;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MetasPorMesTests {

    @Autowired JdbcTemplate jdbc;
    @Autowired MigracaoMetasPorMes migracao;
    @Autowired MetaRepresentanteController controller;
    @Autowired AdsSincronizacaoService sincronizacao;
    @Autowired MetaRepresentanteRepository atribuicoes;
    @Autowired FornecedorRepository fornecedores;
    @Autowired MetaRepository metas;
    @Autowired RepresentanteRepository representantes;

    Fornecedor vetnil;
    Fornecedor ceva;
    Meta v10;
    Meta geralCeva;
    Representante marina;

    // Relativos a hoje: mês passado fica fechado pra edição, então nada de mês fixo.
    static final String ANTERIOR = Mes.atual().minusMonths(1).toString();
    static final String ATUAL = Mes.atual().toString();
    static final String PROXIMO = Mes.atual().plusMonths(1).toString();

    @BeforeEach
    void preparar() {
        jdbc.execute("DROP TABLE IF EXISTS " + MigracaoMetasPorMes.TABELA_ANTIGA);
        atribuicoes.deleteAll();
        metas.deleteAll();
        representantes.deleteAll();
        fornecedores.deleteAll();

        vetnil = fornecedor("Vetnil");
        ceva = fornecedor("Ceva");
        v10 = meta("Vacina V10", vetnil);
        geralCeva = meta("Geral Ceva", ceva);
        Representante r = new Representante();
        r.setNome("Marina Alves");
        marina = representantes.save(r);
    }

    @Test
    void migraATabelaAntigaProMesAtualEApagaEla() {
        jdbc.execute("CREATE TABLE " + MigracaoMetasPorMes.TABELA_ANTIGA
                + " (id VARCHAR(255) PRIMARY KEY, representante_id VARCHAR(255), meta_id VARCHAR(255),"
                + " valor_meta DOUBLE, valor_realizado DOUBLE)");
        jdbc.update("INSERT INTO " + MigracaoMetasPorMes.TABELA_ANTIGA + " VALUES (?, ?, ?, ?, ?)",
                "antiga-1", marina.getId(), v10.getId(), 500.0, 320.0);

        migracao.run(null);

        List<MetaRepresentante> migradas = atribuicoes.findAll();
        assertThat(migradas).hasSize(1);
        MetaRepresentante m = migradas.get(0);
        assertThat(m.getId()).isEqualTo("antiga-1");
        assertThat(m.getMes()).isEqualTo(Mes.atual().toString());
        assertThat(m.getValorMeta()).isEqualTo(500.0);
        assertThat(m.getValorRealizado()).isEqualTo(320.0);
        assertThat(tabelaAntigaExiste()).isFalse();

        // Rodar de novo (próximo boot) não faz nada nem quebra.
        migracao.run(null);
        assertThat(atribuicoes.count()).isEqualTo(1);
    }

    @Test
    void semTabelaAntigaNaoFazNada() {
        migracao.run(null);
        assertThat(atribuicoes.count()).isZero();
    }

    @Test
    void copiaSoOQueFaltaNoMesDeDestinoESoDoFornecedorPedido() {
        salvar(v10, ANTERIOR, 500, 480);
        salvar(geralCeva, ANTERIOR, 1000, 900);
        salvar(v10, ATUAL, 650, 0); // o mês atual já tem valor pra V10: não pode ser sobrescrito

        List<MetaRepresentante> soVetnil = controller.copiar(new CopiarMetasRequest(ANTERIOR, ATUAL, vetnil.getId()));
        assertThat(soVetnil).isEmpty();

        List<MetaRepresentante> criadas = controller.copiar(new CopiarMetasRequest(ANTERIOR, ATUAL, null));
        assertThat(criadas).hasSize(1);
        assertThat(criadas.get(0).getMeta().getId()).isEqualTo(geralCeva.getId());
        assertThat(criadas.get(0).getValorMeta()).isEqualTo(1000);
        assertThat(criadas.get(0).getValorRealizado()).isZero();

        MetaRepresentante v10Setembro = atribuicoes
                .findByRepresentanteIdAndMetaIdAndMes(marina.getId(), v10.getId(), ATUAL).orElseThrow();
        assertThat(v10Setembro.getValorMeta()).isEqualTo(650);
    }

    @Test
    void metaOcultaNaoECopiadaProMesSeguinte() {
        salvar(v10, ANTERIOR, 500, 480);
        salvar(geralCeva, ANTERIOR, 1000, 900);
        geralCeva.setOculta(true);
        metas.save(geralCeva);

        List<MetaRepresentante> criadas = controller.copiar(new CopiarMetasRequest(ANTERIOR, ATUAL, null));

        assertThat(criadas).extracting(mv -> mv.getMeta().getId()).containsExactly(v10.getId());
        // O valor do mês em que ela valeu continua lá.
        assertThat(atribuicoes.findByRepresentanteIdAndMetaIdAndMes(marina.getId(), geralCeva.getId(), ANTERIOR)).isPresent();
    }

    @Test
    void mesmaMetaPodeTerValorDiferenteEmCadaMesMasNaoDuasNoMesmoMes() {
        controller.criar(new MetaRepresentanteRequest(marina.getId(), v10.getId(), ATUAL, 650.0));
        controller.criar(new MetaRepresentanteRequest(marina.getId(), v10.getId(), PROXIMO, 700.0));
        assertThat(controller.listar(ATUAL)).extracting(MetaRepresentante::getValorMeta).containsExactly(650.0);
        assertThat(controller.listar(PROXIMO)).extracting(MetaRepresentante::getValorMeta).containsExactly(700.0);

        assertThatThrownBy(() -> controller.criar(new MetaRepresentanteRequest(marina.getId(), v10.getId(), ATUAL, 1.0)))
                .hasMessageContaining("nesse mês");
        assertThatThrownBy(() -> controller.listar("setembro")).isInstanceOf(MesInvalidoException.class);
    }

    @Test
    void editarAMetaNaoMexeNoRealizado() {
        MetaRepresentante salva = salvar(v10, ATUAL, 500, 320);
        MetaRepresentante editada = controller.atualizar(salva.getId(),
                new MetaRepresentanteRequest(marina.getId(), v10.getId(), ATUAL, 650.0));
        assertThat(editada.getValorMeta()).isEqualTo(650);
        assertThat(editada.getValorRealizado()).isEqualTo(320);
    }

    @Test
    void mesQueJaAcabouFicaSoPraConsulta() {
        MetaRepresentante passada = salvar(v10, ANTERIOR, 500, 480);

        assertThatThrownBy(() -> controller.criar(new MetaRepresentanteRequest(marina.getId(), geralCeva.getId(), ANTERIOR, 1.0)))
                .isInstanceOf(MesFechadoException.class)
                .hasMessageContaining("já fechou");
        assertThatThrownBy(() -> controller.atualizar(passada.getId(),
                new MetaRepresentanteRequest(marina.getId(), v10.getId(), ANTERIOR, 999.0)))
                .isInstanceOf(MesFechadoException.class);
        // Nem "mudando" ela pro mês atual dá pra mexer num registro de mês fechado.
        assertThatThrownBy(() -> controller.atualizar(passada.getId(),
                new MetaRepresentanteRequest(marina.getId(), v10.getId(), ATUAL, 999.0)))
                .isInstanceOf(MesFechadoException.class);
        assertThatThrownBy(() -> controller.excluir(passada.getId())).isInstanceOf(MesFechadoException.class);
        assertThatThrownBy(() -> controller.copiar(new CopiarMetasRequest(ATUAL, ANTERIOR, null)))
                .isInstanceOf(MesFechadoException.class);

        MetaRepresentante intacta = atribuicoes.findById(passada.getId()).orElseThrow();
        assertThat(intacta.getValorMeta()).isEqualTo(500);
        assertThat(intacta.getMes()).isEqualTo(ANTERIOR);
    }

    @Test
    void sincronizarMesFuturoNaoFazNada() {
        assertThat(sincronizacao.sincronizarMes(Mes.atual().plusMonths(1))).isEmpty();
    }

    private MetaRepresentante salvar(Meta meta, String mes, double valorMeta, double realizado) {
        MetaRepresentante m = new MetaRepresentante();
        m.setRepresentante(marina);
        m.setMeta(meta);
        m.setMes(mes);
        m.setValorMeta(valorMeta);
        m.setValorRealizado(realizado);
        return atribuicoes.save(m);
    }

    private Fornecedor fornecedor(String nome) {
        Fornecedor f = new Fornecedor();
        f.setNome(nome);
        return fornecedores.save(f);
    }

    private Meta meta(String nome, Fornecedor fornecedor) {
        Meta m = new Meta();
        m.setNome(nome);
        m.setFornecedor(fornecedor);
        m.setUnidade(UnidadeMeta.UNIDADE);
        return metas.save(m);
    }

    private boolean tabelaAntigaExiste() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + MigracaoMetasPorMes.TABELA_ANTIGA, Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
