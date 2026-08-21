package com.asteriskia.domain.auth.sso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Re-cifra no boot qualquer {@code sso_configurations.client_secret} que ainda esteja em
 * texto puro (legado, gravado antes de {@link EncryptedSecretConverter} existir) — sem isso,
 * um registro antigo só seria re-cifrado no próximo `PUT /admin/config` feito por um admin,
 * o que pode nunca acontecer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SsoSecretReencryptionRunner implements ApplicationRunner {

    private final SsoConfigurationRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        var ids = repository.findIdsWithPlaintextClientSecret();
        if (ids.isEmpty()) {
            return;
        }
        for (var id : ids) {
            // getClientSecret() já passou pelo converter: como o valor bruto não tinha o
            // prefixo "enc:v1:", ele volta aqui como o texto puro original; save() cifra.
            repository.findById(id).ifPresent(repository::save);
        }
        log.info("SSO: {} configuração(ões) com client_secret legado re-cifrada(s) no boot.", ids.size());
    }
}
