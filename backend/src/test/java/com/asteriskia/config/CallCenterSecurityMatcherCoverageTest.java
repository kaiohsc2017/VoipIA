package com.asteriskia.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fase 10, critério de conclusão 3.7.1: prova estaticamente que todo {@code @RestController} do
 * domínio {@code callcenter} (e o namespace {@code /api/v1/internal/**}) tem um matcher próprio em
 * {@code SecurityConfig} — nunca cai no {@code anyRequest().authenticated()} genérico, exatamente
 * a classe de achado HIGH já corrigida na Fase 23 (JWT comum de usuário chamando endpoints
 * internos como se fosse o Asterisk). Não sobe um {@code ApplicationContext} (não precisa de
 * banco): varre o classpath por controllers e lê o texto-fonte de {@code SecurityConfig.java}
 * como uma verificação estrutural — barata e roda em toda execução da suíte, diferente de um
 * teste de integração completo com {@code MockMvc}.
 */
class CallCenterSecurityMatcherCoverageTest {

    @Test
    void todoControllerDoCallCenterTemMatcherProprioEmSecurityConfig() throws IOException {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        var basePaths =
                scanner.findCandidateComponents("com.asteriskia.domain.callcenter").stream()
                        .map(beanDef -> {
                            try {
                                return Class.forName(beanDef.getBeanClassName());
                            } catch (ClassNotFoundException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .map(clazz -> clazz.getAnnotation(RequestMapping.class))
                        .filter(mapping -> mapping != null && mapping.value().length > 0)
                        .map(mapping -> mapping.value()[0])
                        .distinct()
                        .toList();

        assertThat(basePaths).isNotEmpty();

        String securityConfigSource =
                Files.readString(Path.of("src/main/java/com/asteriskia/config/SecurityConfig.java"));
        List<String> matcherPrefixes = extractMatcherPrefixes(securityConfigSource);

        List<String> semMatcherProprio =
                basePaths.stream()
                        .filter(
                                path ->
                                        matcherPrefixes.stream()
                                                .noneMatch(prefix -> covers(prefix, path) || prefix.startsWith(path + "/")))
                        .toList();

        assertThat(semMatcherProprio)
                .as(
                        "Todo controller do domínio callcenter deve ter ao menos um "
                                + ".requestMatchers(...) referenciando seu path base em SecurityConfig — "
                                + "controllers listados aqui cairiam no anyRequest().authenticated() genérico "
                                + "(mesma classe de achado HIGH da Fase 23)")
                .isEmpty();
    }

    /** Extrai os literais de path dos {@code .requestMatchers(...)} de {@code SecurityConfig} e
     * devolve o prefixo real de cada um (sem o {@code *}/{@code **} final) — um controller cujo
     * path base COMEÇA por esse prefixo está coberto (matchers de Spring Security são sempre
     * hierárquicos: {@code "/api/v1/callcenter/kb/**"} cobre {@code "/api/v1/callcenter/kb/articles"}). */
    private static final Pattern PATH_LITERAL = Pattern.compile("\"(/api/v1/[^\"]*)\"");

    private List<String> extractMatcherPrefixes(String source) {
        return PATH_LITERAL.matcher(source).results()
                .map(m -> m.group(1))
                .map(p -> p.replaceAll("\\*+$", ""))
                .distinct()
                .toList();
    }

    /** {@code prefix} (já sem o {@code *}/{@code **} final) cobre {@code controllerBasePath} se
     * for prefixo dele ignorando uma barra final residual dos dois lados — ex.: o matcher
     * {@code "/api/v1/callcenter/agentes/**"} vira o prefixo {@code "/api/v1/callcenter/agentes/"},
     * que deve cobrir o {@code @RequestMapping("/api/v1/callcenter/agentes")} do controller (sem
     * a barra final). */
    private boolean covers(String prefix, String controllerBasePath) {
        String normalizedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return controllerBasePath.equals(normalizedPrefix) || controllerBasePath.startsWith(normalizedPrefix + "/");
    }

    @Test
    void internalApiTemMatcherDedicadoComRoleInternal() throws IOException {
        String securityConfigSource =
                Files.readString(Path.of("src/main/java/com/asteriskia/config/SecurityConfig.java"));

        assertThat(securityConfigSource)
                .as("/api/v1/internal/** deve exigir ROLE_INTERNAL — nunca cair no fallback autenticado genérico")
                .contains("\"/api/v1/internal/**\"")
                .contains("ROLE_INTERNAL");
    }

    @Test
    void fallbackGenericoContinuaExigindoAutenticacao() throws IOException {
        // Confirma que o fallback existe e exige autenticação — a garantia desta suíte é que
        // nenhuma rota do callcenter DEPENDA dele, não que ele deixe de existir.
        String securityConfigSource =
                Files.readString(Path.of("src/main/java/com/asteriskia/config/SecurityConfig.java"));

        assertThat(securityConfigSource).contains("anyRequest().authenticated()");
    }
}
