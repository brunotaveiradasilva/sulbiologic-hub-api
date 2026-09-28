package com.almoxarifado.api.auth;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Um login do sistema. A senha nunca é guardada em texto puro, só o hash (BCrypt). */
@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String usuario;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    // columnDefinition com DEFAULT: a coluna é nova, e o ALTER TABLE precisa de um valor pros
    // logins que já existem no banco (senão falha em modo estrito do MySQL).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20) default 'USUARIO'")
    private Role role = Role.USUARIO;

    /** Foto de perfil como data URL (base64), já redimensionada e comprimida pelo front-end. */
    @Column(columnDefinition = "LONGTEXT")
    private String avatar;

    /**
     * Representante dono desse login, quando o role é REPRESENTANTE. Só o id, sem chave estrangeira,
     * pra não impedir a exclusão do representante (o login fica sem enxergar nada até ser religado).
     */
    @Column(name = "representante_id")
    private String representanteId;

    /** Dados da pessoa dona do login — todos opcionais (logins antigos não têm). */
    @Column(length = 80)
    private String nome;

    @Column(length = 80)
    private String sobrenome;

    @Column(length = 120)
    private String email;

    @Column(name = "data_nascimento")
    private LocalDate dataNascimento;

    public Usuario() {
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getSobrenome() {
        return sobrenome;
    }

    public void setSobrenome(String sobrenome) {
        this.sobrenome = sobrenome;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getDataNascimento() {
        return dataNascimento;
    }

    public void setDataNascimento(LocalDate dataNascimento) {
        this.dataNascimento = dataNascimento;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public void setSenhaHash(String senhaHash) {
        this.senhaHash = senhaHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getRepresentanteId() {
        return representanteId;
    }

    public void setRepresentanteId(String representanteId) {
        this.representanteId = representanteId;
    }
}
