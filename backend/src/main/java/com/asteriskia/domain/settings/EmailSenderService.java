package com.asteriskia.domain.settings;

import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * EmailSenderService — envio de e-mail configurado dinamicamente a partir do {@code .env} atual
 * (nunca {@code application.properties} estático) — mesma tela de Configuração do Sistema
 * (aba "E-mail") usada por Jira/Zabbix/Telegram/AD, aba `CFG-email` do plano
 * modulo-callcenter-omnicanal.plan.md. Preparação de infraestrutura para o agendamento de
 * relatórios (Fase 9c.6) — nenhum fluxo do projeto dispara e-mail real ainda nesta fatia além do
 * teste manual de envio.
 *
 * <p>{@code EMAIL_ENABLED=false} (default) é fail-closed: {@link #isEnabled()} retorna falso e
 * {@link #send} não tenta nada — quem chama deve checar antes (ver {@code CcReportSchedule}
 * futuro, que loga aviso e não falha o agendamento se e-mail estiver desabilitado).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSenderService {

    private final EnvFileStore envFileStore;

    public boolean isEnabled() {
        return "true".equalsIgnoreCase(readRaw().getOrDefault("EMAIL_ENABLED", "false"));
    }

    /** Envia um e-mail simples com anexo binário opcional (relatório exportado, Fase 9c.6). */
    public void send(String to, String subject, String body, byte[] attachment, String attachmentFilename) {
        Map<String, String> env = readRaw();
        JavaMailSenderImpl mailSender = buildMailSender(env);
        String from = env.getOrDefault("SMTP_FROM_ADDRESS", "");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, attachment != null);
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body);
            if (attachment != null) {
                helper.addAttachment(attachmentFilename, new org.springframework.core.io.ByteArrayResource(attachment));
            }
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Falha ao enviar e-mail para {}: {}", to, e.getClass().getSimpleName());
            throw new IllegalStateException("Falha ao enviar e-mail", e);
        }
    }

    /** Testa a conexão SMTP com os valores informados (ainda não salvos no .env) — mesmo padrão
     * de {@code SettingsTestController.testAd}: um relay SMTP corporativo é quase sempre um host
     * da rede interna por definição, então (diferente de Jira/Zabbix) NÃO bloqueia IP privado
     * aqui — bloquear quebraria o caso de uso real. */
    public String testConnection(String host, int port, String username, String password, boolean starttls) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);
        applySmtpProperties(mailSender, starttls);
        try {
            mailSender.testConnection();
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("Falha na conexão SMTP: " + e.getMessage(), e);
        }
        return "Conexão SMTP bem-sucedida com " + host + ":" + port;
    }

    private JavaMailSenderImpl buildMailSender(Map<String, String> env) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(env.getOrDefault("SMTP_HOST", ""));
        mailSender.setPort(parsePort(env.get("SMTP_PORT")));
        mailSender.setUsername(env.getOrDefault("SMTP_USERNAME", ""));
        mailSender.setPassword(env.getOrDefault("SMTP_PASSWORD_CREDENTIAL", ""));
        applySmtpProperties(mailSender, "true".equalsIgnoreCase(env.getOrDefault("SMTP_STARTTLS", "true")));
        return mailSender;
    }

    private void applySmtpProperties(JavaMailSenderImpl mailSender, boolean starttls) {
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
    }

    private int parsePort(String raw) {
        try {
            return raw != null ? Integer.parseInt(raw.trim()) : 587;
        } catch (NumberFormatException e) {
            return 587;
        }
    }

    private Map<String, String> readRaw() {
        try {
            return envFileStore.readRaw();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler configuração de e-mail do .env", e);
        }
    }
}
