package com.almoxarifado.api.auth;

import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.PutMapping;
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
                .map(u -> resumo(u, nomes))
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

        Usuario novo = new Usuario();
        novo.setUsuario(corpo.usuario().trim());
        novo.setSenhaHash(passwordEncoder.encode(corpo.senha()));
        definirPerfil(novo, corpo.role(), corpo.representanteId());
        definirDadosPessoais(novo, corpo.nome(), corpo.sobrenome(), corpo.email(), corpo.dataNascimento());
        usuarios.save(novo);
    }

    /** Edita perfil, dados pessoais e, se vier novaSenha, redefine a senha. Nunca rebaixa o último ADMIN. */
    @PutMapping("/usuarios/{usuario}")
    public UsuarioResumo atualizarUsuario(@PathVariable String usuario, @Valid @RequestBody AtualizarUsuarioRequest corpo) {
        Usuario alvo = usuarios.findByUsuarioIgnoreCase(usuario)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário " + usuario + " não encontrado"));

        Role novoRole = corpo.role() == null ? alvo.getRole() : corpo.role();
        if (alvo.getRole() == Role.ADMIN && novoRole != Role.ADMIN && usuarios.countByRole(Role.ADMIN) <= 1) {
            throw new UltimoUsuarioException("Esse é o único administrador: não dá pra trocar o perfil dele");
        }
        if (corpo.novaSenha() != null && !corpo.novaSenha().isBlank()) {
            if (corpo.novaSenha().length() < 4) {
                throw new UsuarioInvalidoException("A senha precisa ter pelo menos 4 caracteres");
            }
            alvo.setSenhaHash(passwordEncoder.encode(corpo.novaSenha()));
        }

        definirPerfil(alvo, novoRole, corpo.representanteId());
        definirDadosPessoais(alvo, corpo.nome(), corpo.sobrenome(), corpo.email(), corpo.dataNascimento());
        return resumo(usuarios.save(alvo), null);
    }

    /** role vazio = USUARIO. Só REPRESENTANTE guarda representante — e é obrigatório pra ele. */
    private void definirPerfil(Usuario usuario, Role role, String representanteId) {
        Role perfil = role == null ? Role.USUARIO : role;
        String vinculo = null;
        if (perfil == Role.REPRESENTANTE) {
            if (representanteId == null || representanteId.isBlank()) {
                throw new UsuarioInvalidoException("Escolha o representante desse login");
            }
            vinculo = representantes.findById(representanteId)
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Representante não encontrado"))
                    .getId();
        }
        usuario.setRole(perfil);
        usuario.setRepresentanteId(vinculo);
    }

    private static void definirDadosPessoais(Usuario usuario, String nome, String sobrenome, String email, LocalDate nascimento) {
        usuario.setNome(limpar(nome));
        usuario.setSobrenome(limpar(sobrenome));
        usuario.setEmail(limpar(email));
        usuario.setDataNascimento(nascimento);
    }

    /** Texto em branco vira null, pra não guardar "" no banco. */
    private static String limpar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    /** nomesRepresentantes null = busca só o do próprio login. */
    private UsuarioResumo resumo(Usuario u, Map<String, String> nomesRepresentantes) {
        String representanteNome = null;
        if (u.getRepresentanteId() != null) {
            representanteNome = nomesRepresentantes != null
                    ? nomesRepresentantes.get(u.getRepresentanteId())
                    : representantes.findById(u.getRepresentanteId()).map(Representante::getNome).orElse(null);
        }
        return new UsuarioResumo(u.getUsuario(), u.getRole(), u.getRepresentanteId(), representanteNome,
                u.getNome(), u.getSobrenome(), u.getEmail(), u.getDataNascimento());
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
