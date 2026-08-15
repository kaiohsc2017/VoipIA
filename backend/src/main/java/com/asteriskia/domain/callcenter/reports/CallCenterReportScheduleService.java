package com.asteriskia.domain.callcenter.reports;

import com.asteriskia.domain.callcenter.chat.TelegramApiClient;
import com.asteriskia.domain.settings.EmailSenderService;
import com.asteriskia.domain.settings.EnvFileStore;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CallCenterReportScheduleService — agendamento de exportação periódica do relatório de
 * chamada/chat (sub-fase 9c.6 do plano modulo-callcenter-omnicanal.plan.md). Escopo (fila/agente/
 * período) e destinatário são congelados na criação do agendamento e reavaliados só na execução
 * — mesma disciplina fail-closed já aplicada em {@code CcQualityReport} (achado HIGH real da
 * Fase 26): a execução roda fora da sessão de quem criou.
 *
 * <p>Frequência simples (DAILY/WEEKLY/MONTHLY + hora do dia), não cron completo — o scheduler
 * ({@link CallCenterReportScheduleScheduler}) roda a cada hora e cada agendamento só dispara na
 * hora configurada, no máximo uma vez por dia (guardado por {@code lastRunAt}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallCenterReportScheduleService {

    private static final String TELEGRAM_TOKEN_ENV_KEY = "CALLCENTER_TELEGRAM_BOT_TOKEN";

    private final CcReportScheduleRepository scheduleRepository;
    private final CallCenterReportExportService exportService;
    private final TelegramApiClient telegramApiClient;
    private final EmailSenderService emailSenderService;
    private final EnvFileStore envFileStore;

    public List<CcReportSchedule> list() {
        return scheduleRepository.findAll();
    }

    @Transactional
    public CcReportSchedule create(CcReportSchedule schedule, String createdBy) {
        validate(schedule);
        schedule.setId(null);
        schedule.setCreatedBy(createdBy);
        schedule.setCreatedAt(OffsetDateTime.now());
        schedule.setLastRunAt(null);
        schedule.setLastRunStatus(null);
        return scheduleRepository.save(schedule);
    }

    @Transactional
    public void setActive(Long id, boolean active) {
        CcReportSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Agendamento não encontrado"));
        schedule.setActive(active);
        scheduleRepository.save(schedule);
    }

    @Transactional
    public void delete(Long id) {
        scheduleRepository.deleteById(id);
    }

    private void validate(CcReportSchedule schedule) {
        if (schedule.getName() == null || schedule.getName().isBlank()) {
            throw new IllegalArgumentException("Nome do agendamento é obrigatório.");
        }
        try {
            CcReportSchedule.ReportType.valueOf(schedule.getReportType());
            CcReportSchedule.Frequency freq = CcReportSchedule.Frequency.valueOf(schedule.getFrequency());
            CcReportSchedule.Channel.valueOf(schedule.getChannel());
            if (freq == CcReportSchedule.Frequency.WEEKLY
                    && (schedule.getDayOfWeek() == null || schedule.getDayOfWeek() < 1 || schedule.getDayOfWeek() > 7)) {
                throw new IllegalArgumentException("dayOfWeek (1-7) é obrigatório para frequência WEEKLY.");
            }
            if (freq == CcReportSchedule.Frequency.MONTHLY
                    && (schedule.getDayOfMonth() == null || schedule.getDayOfMonth() < 1 || schedule.getDayOfMonth() > 28)) {
                throw new IllegalArgumentException("dayOfMonth (1-28) é obrigatório para frequência MONTHLY.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("reportType/frequency/channel inválido.");
        }
        if (schedule.getRecipient() == null || schedule.getRecipient().isBlank()) {
            throw new IllegalArgumentException("Destinatário é obrigatório.");
        }
        if (schedule.getHourOfDay() == null || schedule.getHourOfDay() < 0 || schedule.getHourOfDay() > 23) {
            throw new IllegalArgumentException("hourOfDay deve estar entre 0 e 23.");
        }
    }

    /** Roda a cada hora (ver scheduler) — executa todo agendamento ativo cuja hora bate com a
     * hora atual e que ainda não rodou hoje. */
    @Transactional
    public void runDue(LocalDateTime now) {
        for (CcReportSchedule schedule : scheduleRepository.findByActiveTrue()) {
            if (isDue(schedule, now)) {
                execute(schedule, now);
            }
        }
    }

    private boolean isDue(CcReportSchedule schedule, LocalDateTime now) {
        if (schedule.getLastRunAt() != null
                && schedule.getLastRunAt().toLocalDate().equals(now.toLocalDate())) {
            return false;
        }
        if (schedule.getHourOfDay() == null || now.getHour() != schedule.getHourOfDay()) {
            return false;
        }
        CcReportSchedule.Frequency freq = CcReportSchedule.Frequency.valueOf(schedule.getFrequency());
        return switch (freq) {
            case DAILY -> true;
            case WEEKLY -> now.getDayOfWeek().getValue() == schedule.getDayOfWeek();
            case MONTHLY -> now.getDayOfMonth() == schedule.getDayOfMonth();
        };
    }

    private void execute(CcReportSchedule schedule, LocalDateTime now) {
        LocalDate to = now.toLocalDate();
        LocalDate from = to.minusDays(schedule.getPeriodDays());
        byte[] content;
        String filename;
        try {
            content = buildExport(schedule, from, to);
            filename = "relatorio-" + schedule.getReportType().toLowerCase().replace('_', '-')
                    + "-" + to + extensionFor(schedule.getReportType());
        } catch (Exception e) {
            log.warn("Falha ao gerar relatório do agendamento {} ({}): {}",
                    schedule.getId(), schedule.getName(), e.getClass().getSimpleName());
            markResult(schedule, now, "FAILED");
            return;
        }

        boolean delivered = deliver(schedule, content, filename);
        markResult(schedule, now, delivered ? "OK" : "FAILED");
    }

    private byte[] buildExport(CcReportSchedule schedule, LocalDate from, LocalDate to) {
        Long queueId = schedule.getQueue() != null ? schedule.getQueue().getId() : null;
        Long agentId = schedule.getAgent() != null ? schedule.getAgent().getId() : null;
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);
        CcReportSchedule.ReportType type = CcReportSchedule.ReportType.valueOf(schedule.getReportType());
        return switch (type) {
            case CALLS_EXCEL -> exportService.exportCallsExcel(
                    new CallReportFilter(fromDt, toDt, queueId, agentId, null, null, null, null, null, null, null));
            case CALLS_PDF -> exportService.exportCallsPdf(
                    new CallReportFilter(fromDt, toDt, queueId, agentId, null, null, null, null, null, null, null));
            case CHATS_EXCEL -> exportService.exportChatsExcel(new ChatReportFilter(fromDt, toDt, queueId, agentId));
            case CHATS_PDF -> exportService.exportChatsPdf(new ChatReportFilter(fromDt, toDt, queueId, agentId));
        };
    }

    private String extensionFor(String reportType) {
        return reportType.endsWith("PDF") ? ".pdf" : ".xlsx";
    }

    /** Entrega fail-closed: e-mail só dispara se {@code EMAIL_ENABLED=true} — senão loga aviso e
     * retorna falso, sem lançar exceção (não deve derrubar o scheduler nem afetar outros
     * agendamentos, mesma disciplina de {@code TelegramLongPollingClient}/schedulers do domínio). */
    private boolean deliver(CcReportSchedule schedule, byte[] content, String filename) {
        CcReportSchedule.Channel channel = CcReportSchedule.Channel.valueOf(schedule.getChannel());
        if (channel == CcReportSchedule.Channel.telegram) {
            String token = readTelegramToken();
            if (token == null || token.isBlank()) {
                log.warn("Agendamento {} usa canal Telegram mas CALLCENTER_TELEGRAM_BOT_TOKEN não está configurado — pulando.",
                        schedule.getId());
                return false;
            }
            return telegramApiClient.sendDocument(token, schedule.getRecipient(), content, filename);
        }
        if (!emailSenderService.isEnabled()) {
            log.warn("Agendamento {} usa canal e-mail mas EMAIL_ENABLED está desligado — pulando (configure em Sistema > Configuração > E-mail).",
                    schedule.getId());
            return false;
        }
        try {
            emailSenderService.send(schedule.getRecipient(), "Relatório agendado: " + schedule.getName(),
                    "Segue em anexo o relatório agendado \"" + schedule.getName() + "\".", content, filename);
            return true;
        } catch (Exception e) {
            log.warn("Falha ao enviar e-mail do agendamento {}: {}", schedule.getId(), e.getClass().getSimpleName());
            return false;
        }
    }

    private String readTelegramToken() {
        try {
            return envFileStore.readRaw().get(TELEGRAM_TOKEN_ENV_KEY);
        } catch (IOException e) {
            return null;
        }
    }

    private void markResult(CcReportSchedule schedule, LocalDateTime now, String status) {
        schedule.setLastRunAt(now.atOffset(OffsetDateTime.now().getOffset()));
        schedule.setLastRunStatus(status);
        scheduleRepository.save(schedule);
    }
}
