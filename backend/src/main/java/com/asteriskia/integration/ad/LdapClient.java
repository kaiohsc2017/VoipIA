package com.asteriskia.integration.ad;

import com.asteriskia.domain.config.ConfigService;
import java.util.List;
import java.util.Optional;
import javax.naming.directory.Attributes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Component;

/**
 * LdapClient — bind de autenticação e busca de atributos contra o Active Directory.
 *
 * <p>Nunca loga a senha (nem a do usuário, nem a da conta de serviço) — item de segurança explícito
 * do plano do módulo Call Center. Timeouts curtos (ver {@link LdapTemplateFactory}) para que um DC
 * fora do ar não trave o login local de quem não depende do AD.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LdapClient {

    private final ConfigService config;
    private final LdapTemplateFactory templateFactory;

    private static final AttributesMapper<LdapUserAttributes> ATTRIBUTES_MAPPER =
            LdapClient::mapAttributes;

    public AdLdapConfig currentConfig() {
        return new AdLdapConfig(
                config.get("AD_LDAP_ENABLED", "false").equalsIgnoreCase("true"),
                config.get("AD_LDAP_HOST", ""),
                config.getInt("AD_LDAP_PORT", 636),
                !"false".equalsIgnoreCase(config.get("AD_LDAP_USE_SSL", "true")),
                config.get("AD_LDAP_BASE_DN", ""),
                config.get("AD_LDAP_BIND_DN", ""),
                config.get("AD_LDAP_BIND_PASSWORD", ""),
                !"false".equalsIgnoreCase(config.get("AD_LOCAL_FALLBACK_ENABLED", "true")),
                config.getInt("AD_DEFAULT_ACCESS_GROUP_ID", 2));
    }

    /**
     * Tenta autenticar via bind — pesquisa o DN pelo sAMAccountName com a conta de serviço, depois
     * tenta bindar como o próprio usuário com a senha informada (LdapTemplate.authenticate cobre
     * as duas etapas). Em caso de sucesso, busca os atributos do usuário na mesma passada.
     */
    public Optional<LdapUserAttributes> authenticate(String username, String password) {
        AdLdapConfig cfg = currentConfig();
        if (!cfg.enabled() || cfg.host().isBlank()) {
            return Optional.empty();
        }
        try {
            LdapTemplate template = templateFactory.create(cfg);
            var query = LdapQueryBuilder.query().where("sAMAccountName").is(username);
            // authenticate(query, password, mapper) faz a busca com a conta de serviço, binda
            // como o usuário com a senha informada, e só então mapeia os atributos do contexto
            // já autenticado — um único round-trip, e lança em caso de falha de bind (capturado
            // abaixo, nunca vaza qual etapa falhou).
            LdapUserAttributes attrs =
                    template.authenticate(
                            query,
                            password,
                            (ctx, entry) -> {
                                try {
                                    return mapAttributes(ctx.getAttributes(entry.getRelativeName()));
                                } catch (javax.naming.NamingException ex) {
                                    throw new RuntimeException(ex);
                                }
                            });
            return Optional.ofNullable(attrs);
        } catch (Exception e) {
            // WARN, nunca a senha — só a mensagem da exceção (erro de rede/protocolo, não credencial)
            log.warn("Bind AD falhou para '{}': {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Busca completa — usada pelo {@link AdSyncScheduler} para espelhar todos os usuários.
     *
     * <p><b>Limitação conhecida (Fase 1):</b> sem {@code PagedResultsControl} — a maioria dos AD
     * limita a 1000 entradas por busca (MaxPageSize); acima disso o resultado vem truncado ou falha.
     * Aceitável para o volume-alvo desta fase; paginação real fica para quando o volume real de
     * usuários no AD for conhecido.
     */
    public List<LdapUserAttributes> fetchAll() {
        AdLdapConfig cfg = currentConfig();
        if (!cfg.enabled() || cfg.host().isBlank()) {
            return List.of();
        }
        LdapTemplate template = templateFactory.create(cfg);
        var query =
                LdapQueryBuilder.query()
                        .where("objectClass")
                        .is("user")
                        .and("sAMAccountName")
                        .isPresent();
        return template.search(query, ATTRIBUTES_MAPPER);
    }

    /** Testa uma conexão com parâmetros arbitrários (do body da requisição, não persistidos). */
    public String testConnection(AdLdapConfig cfg) {
        LdapTemplate template = templateFactory.create(cfg);
        // Bind da própria conta de serviço já valida host/porta/credencial/base DN.
        template.list(cfg.baseDn());
        return "Conectado ao Active Directory (" + cfg.host() + ":" + cfg.port() + ")";
    }

    private static LdapUserAttributes mapAttributes(Attributes attrs) throws javax.naming.NamingException {
        return new LdapUserAttributes(
                attrValue(attrs, "sAMAccountName"),
                attrValue(attrs, "displayName"),
                attrValue(attrs, "department"),
                attrValue(attrs, "physicalDeliveryOfficeName"),
                attrValue(attrs, "title"),
                attrValues(attrs, "memberOf"),
                extractRdnValue(attrValue(attrs, "manager")),
                attrValue(attrs, "mail"),
                attrValue(attrs, "telephoneNumber"));
    }

    private static String attrValue(Attributes attrs, String name) throws javax.naming.NamingException {
        var attr = attrs.get(name);
        return attr != null && attr.get() != null ? String.valueOf(attr.get()) : null;
    }

    private static List<String> attrValues(Attributes attrs, String name) throws javax.naming.NamingException {
        var attr = attrs.get(name);
        if (attr == null) {
            return List.of();
        }
        List<String> values = new java.util.ArrayList<>();
        var all = attr.getAll();
        while (all.hasMore()) {
            values.add(String.valueOf(all.next()));
        }
        return List.copyOf(values);
    }

    /** Extrai o sAMAccountName aproximado de um DN de "manager" (ex: "CN=Fulano,OU=..." → "Fulano"). */
    private static String extractRdnValue(String dn) {
        if (dn == null || dn.isBlank()) {
            return null;
        }
        String firstRdn = dn.split(",")[0];
        int eq = firstRdn.indexOf('=');
        return eq >= 0 ? firstRdn.substring(eq + 1).trim() : firstRdn.trim();
    }
}
