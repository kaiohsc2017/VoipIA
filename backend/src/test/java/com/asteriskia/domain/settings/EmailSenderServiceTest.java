package com.asteriskia.domain.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cobre a regra de negócio de {@link EmailSenderService} (CFG-email): fail-closed quando
 * EMAIL_ENABLED não é explicitamente "true".
 */
@ExtendWith(MockitoExtension.class)
class EmailSenderServiceTest {

    @Mock
    private EnvFileStore envFileStore;

    @Test
    void isEnabled_falseByDefault_whenKeyMissing() throws IOException {
        when(envFileStore.readRaw()).thenReturn(Map.of());
        EmailSenderService service = new EmailSenderService(envFileStore);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseWhenExplicitlyDisabled() throws IOException {
        when(envFileStore.readRaw()).thenReturn(Map.of("EMAIL_ENABLED", "false"));
        EmailSenderService service = new EmailSenderService(envFileStore);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void isEnabled_trueWhenExplicitlyEnabled() throws IOException {
        when(envFileStore.readRaw()).thenReturn(Map.of("EMAIL_ENABLED", "true"));
        EmailSenderService service = new EmailSenderService(envFileStore);
        assertThat(service.isEnabled()).isTrue();
    }
}
