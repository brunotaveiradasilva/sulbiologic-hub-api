package com.almoxarifado.api.auth;

/**
 * Nível de acesso de um login.
 *
 * <ul>
 *   <li>ADMIN: gerencia tudo — usuários, cadastros, metas, campanhas e dados.</li>
 *   <li>SUPERVISOR: consulta as metas e campanhas de todos os representantes, sem editar.</li>
 *   <li>REPRESENTANTE: consulta só as próprias metas e campanhas, sem editar.</li>
 *   <li>USUARIO: só o almoxarifado (materiais e agendamentos).</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    SUPERVISOR,
    REPRESENTANTE,
    USUARIO
}
