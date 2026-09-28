package com.almoxarifado.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.almoxarifado.api.especialistapet.ClienteEspecialistaPet;
import com.almoxarifado.api.especialistapet.ClienteEspecialistaPetRepository;
import com.almoxarifado.api.metarepresentante.Mes;
import com.almoxarifado.api.metarepresentante.TotalVendidoMensal;
import com.almoxarifado.api.metarepresentante.TotalVendidoMensalRepository;
import com.almoxarifado.api.representante.Representante;
import com.almoxarifado.api.representante.RepresentanteRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Supervisor e representante só consultam; o representante só enxerga o que é dele. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:perfisdeacesso;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PerfisDeAcessoTests {

    static final String MES = Mes.atual().toString();

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UsuarioRepository usuarios;
    @Autowired RepresentanteRepository representantes;
    @Autowired TotalVendidoMensalRepository totais;
    @Autowired ClienteEspecialistaPetRepository especialistaPet;

    Representante marina;
    Representante joao;

    @BeforeEach
    void preparar() {
        especialistaPet.deleteAll();
        totais.deleteAll();
        usuarios.deleteAll();
        representantes.deleteAll();

        marina = representante("Marina Álves");
        joao = representante("João Souza");
        usuario("admin", Role.ADMIN, null);
        usuario("renata", Role.SUPERVISOR, null);
        usuario("marina", Role.REPRESENTANTE, marina.getId());

        totais.save(total(marina, 1000));
        totais.save(total(joao, 2000));
        especialistaPet.save(cliente("1", "  MARINA ALVES ")); // mesmo nome, com outro acento/espaço
        especialistaPet.save(cliente("2", "JOAO SOUZA"));
    }

    @Test
    void representanteSoEnxergaOQueEDele() throws Exception {
        mvc.perform(como("marina", get("/api/representantes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(marina.getId()));
        mvc.perform(como("marina", get("/api/metas-representante/totais-vendidos")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].representanteId").value(marina.getId()));
        mvc.perform(como("marina", get("/api/especialista-pet").param("mes", MES)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].codigoCliente").value("1"));
    }

    @Test
    void supervisorEnxergaTodos() throws Exception {
        mvc.perform(como("renata", get("/api/representantes"))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(como("renata", get("/api/metas-representante/totais-vendidos"))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(como("renata", get("/api/especialista-pet"))).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void soAdminEditaSincronizaEVeDadosEUsuarios() throws Exception {
        for (String login : new String[] { "renata", "marina" }) {
            mvc.perform(como(login, post("/api/metas-representante/sincronizar"))).andExpect(status().isForbidden());
            mvc.perform(como(login, post("/api/especialista-pet/sincronizar"))).andExpect(status().isForbidden());
            mvc.perform(como(login, delete("/api/representantes/" + joao.getId()))).andExpect(status().isForbidden());
            mvc.perform(como(login, get("/api/dados/vendas"))).andExpect(status().isForbidden());
            mvc.perform(como(login, get("/api/auth/usuarios"))).andExpect(status().isForbidden());
        }
        assertThat(representantes.existsById(joao.getId())).isTrue();
    }

    @Test
    void representanteSemVinculoNaoEnxergaNada() throws Exception {
        usuario("solto", Role.REPRESENTANTE, null);
        mvc.perform(como("solto", get("/api/representantes"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(como("solto", get("/api/especialista-pet"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void adminCriaLoginDeRepresentanteSoComORepresentante() throws Exception {
        mvc.perform(como("admin", post("/api/auth/usuarios")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"joao\",\"senha\":\"1234\",\"role\":\"REPRESENTANTE\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(como("admin", post("/api/auth/usuarios")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"joao\",\"senha\":\"1234\",\"role\":\"REPRESENTANTE\",\"representanteId\":\""
                        + joao.getId() + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(como("admin", get("/api/auth/usuarios")))
                .andExpect(jsonPath("$[?(@.usuario == 'joao')].representanteNome").value("João Souza"));
    }

    @Test
    void guardaDadosPessoaisEAdminEditaPerfilESenha() throws Exception {
        mvc.perform(como("admin", post("/api/auth/usuarios")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"usuario\":\"tatiane\",\"senha\":\"1234\",\"role\":\"SUPERVISOR\",\"nome\":\" Tatiane \","
                        + "\"sobrenome\":\"Santana\",\"email\":\"tati@exemplo.com\",\"dataNascimento\":\"1990-05-17\"}"))
                .andExpect(status().isCreated());
        mvc.perform(como("admin", get("/api/auth/usuarios")))
                .andExpect(jsonPath("$[?(@.usuario == 'tatiane')].nome").value("Tatiane"))
                .andExpect(jsonPath("$[?(@.usuario == 'tatiane')].dataNascimento").value("1990-05-17"));

        mvc.perform(como("admin", put("/api/auth/usuarios/tatiane")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"REPRESENTANTE\",\"representanteId\":\"" + joao.getId() + "\",\"nome\":\"Tatiane\","
                        + "\"sobrenome\":\"Santana\",\"email\":\"\",\"novaSenha\":\"nova-senha\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REPRESENTANTE"))
                .andExpect(jsonPath("$.representanteNome").value("João Souza"))
                .andExpect(jsonPath("$.email").doesNotExist());

        Usuario editado = usuarios.findByUsuarioIgnoreCase("tatiane").orElseThrow();
        assertThat(passwordEncoder.matches("nova-senha", editado.getSenhaHash())).isTrue();
        assertThat(editado.getDataNascimento()).isNull();

        mvc.perform(como("admin", put("/api/auth/usuarios/tatiane")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SUPERVISOR\",\"email\":\"sem-arroba\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(como("renata", put("/api/auth/usuarios/tatiane")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void naoRebaixaOUltimoAdmin() throws Exception {
        mvc.perform(como("admin", put("/api/auth/usuarios/admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SUPERVISOR\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void naoExcluiOUltimoAdmin() throws Exception {
        mvc.perform(como("admin", delete("/api/auth/usuarios/admin"))).andExpect(status().isConflict());
        mvc.perform(como("admin", delete("/api/auth/usuarios/renata"))).andExpect(status().isNoContent());
    }

    MockHttpServletRequestBuilder como(String login, MockHttpServletRequestBuilder req) {
        Usuario u = usuarios.findByUsuarioIgnoreCase(login).orElseThrow();
        return req.header("Authorization", "Bearer " + jwt.gerar(u.getUsuario(), u.getRole()));
    }

    Representante representante(String nome) {
        Representante r = new Representante();
        r.setNome(nome);
        return representantes.save(r);
    }

    void usuario(String login, Role role, String representanteId) {
        Usuario u = new Usuario();
        u.setUsuario(login);
        u.setSenhaHash(passwordEncoder.encode("1234"));
        u.setRole(role);
        u.setRepresentanteId(representanteId);
        usuarios.save(u);
    }

    static TotalVendidoMensal total(Representante r, double total) {
        TotalVendidoMensal t = new TotalVendidoMensal(r.getId(), MES);
        t.setTotal(total);
        return t;
    }

    static ClienteEspecialistaPet cliente(String codigo, String vendedor) {
        ClienteEspecialistaPet c = new ClienteEspecialistaPet();
        c.setMes(MES);
        c.setCodigoCliente(codigo);
        c.setNome("Cliente " + codigo);
        c.setRepresentante(vendedor);
        c.setClassificacao("NUMERICA");
        return c;
    }
}
