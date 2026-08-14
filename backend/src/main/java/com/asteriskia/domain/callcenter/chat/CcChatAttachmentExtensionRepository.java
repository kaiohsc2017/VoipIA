package com.asteriskia.domain.callcenter.chat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CcChatAttachmentExtensionRepository extends JpaRepository<CcChatAttachmentExtension, Long> {

    List<CcChatAttachmentExtension> findAllByOrderByExtensionAsc();

    Optional<CcChatAttachmentExtension> findByExtensionIgnoreCaseAndActiveTrue(String extension);

    boolean existsByExtensionIgnoreCase(String extension);
}
