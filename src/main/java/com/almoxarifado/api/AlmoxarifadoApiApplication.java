package com.almoxarifado.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration fica excluída porque a autenticação é toda manual (JWT,
// ver auth/JwtAuthFilter): sem isso, o Spring cria um usuário padrão com senha aleatória que
// nunca é usado, só polui o log de inicialização.
// Os jobs diários com a ADS são ligados em config/AgendadorConfig.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AlmoxarifadoApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlmoxarifadoApiApplication.class, args);
    }
}
