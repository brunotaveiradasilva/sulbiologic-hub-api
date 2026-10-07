package com.almoxarifado.api.campanhawellpet;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ClienteCampanhaWellpetRepository extends JpaRepository<ClienteCampanhaWellpet, String> {

    List<ClienteCampanhaWellpet> findByMes(String mes);

    List<ClienteCampanhaWellpet> findByMesOrderByRepresentanteAscNomeAsc(String mes);

    List<ClienteCampanhaWellpet> findAllByOrderByRepresentanteAscNomeAsc();

    @Transactional
    void deleteByMes(String mes);
}
