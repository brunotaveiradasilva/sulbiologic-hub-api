package com.almoxarifado.api.campanhawellpet;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Um cliente da campanha de positivação Wellpet num mês: alguém da carteira de um representante (comprou
 * com ele nos 12 meses antes da campanha) que nunca tinha comprado Wellpet desde o lançamento. A lista
 * é montada uma vez, a partir da ADS (ver AdsCampanhaWellpetService); depois só o que ele comprou de
 * Wellpet no mês da campanha muda, a cada sincronização.
 *
 * <p>O representante é o da última compra do cliente na carteira — cliente atendido por dois aparece
 * só uma vez, com quem vendeu pra ele por último.
 */
@Entity
@Table(name = "campanha_wellpet_clientes", uniqueConstraints = @UniqueConstraint(
        name = "uk_campanha_wellpet_cliente_mes", columnNames = { "mes", "codigo_cliente" }))
public class ClienteCampanhaWellpet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Mês da campanha, no formato "2026-10". */
    @Column(nullable = false, length = 7)
    private String mes;

    /** Id do cliente na ADS. */
    @Column(name = "codigo_cliente", nullable = false, length = 20)
    private String codigoCliente;

    @Column(nullable = false)
    private String nome;

    @Column(name = "cnpj_cpf", nullable = false, length = 20)
    private String cnpjCpf;

    /** Segmento na ADS, ex.: "VETERINARIOS", "PET SHOP". */
    @Column(nullable = false, length = 60)
    private String segmento;

    /** Código do representante na ADS ("003") — é por ele que o login de representante vê só os seus. */
    @Column(name = "representante_codigo_ads", nullable = false, length = 20)
    private String representanteCodigoAds;

    /** Nome do representante como vem da ADS. */
    @Column(nullable = false)
    private String representante;

    /** Última compra do cliente (de qualquer coisa) com esse representante, antes da campanha. */
    @Column(name = "ultima_compra")
    private LocalDate ultimaCompra;

    /** Wellpet comprado no mês da campanha, em R$ (venda menos devolução). Acima de zero = positivou. */
    @Column(name = "wellpet_reais", nullable = false)
    private double wellpetReais;

    /** Unidades de Wellpet no mês da campanha (venda menos devolução). */
    @Column(name = "wellpet_unidades", nullable = false)
    private double wellpetUnidades;

    /** Dia da primeira compra de Wellpet no mês da campanha; null enquanto não positivou. */
    @Column(name = "primeira_compra_wellpet")
    private LocalDate primeiraCompraWellpet;

    /** Folga pra arredondamento, igual à positivação das metas. */
    private static final double SALDO_MINIMO_POSITIVADO = 0.01;

    public ClienteCampanhaWellpet() {
    }

    /** Comprou Wellpet no mês da campanha (sobrou saldo depois das devoluções). Vai no JSON; não é coluna. */
    public boolean isPositivado() {
        return wellpetReais >= SALDO_MINIMO_POSITIVADO;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMes() {
        return mes;
    }

    public void setMes(String mes) {
        this.mes = mes;
    }

    public String getCodigoCliente() {
        return codigoCliente;
    }

    public void setCodigoCliente(String codigoCliente) {
        this.codigoCliente = codigoCliente;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCnpjCpf() {
        return cnpjCpf;
    }

    public void setCnpjCpf(String cnpjCpf) {
        this.cnpjCpf = cnpjCpf;
    }

    public String getSegmento() {
        return segmento;
    }

    public void setSegmento(String segmento) {
        this.segmento = segmento;
    }

    public String getRepresentanteCodigoAds() {
        return representanteCodigoAds;
    }

    public void setRepresentanteCodigoAds(String representanteCodigoAds) {
        this.representanteCodigoAds = representanteCodigoAds;
    }

    public String getRepresentante() {
        return representante;
    }

    public void setRepresentante(String representante) {
        this.representante = representante;
    }

    public LocalDate getUltimaCompra() {
        return ultimaCompra;
    }

    public void setUltimaCompra(LocalDate ultimaCompra) {
        this.ultimaCompra = ultimaCompra;
    }

    public double getWellpetReais() {
        return wellpetReais;
    }

    public void setWellpetReais(double wellpetReais) {
        this.wellpetReais = wellpetReais;
    }

    public double getWellpetUnidades() {
        return wellpetUnidades;
    }

    public void setWellpetUnidades(double wellpetUnidades) {
        this.wellpetUnidades = wellpetUnidades;
    }

    public LocalDate getPrimeiraCompraWellpet() {
        return primeiraCompraWellpet;
    }

    public void setPrimeiraCompraWellpet(LocalDate primeiraCompraWellpet) {
        this.primeiraCompraWellpet = primeiraCompraWellpet;
    }
}
