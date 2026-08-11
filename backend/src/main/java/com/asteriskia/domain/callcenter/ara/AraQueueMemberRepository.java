package com.asteriskia.domain.callcenter.ara;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AraQueueMemberRepository extends JpaRepository<AraQueueMember, Long> {
    List<AraQueueMember> findByQueueName(String queueName);

    Optional<AraQueueMember> findByQueueNameAndInterfaceName(String queueName, String interfaceName);

    void deleteByQueueNameAndInterfaceName(String queueName, String interfaceName);

    void deleteByInterfaceName(String interfaceName);
}
