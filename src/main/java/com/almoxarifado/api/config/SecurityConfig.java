package com.almoxarifado.api.config;

import java.util.List;

import com.almoxarifado.api.auth.JwtAuthFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * API sem sessão (stateless): cada requisição se autentica sozinha com um JWT no header
 * Authorization. Só /api/auth/login e o health check ficam abertos; todo o resto exige token
 * válido — é o {@link JwtAuthFilter} quem lê e valida esse token antes de chegar no controller.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] ROTAS_DE_METAS = {
            "/api/representantes/**",
            "/api/fornecedores/**",
            "/api/metas/**",
            "/api/metas-representante/**",
            "/api/especialista-pet/**",
    };

    private final JwtAuthFilter jwtAuthFilter;
    private final String[] origensPermitidas;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, @Value("${app.cors.allowed-origins}") String origensPermitidas) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.origensPermitidas = origensPermitidas.split(",");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sem isso, o Spring Security devolve 403 pra quem não está autenticado (o
                // padrão dele quando não há httpBasic/formLogin). O front-end espera 401
                // especificamente pra saber que precisa mandar de volta pro login.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Preflight de CORS: o navegador nunca manda Authorization nele, então
                        // precisa passar livre ou toda chamada não-GET do front-end quebra.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // Logins e o comparativo de vendas são só do admin.
                        .requestMatchers("/api/auth/usuarios", "/api/auth/usuarios/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/api/dados/**").hasAuthority("ROLE_ADMIN")
                        // Metas e campanhas: supervisor e representante só consultam (GET) — e o
                        // representante só recebe o que é dele, filtrado nos controllers (EscopoAcesso).
                        // Criar, editar, importar e sincronizar continua só pro admin.
                        .requestMatchers(HttpMethod.GET, ROTAS_DE_METAS).hasAnyAuthority(
                                "ROLE_ADMIN", "ROLE_SUPERVISOR", "ROLE_REPRESENTANTE")
                        .requestMatchers(ROTAS_DE_METAS).hasAuthority("ROLE_ADMIN")
                        // Materiais e agendamentos: qualquer login, como sempre foi.
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origensPermitidas));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
