package com.almoxarifado.api.ads;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** O Cloud Scheduler entra com o X-Cron-Token, sem login; qualquer outro token é barrado. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sincronizacaodiaria;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.agendador-interno=false",
        "app.cron-token=segredo-do-scheduler"
})
class SincronizacaoDiariaTests {

    static final String ROTA = "/api/interno/sincronizacao-diaria";

    @Autowired MockMvc mvc;
    @MockBean AdsSincronizacaoService metas;
    @MockBean AdsEspecialistaPetService especialistaPet;
    @MockBean AdsCampanhaWellpetService campanhaWellpet;

    @Test
    void comOTokenCertoSincronizaMetasECampanhas() throws Exception {
        mvc.perform(post(ROTA).header("X-Cron-Token", "segredo-do-scheduler"))
                .andExpect(status().isNoContent());
        verify(metas).sincronizarAgendado();
        verify(especialistaPet).sincronizarAgendado();
        verify(campanhaWellpet).sincronizarAgendado();
    }

    @Test
    void semTokenOuComTokenErradoNaoSincroniza() throws Exception {
        mvc.perform(post(ROTA)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROTA).header("X-Cron-Token", "chute")).andExpect(status().isUnauthorized());
        verify(metas, never()).sincronizarAgendado();
        verify(especialistaPet, never()).sincronizarAgendado();
        verify(campanhaWellpet, never()).sincronizarAgendado();
    }
}
