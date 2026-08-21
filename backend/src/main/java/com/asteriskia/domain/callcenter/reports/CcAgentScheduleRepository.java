package com.asteriskia.domain.callcenter.reports;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcAgentScheduleRepository extends JpaRepository<CcAgentSchedule, Long> {
    List<CcAgentSchedule> findByAgentIdAndActiveTrue(Long agentId);

    List<CcAgentSchedule> findByAgentIdAndDayOfWeekAndActiveTrue(Long agentId, Integer dayOfWeek);

    /** Busca em lote (achado de auditoria — WfmTab fazia 1 requisição por agente via
     * Promise.all para montar o painel de escalas da equipe). */
    List<CcAgentSchedule> findByAgentIdInAndActiveTrue(Collection<Long> agentIds);
}
