package com.asteriskia.domain.callcenter.cobrowsing;

import com.asteriskia.domain.masterdata.BusinessUnitContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterCobrowsingService — listagem/reprodução/eliminação sob demanda das sessões de
 * co-browsing gravado do chat (Fase 17, sub-fase 17c). Reusa exatamente a disciplina já validada
 * em {@code CallCenterRecordingService}/{@code CobrowseIngestService}: 404 nunca 403, escopo de
 * BU aplicado na consulta, defesa de path traversal por canonicalização contra o diretório base.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterCobrowsingService {

    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final CcCobrowseSessionRepository cobrowseSessionRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.callcenter.chat-transcript-path:/opt/AsteriskIA/media/chat}")
    private String basePath;

    @Transactional(readOnly = true)
    public Page<CcCobrowseSession> list(Pageable pageable) {
        Specification<CcCobrowseSession> spec = Specification.where(null);
        if (BusinessUnitContext.isRestricted()) {
            spec =
                    spec.and(
                            CcCobrowseSessionSpecifications.restrictedToBusinessUnits(
                                    BusinessUnitContext.currentBusinessUnitIds()));
        }
        return cobrowseSessionRepository.findAll(spec, pageable);
    }

    /**
     * Busca já aplicando o escopo de BU — mesmo padrão de
     * {@code CallCenterRecordingService#findByIdInScope}: vazio (nunca lança) tanto para id
     * inexistente quanto para sessão fora do escopo do usuário, para o controller devolver 404 nos
     * dois casos sem revelar qual deles ocorreu.
     */
    @Transactional(readOnly = true)
    public Optional<CcCobrowseSession> findByIdInScope(Long id) {
        return cobrowseSessionRepository
                .findById(id)
                .filter(
                        session ->
                                !BusinessUnitContext.isRestricted()
                                        || session.getBusinessUnitId() == null
                                        || BusinessUnitContext.currentBusinessUnitIds()
                                                .contains(session.getBusinessUnitId().intValue()));
    }

    /**
     * Lê o conteúdo de {@code file_path} e devolve os eventos (JSONL descomprimido) já
     * desserializados — nunca expõe o gzip cru ao frontend, mantém a decisão de formato (JSON
     * puro, mais simples de consumir no player) inteiramente no backend. Retorna {@code null}
     * (nunca lança) para qualquer falha de leitura/parse — o controller trata isso como 404, no
     * mesmo espírito de {@code CallCenterRecordingController#audio}.
     */
    public List<JsonNode> readEvents(CcCobrowseSession session) {
        Path file = resolveEventsFile(session);
        if (file == null || !Files.exists(file) || !Files.isReadable(file)) {
            return null;
        }
        List<JsonNode> events = new ArrayList<>();
        try (var in = new GZIPInputStream(Files.newInputStream(file));
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(objectMapper.readTree(line));
            }
        } catch (IOException e) {
            log.warn(
                    "Erro ao ler eventos de co-browsing id={}: {}", session.getId(), e.getMessage());
            return null;
        }
        return events;
    }

    /**
     * Eliminação sob demanda (§5.5 do plano, antecipada aqui por ser um endpoint simples de
     * expor junto do CRUD administrativo — sem o scheduler de retenção automática, que fica para
     * 17d): apaga o arquivo físico se existir e marca {@code purged_at}, mas **nunca** apaga a
     * linha do banco (mesmo padrão de {@code CallCenterRecordingRetentionService}). Idempotente —
     * chamar duas vezes não lança nem sobrescreve um {@code purged_at} já registrado.
     */
    @Transactional
    public CcCobrowseSession purge(CcCobrowseSession session) {
        if (session.getPurgedAt() != null) {
            return session;
        }
        Path file = resolveEventsFile(session);
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn(
                        "Erro ao apagar arquivo de co-browsing id={}: {} — linha preservada, purgedAt marcado mesmo assim",
                        session.getId(),
                        e.getMessage());
            }
        }
        session.setPurgedAt(LocalDateTime.now());
        return cobrowseSessionRepository.save(session);
    }

    /**
     * Resolve o arquivo físico com defesa de path traversal: reconstrói
     * {@code media/chat/YYYY/MM/DD/<chatSessionId>.events.jsonl.gz} a partir de campos confiáveis
     * do banco ({@code startedAt}, {@code chatSessionId}) — nunca usa {@code filePath} persistido
     * cru para montar o caminho de leitura, mesmo padrão de
     * {@code CallCenterRecordingService#resolveAudioFile}/{@code CobrowseIngestService#resolveEventsFile}.
     */
    private Path resolveEventsFile(CcCobrowseSession session) {
        try {
            Path base = Path.of(basePath).toAbsolutePath().normalize();
            Path dir = base.resolve(DIR_FORMAT.format(session.getStartedAt()));
            Path file = dir.resolve(session.getChatSessionId() + ".events.jsonl.gz").normalize();
            if (!file.startsWith(base)) {
                log.warn(
                        "Caminho de co-browsing fora da raiz de mídia esperada: sessionId={}",
                        session.getId());
                return null;
            }
            return file;
        } catch (RuntimeException e) {
            log.warn(
                    "Erro ao resolver caminho de co-browsing id={}: {}", session.getId(), e.getMessage());
            return null;
        }
    }
}
