package com.asteriskia.domain.auth.sso;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.asteriskia.config.JwtService;
import com.asteriskia.domain.user.AppUser;
import com.asteriskia.domain.user.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SsoServiceTest {

    @Mock
    private SsoConfigurationRepository ssoConfigRepository;

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SsoService ssoService;

    @BeforeEach
    void setUp() {
        ssoService = new SsoService(ssoConfigRepository, userRepository, jwtService, passwordEncoder);
    }

    @Test
    void testGetPublicConfigActive() {
        SsoConfiguration config = SsoConfiguration.builder()
                .providerName("MICROSOFT_ENTRA")
                .displayName("Microsoft 365 / Entra ID")
                .clientId("test-client-id")
                .tenantId("test-tenant")
                .isActive(true)
                .build();

        when(ssoConfigRepository.findByProviderNameIgnoreCase("MICROSOFT_ENTRA")).thenReturn(Optional.of(config));

        var publicConfig = ssoService.getPublicConfig();

        assertTrue(publicConfig.enabled());
        assertEquals("Microsoft 365 / Entra ID", publicConfig.displayName());
        assertTrue(publicConfig.authorizationUrl().contains("test-client-id"));
    }

    @Test
    void testProcessSsoLoginExistingUser() {
        AppUser user = AppUser.builder()
                .id(10)
                .username("carlos.silva@empresa.com.br")
                .displayName("Carlos Silva")
                .extension(9001)
                .isActive(true)
                .build();

        when(userRepository.findByUsernameIgnoreCase("carlos.silva@empresa.com.br")).thenReturn(Optional.of(user));
        when(jwtService.generateToken("carlos.silva@empresa.com.br")).thenReturn("jwt.token.123");

        var response = ssoService.processSsoLogin("carlos.silva@empresa.com.br", "Carlos Silva");

        assertNotNull(response);
        assertEquals("jwt.token.123", response.token());
        assertEquals(9001, response.extension());
        assertEquals("carlos.silva@empresa.com.br", response.username());
    }

    @Test
    void testProcessSsoLoginNewUserAutoProvision() {
        when(userRepository.findByUsernameIgnoreCase("novo.usuario@empresa.com.br")).thenReturn(Optional.empty());
        when(userRepository.findNextExtension(9010)).thenReturn(9015);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed_sso_pwd");
        when(userRepository.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            u.setId(25);
            return u;
        });
        when(jwtService.generateToken("novo.usuario@empresa.com.br")).thenReturn("jwt.new.user");

        var response = ssoService.processSsoLogin("novo.usuario@empresa.com.br", "Novo Usuario");

        assertNotNull(response);
        assertEquals("jwt.new.user", response.token());
        assertEquals(9015, response.extension());
        assertEquals(25, response.id());
    }
}
