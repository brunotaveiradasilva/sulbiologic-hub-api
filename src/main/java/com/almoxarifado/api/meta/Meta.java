package com.almoxarifado.api.meta;

import com.almoxarifado.api.fornecedor.Fornecedor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Uma meta: pertence a um único fornecedor e é medida numa unidade fixa (kg, unidade ou R$). */
@Entity
@Table(name = "metas")
public class Meta {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotBlank(message = "Informe o nome da meta")
    @Column(nullable = false)
    private String nome;

    @NotNull(message = "Informe o fornecedor")
    @ManyToOne(optional = false)
    @JoinColumn(name = "fornecedor_id", nullable = false)
    private Fornecedor fornecedor;

    @NotNull(message = "Informe a unidade de medida")
    // varchar em vez do enum(...) que o Hibernate cria sozinho no MySQL: senão cada unidade nova
    // exigiria alterar a coluna (bancos antigos são convertidos por MigracaoUnidadeMetaVarchar).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private UnidadeMeta unidade;

    /**
     * Código (ou códigos, separados por vírgula) da divisão correspondente na API da ADS —
     * soma só os itens vendidos nessa(s) divisão(ões). Vazio se essa meta usa
     * {@link #cnpjAdsFornecedor} ou não é sincronizada automaticamente.
     */
    private String codigoAdsDivisao;

    /**
     * CNPJ do fornecedor na API da ADS — alternativa a {@link #codigoAdsDivisao} pra metas
     * "catch-all" que somam tudo vendido desse fornecedor, não só uma divisão específica
     * (ex: uma meta "Geral"). Se preenchido, tem prioridade sobre codigoAdsDivisao.
     */
    private String cnpjAdsFornecedor;

    /**
     * Produtos que não contam pra essa meta, separados por vírgula. Cada um é o código do produto
     * na ADS (ex: "5085") ou um trecho do nome (ex: "WELLPET" tira todas as apresentações) — pra
     * metas tipo "Ourofino sem Wellpet". Vale tanto com cnpjAdsFornecedor quanto com codigoAdsDivisao.
     */
    private String produtosExcluidos;

    /**
     * Se preenchido, só esses produtos contam pra meta — mesmo formato de {@link #produtosExcluidos}
     * (código na ADS ou trecho do nome). Pra metas de um produto específico, tipo as sazonais do
     * Banni. Pode ser usado sozinho, sem CNPJ nem divisão.
     */
    private String produtosIncluidos;

    /**
     * Posição da meta nas listas da tela (0 primeiro), definida pelo admin em PUT /api/metas/ordem.
     * Null em metas de antes disso — elas vão pro fim, por nome.
     */
    private Integer ordem;

    /** Texto livre pra explicar a meta (ex: "Campanha da 1ª quinzena"). Opcional. */
    @Column(length = 500)
    private String descricao;

    /**
     * Período da meta dentro do mês, em dias (ex: 1 a 19 e outra de 20 a 31): só as vendas faturadas
     * nesses dias contam. Os dois null = mês inteiro. Um fim maior que o último dia do mês (31 em
     * setembro) vale até o fim do mês.
     */
    private Integer diaInicio;
    private Integer diaFim;

    /**
     * Meta guardada só pelo histórico (ex: uma campanha que valeu só em setembro): some das telas de
     * consulta nos meses sem valor de meta, não é copiada pro mês seguinte e a sincronização não cria
     * linha "sem meta" pra ela. As atribuições que já existem continuam como estão.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean oculta;

    public Meta() {
    }

    /** O dia do mês está no período da meta? Sem período, todo dia está. */
    public boolean noPeriodo(int dia) {
        if (diaInicio == null || diaFim == null) return true;
        return dia >= diaInicio && dia <= diaFim;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Fornecedor getFornecedor() {
        return fornecedor;
    }

    public void setFornecedor(Fornecedor fornecedor) {
        this.fornecedor = fornecedor;
    }

    public UnidadeMeta getUnidade() {
        return unidade;
    }

    public void setUnidade(UnidadeMeta unidade) {
        this.unidade = unidade;
    }

    public String getCodigoAdsDivisao() {
        return codigoAdsDivisao;
    }

    public void setCodigoAdsDivisao(String codigoAdsDivisao) {
        this.codigoAdsDivisao = codigoAdsDivisao;
    }

    public String getCnpjAdsFornecedor() {
        return cnpjAdsFornecedor;
    }

    public void setCnpjAdsFornecedor(String cnpjAdsFornecedor) {
        this.cnpjAdsFornecedor = cnpjAdsFornecedor;
    }

    public String getProdutosExcluidos() {
        return produtosExcluidos;
    }

    public void setProdutosExcluidos(String produtosExcluidos) {
        this.produtosExcluidos = produtosExcluidos;
    }

    public String getProdutosIncluidos() {
        return produtosIncluidos;
    }

    public void setProdutosIncluidos(String produtosIncluidos) {
        this.produtosIncluidos = produtosIncluidos;
    }

    public Integer getOrdem() {
        return ordem;
    }

    public void setOrdem(Integer ordem) {
        this.ordem = ordem;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public Integer getDiaInicio() {
        return diaInicio;
    }

    public void setDiaInicio(Integer diaInicio) {
        this.diaInicio = diaInicio;
    }

    public Integer getDiaFim() {
        return diaFim;
    }

    public void setDiaFim(Integer diaFim) {
        this.diaFim = diaFim;
    }

    public boolean isOculta() {
        return oculta;
    }

    public void setOculta(boolean oculta) {
        this.oculta = oculta;
    }
}
