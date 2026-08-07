package com.asteriskia.domain.callcenter.flow;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcFlowVersionRepository extends JpaRepository<CcFlowVersion, Long> {

    List<CcFlowVersion> findByFlowIdOrderByVersionNumberDesc(Long flowId);

    Optional<CcFlowVersion> findByFlowIdAndStatus(Long flowId, FlowStatus status);

    Optional<CcFlowVersion> findTopByFlowIdOrderByVersionNumberDesc(Long flowId);
}
