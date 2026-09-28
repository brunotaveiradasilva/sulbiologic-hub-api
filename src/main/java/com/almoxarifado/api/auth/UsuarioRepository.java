package com.almoxarifado.api.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, String> {

    Optional<Usuario> findByUsuarioIgnoreCase(String usuario);

    long countByRole(Role role);
}
