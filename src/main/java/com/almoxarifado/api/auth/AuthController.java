package com.almoxarifado.api.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.almoxarifado.api.common.RecursoNaoEncontradoException;
import com.almoxarifado.api.representante.Representante;
import com.almoxarifado.api.representante.RepresentanteRepository;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RepresentanteRepository representantes;

    public AuthController(
            UsuarioRepository usuarios,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RepresentanteRepository representantes) {
        this.usuarios = usuarios;
        this.representantes = representantes;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Único endpoint aberto: todo o resto da API exige o token que sai daqui. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest corpo) {
        Usuario usuario = usuarios.findByUsuarioIgnoreCase(corpo.usuario())
                .orElseThrow(() -> new CredenciaisInvalidasException("Usuário ou senha inválidos"));

        if (!passwordEncoder.matches(corpo.senha(), usuario.getSenhaHash())) {
            throw new CredenciaisInvalidasException("Usuário ou senha inválidos");
        }

        return new LoginResponse(
                jwtService.gerar(usuario.getUsuario(), usuario.getRole()),
                usuario.getUsuario(),
                usuario.getRole(),
                usuario.getAvatar());
    }

    /** Todos os logins com perfil e representante (nunca as senhas/hashes). Só admin (ver SecurityConfig). */
    @GetMapping("/usuarios")
    public List<UsuarioResumo> listarUsuarios() {
        Map<String, String> nomes = representantes.findAll().stream()
                .collect(Collectors.toMap(Representante::getId, Representante::getNome));
        return usuarios.findAll().stream()
                .sorted(Comparator.comparing(Usuario::getUsuario, String.CASE_INSENSITIVE_ORDER))
                .map(u -> new UsuarioResumo(
                        u.getUsuario(),
                        u.getRole(),
                        u.getRepresentanteId(),
                        u.getRepresentanteId() == null ? null : nomes.get(u.getRepresentanteId())))
                .toList();
    }

    /**
     * Cria outro login, com o perfil escolhido. REPRESENTANTE precisa dizer qual representante é —
     * é por ele que a API filtra o que esse login enxerga.
     */
    @PostMapping("/usuarios")
    @ResponseStatus(HttpStatus.CREATED)
    public void criarUsuario(@Valid @RequestBody CriarUsuarioRequest corpo) {
        usuarios.findByUsuarioIgnoreCase(corpo.usuario().trim()).ifPresent(u -> {
            throw new UsuarioJaExisteException("Já existe um usuário com esse nome");
        });

        Role role = corpo.role() == null ? Role.USUARIO : corpo.role();
        String representanteId = null;
        if (role == Role.REPRESENTANTE) {
            if (corpo.representanteId() == null || corpo.representanteId().isBlank()) {
                throw new UsuarioInvalidoException("Escolha o representante desse login");
            }
            representanteId = representantes.findById(corpo.representanteId())
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Representante não encontrado"))
                    .getId();
        }

        Usuario novo = new Usuario();
        novo.setUsuario(corpo.usuario().trim());
        novo.setSenhaHash(passwordEncoder.encode(corpo.senha()));
        novo.setRole(role);
        novo.setRepresentanteId(representanteId);
        usuarios.save(novo);
    }

    /** Exclui um login. Nunca o último ADMIN — senão ninguém mais consegue gerenciar o sistema. */
    @DeleteMapping("/usuarios/{usuario}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluirUsuario(@PathVariable String usuario) {
        Usuario alvo = usuarios.findByUsuarioIgnoreCase(usuario)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário " + usuario + " não encontrado"));

        if (alvo.getRole() == Role.ADMIN && usuarios.countByRole(Role.ADMIN) <= 1) {
            throw new UltimoUsuarioException("Não dá para excluir o único administrador");
        }

        usuarios.delete(alvo);
    }

    /** Troca a própria senha. É assim que se recupera de uma senha gerada automaticamente. */
    @PatchMapping("/senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trocarSenha(Authentication authentication, @Valid @RequestBody TrocarSenhaRequest corpo) {
        Usuario usuario = usuarios.findByUsuarioIgnoreCase(authentication.getName())
                .orElseThrow(() -> new CredenciaisInvalidasException("Usuário não encontrado"));

        if (!passwordEncoder.matches(corpo.senhaAtual(), usuario.getSenhaHash())) {
            throw new CredenciaisInvalidasException("Senha atual incorreta");
        }

        usuario.setSenhaHash(passwordEncoder.encode(corpo.novaSenha()));
        usuarios.save(usuario);
    }

    /** Troca a própria foto de perfil. Manda avatar em branco/nulo pra remover. */
    @PatchMapping("/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void atualizarAvatar(Authentication authentication, @Valid @RequestBody AtualizarAvatarRequest corpo) {
        Usuario usuario = usuarios.findByUsuarioIgnoreCase(authentication.getName())
                .orElseThrow(() -> new CredenciaisInvalidasException("Usuário não encontrado"));

        usuario.setAvatar(corpo.avatar() == null || corpo.avatar().isBlank() ? null : corpo.avatar());
        usuarios.save(usuario);
    }
}
