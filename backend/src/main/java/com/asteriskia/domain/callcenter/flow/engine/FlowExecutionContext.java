package com.asteriskia.domain.callcenter.flow.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FlowExecutionContext — estado em memória de uma execução em curso (Fase 5b). Vive só enquanto o
 * canal está dentro do Stasis; nunca persistido por passo (decisão aceita: restart do backend
 * derruba chamadas em curso — o Asterisk já é nó único, um restart dele derruba tudo de qualquer
 * forma).
 */
public final class FlowExecutionContext {

    private final Long flowId;
    private final Long flowVersionId;
    private final Long executionId;
    private final String channelId;
    private final ChannelDriver driver;
    private final Map<String, String> variables = new ConcurrentHashMap<>();

    public FlowExecutionContext(
            Long flowId, Long flowVersionId, Long executionId, String channelId, ChannelDriver driver) {
        this.flowId = flowId;
        this.flowVersionId = flowVersionId;
        this.executionId = executionId;
        this.channelId = channelId;
        this.driver = driver;
    }

    public Long flowId() {
        return flowId;
    }

    public Long flowVersionId() {
        return flowVersionId;
    }

    public Long executionId() {
        return executionId;
    }

    public String channelId() {
        return channelId;
    }

    public ChannelDriver driver() {
        return driver;
    }

    public String getVariable(String name) {
        return variables.get(name);
    }

    public void setVariable(String name, String value) {
        variables.put(name, value);
    }
}
