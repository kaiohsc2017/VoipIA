package com.asteriskia.domain.ura;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UraService — Lógica de negócio das URAs configuráveis (Módulo 1). */
@Slf4j
@Service
@RequiredArgsConstructor
public class UraService {

    /**
     * Novas URAs devem usar ramal nesta faixa — evita precisar editar o dialplan do Asterisk a cada
     * URA criada.
     */
    private static final int RESERVED_RANGE_START = 2000;

    private static final int RESERVED_RANGE_END = 2999;

    /** Ramal da URA legada (service desk), fora da faixa reservada — grandfathered. */
    private static final String LEGACY_EXTENSION = "1000";

    private final UraRepository repository;
    private final UraSettingsRepository settingsRepository;

    @Transactional(readOnly = true)
    public List<Ura> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Ura findById(Integer id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("URA não encontrada: " + id));
    }

    @Transactional
    public Ura save(Ura ura) {
        validateExtension(ura);
        boolean isNew = ura.getId() == null;
        Ura saved = repository.save(ura);
        if (isNew) {
            seedDefaultSettings(saved.getId());
            log.info(
                    "URA criada: id={} nome={} ramal={}",
                    saved.getId(),
                    saved.getName(),
                    saved.getExtension());
        }
        return saved;
    }

    @Transactional
    public void delete(Integer id) {
        if (id == 1) {
            throw new IllegalArgumentException(
                    "A URA padrão (Service Desk) não pode ser removida.");
        }
        repository.deleteById(id);
    }

    private void validateExtension(Ura ura) {
        String ext = ura.getExtension();
        if (ext == null || ext.isBlank()) {
            throw new IllegalArgumentException("Informe o ramal da URA.");
        }
        if (!ext.equals(LEGACY_EXTENSION)) {
            int num;
            try {
                num = Integer.parseInt(ext.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ramal inválido: " + ext);
            }
            if (num < RESERVED_RANGE_START || num > RESERVED_RANGE_END) {
                throw new IllegalArgumentException(
                        "Novas URAs devem usar um ramal entre "
                                + RESERVED_RANGE_START
                                + " e "
                                + RESERVED_RANGE_END
                                + ".");
            }
        }
        repository
                .findByExtension(ext)
                .ifPresent(
                        existing -> {
                            if (!existing.getId().equals(ura.getId())) {
                                throw new IllegalArgumentException(
                                        "Já existe uma URA usando o ramal " + ext + ".");
                            }
                        });
    }

    /** Cria as 4 mensagens/configurações padrão para uma URA recém-criada. */
    private void seedDefaultSettings(Integer uraId) {
        settingsRepository.saveAll(
                List.of(
                        UraSettings.builder()
                                .uraId(uraId)
                                .key("boas_vindas")
                                .label("Mensagem de boas-vindas")
                                .required(true)
                                .value("Bem-vindo! Como posso te ajudar?")
                                .build(),
                        UraSettings.builder()
                                .uraId(uraId)
                                .key("informativa")
                                .label("Mensagem informativa")
                                .required(false)
                                .value("")
                                .build(),
                        UraSettings.builder()
                                .uraId(uraId)
                                .key("encerramento")
                                .label("Mensagem de encerramento")
                                .required(true)
                                .value(
                                        "Atendimento registrado. Em breve entraremos em contato. Obrigado!")
                                .build(),
                        UraSettings.builder()
                                .uraId(uraId)
                                .key("vad_aggressiveness")
                                .label("Sensibilidade a ruído de fundo (VAD)")
                                .required(true)
                                .value("3")
                                .build()));
    }
}
