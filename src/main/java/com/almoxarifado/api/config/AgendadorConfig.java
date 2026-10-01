package com.almoxarifado.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga os jobs diários (@Scheduled) que sincronizam o realizado com a ADS (ver
 * ads/AdsSincronizacaoService e ads/AdsEspecialistaPetService). Num servidor sempre ligado (docker
 * compose, Railway) fica ligado. No Cloud Run a instância desliga sem acesso e o @Scheduled não
 * dispara: lá AGENDADOR_INTERNO=false, e quem chama a sincronização é o Cloud Scheduler (ver
 * ads/SincronizacaoDiariaController).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.agendador-interno", havingValue = "true", matchIfMissing = true)
public class AgendadorConfig {
}
