package com.asteriskia.domain.insights;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * ScorecardService — CRUD de fichas de avaliação de qualidade (Fase 1 do Quality
 * Management, V38). Editar uma ficha que já tem avaliações cria uma nova versão em vez
 * de sobrescrever a existente (a antiga vira inativa e imutável), preservando o
 * histórico de avaliações já feitas — só uma ficha pode estar ativa por vez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScorecardService {

    private final QualityScorecardRepository scorecardRepository;
    private final ScorecardItemRepository scorecardItemRepository;
    private final CallEvaluationRepository evaluationRepository;

    public record ItemInput(Integer ordem, String pergunta, java.math.BigDecimal peso,
                             Integer notaMaxima, Boolean isCritical) {}

    public List<ScorecardDto> listAll() {
        return scorecardRepository.findAll().stream()
                .map(s -> ScorecardDto.from(s, scorecardItemRepository.findByScorecardIdOrderByOrdemAsc(s.getId())))
                .toList();
    }

    public ScorecardDto getById(Long id) {
        QualityScorecard scorecard = scorecardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ficha não encontrada: id=" + id));
        return ScorecardDto.from(scorecard, scorecardItemRepository.findByScorecardIdOrderByOrdemAsc(id));
    }

    /** Ficha ativa no momento, para consumo interno pelo serviço Python (active-scorecard). */
    public Optional<ScorecardDto> getActive() {
        return scorecardRepository.findByIsActiveTrue()
                .map(s -> ScorecardDto.from(s, scorecardItemRepository.findByScorecardIdOrderByOrdemAsc(s.getId())));
    }

    @Transactional
    public ScorecardDto create(String name, String description, List<ItemInput> items) {
        QualityScorecard scorecard = scorecardRepository.save(QualityScorecard.builder()
                .name(name)
                .description(description)
                .isActive(false)
                .version(1)
                .build());
        List<ScorecardItem> saved = saveItems(scorecard.getId(), items);
        log.info("Ficha de avaliação criada: id={} name={}", scorecard.getId(), name);
        return ScorecardDto.from(scorecard, saved);
    }

    /**
     * Atualiza uma ficha. Se ela já possui alguma avaliação registrada, a edição cria
     * uma nova versão (nova linha, version+1) em vez de alterar a existente — avaliações
     * antigas continuam referenciando a versão exata com que foram feitas.
     */
    @Transactional
    public ScorecardDto update(Long id, String name, String description, List<ItemInput> items) {
        QualityScorecard existing = scorecardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ficha não encontrada: id=" + id));

        boolean hasEvaluations = existingHasEvaluations(id);

        if (!hasEvaluations) {
            existing.setName(name);
            existing.setDescription(description);
            scorecardRepository.save(existing);
            scorecardItemRepository.deleteByScorecardId(id);
            List<ScorecardItem> saved = saveItems(id, items);
            log.info("Ficha de avaliação atualizada in-place: id={}", id);
            return ScorecardDto.from(existing, saved);
        }

        QualityScorecard newVersion = scorecardRepository.save(QualityScorecard.builder()
                .name(name)
                .description(description)
                .isActive(false)
                .version(existing.getVersion() + 1)
                .build());
        List<ScorecardItem> saved = saveItems(newVersion.getId(), items);
        log.info("Ficha de avaliação editada com avaliações existentes — nova versão criada: id={} (anterior id={})",
                newVersion.getId(), id);
        return ScorecardDto.from(newVersion, saved);
    }

    /** Ativa a ficha informada e desativa a que estiver ativa no momento (só uma por vez). */
    @Transactional
    public ScorecardDto activate(Long id) {
        QualityScorecard target = scorecardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ficha não encontrada: id=" + id));
        scorecardRepository.findByIsActiveTrue().ifPresent(current -> {
            if (!current.getId().equals(id)) {
                current.setIsActive(false);
                scorecardRepository.save(current);
            }
        });
        target.setIsActive(true);
        scorecardRepository.save(target);
        log.info("Ficha de avaliação ativada: id={}", id);
        return ScorecardDto.from(target, scorecardItemRepository.findByScorecardIdOrderByOrdemAsc(id));
    }

    @Transactional
    public void deactivate(Long id) {
        QualityScorecard scorecard = scorecardRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ficha não encontrada: id=" + id));
        scorecard.setIsActive(false);
        scorecardRepository.save(scorecard);
    }

    private boolean existingHasEvaluations(Long scorecardId) {
        return evaluationRepository.existsByScorecardId(scorecardId);
    }

    private List<ScorecardItem> saveItems(Long scorecardId, List<ItemInput> items) {
        List<ScorecardItem> entities = items.stream()
                .map(i -> ScorecardItem.builder()
                        .scorecardId(scorecardId)
                        .ordem(i.ordem())
                        .pergunta(i.pergunta())
                        .peso(i.peso())
                        .notaMaxima(i.notaMaxima())
                        .isCritical(Boolean.TRUE.equals(i.isCritical()))
                        .build())
                .toList();
        return scorecardItemRepository.saveAll(entities);
    }
}
