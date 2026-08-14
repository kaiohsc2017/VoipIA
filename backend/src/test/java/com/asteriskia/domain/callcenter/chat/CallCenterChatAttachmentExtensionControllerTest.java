package com.asteriskia.domain.callcenter.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CallCenterChatAttachmentExtensionControllerTest {

    @Mock private CcChatAttachmentExtensionRepository repository;

    private CallCenterChatAttachmentExtensionController controller() {
        return new CallCenterChatAttachmentExtensionController(repository);
    }

    @Test
    void create_normalizesLeadingDotAndLowercases() {
        when(repository.existsByExtensionIgnoreCase("pdf")).thenReturn(false);
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        var response = controller().create(new CallCenterChatAttachmentExtensionController.ExtensionRequest(".PDF", null));

        assertThat(response.getBody().getExtension()).isEqualTo("pdf");
    }

    @Test
    void create_invalidExtension_throws() {
        assertThatThrownBy(() -> controller().create(new CallCenterChatAttachmentExtensionController.ExtensionRequest("a b!", null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void create_duplicate_throwsConflict() {
        when(repository.existsByExtensionIgnoreCase("png")).thenReturn(true);

        assertThatThrownBy(() -> controller().create(new CallCenterChatAttachmentExtensionController.ExtensionRequest("png", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("já está cadastrada");
    }
}
