package com.asteriskia.domain.callcenter.flow;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * FlowGraphNodeCatalog — fonte única (servida pelo backend via {@code GET
 * /api/v1/callcenter/fluxos/catalogo}) dos tipos de nó do Flow Builder (Fase 5, catálogo v1). O
 * frontend não duplica esta lista em TypeScript — evita a divergência já observada entre
 * ResourceCatalog.java/Sidebar.tsx em outros módulos.
 *
 * <p>Sub-fase 5b: o motor de execução ARI/Stasis passou a interpretar 7 tipos de nó —
 * {@code inicio}, {@code tocar_audio}, {@code menu_opcoes}, {@code condicao},
 * {@code definir_variavel}, {@code enviar_fila}, {@code encerrar}. Fase 21 (Parte III) somou
 * {@code pesquisa_satisfacao} (delega a {@code CallCenterSurveyRunner}). Fase 5c somou
 * {@code pausar_gravacao} (delega a {@code CallCenterRecordingControlService}, órfão desde a
 * Fase 3) e trocou o roteamento do {@code menu_opcoes} de id-de-aresta digitado à mão para
 * {@code sourceHandle} (ver {@code FlowGraph.Edge}/{@code MenuNodeHandler}). Os 5 restantes
 * ({@code coletar_entrada}, {@code consultar_api}, {@code transferir_ramal},
 * {@code horario_funcionamento}, {@code agente_ia}) continuam {@code false} — ficam para as
 * sub-fases 5d/5e. {@link FlowGraphValidator} bloqueia a publicação de qualquer fluxo que use um
 * nó ainda não implementado.
 *
 * <p>Fase 24 (chat) prova a premissa de motor agnóstico de canal: {@code menu_opcoes},
 * {@code tocar_audio}, {@code enviar_fila}, {@code encerrar}, {@code condicao},
 * {@code definir_variavel} já eram {@code channel="both"} e passam a rodar em chat sem nenhuma
 * mudança de handler — só {@link com.asteriskia.domain.callcenter.flow.chat.ChatChannelDriver}
 * é novo. {@code coletar_texto} é o único nó exclusivo do canal {@code chat} (equivalente chat de
 * {@code coletar_entrada}, que continua exclusivo de voz e não implementado).
 */
@Component
public class FlowGraphNodeCatalog {

    private static final Set<String> IMPLEMENTED_TYPES =
            Set.of(
                    "inicio", "tocar_audio", "menu_opcoes", "condicao", "definir_variavel", "enviar_fila",
                    "pesquisa_satisfacao", "pausar_gravacao", "coletar_texto", "consultar_base", "encerrar");

    private static final List<FlowGraphNodeType> NODE_TYPES =
            List.of(
                    node("inicio", "Início", "both", List.of()),
                    node(
                            "tocar_audio",
                            "Tocar áudio/TTS",
                            "both",
                            List.of(prop("audioPath", "Áudio", "audio"), prop("texto", "Texto (TTS)", "string"))),
                    node(
                            "menu_opcoes",
                            "Menu de opções",
                            "both",
                            List.of(
                                    prop("audioPath", "Áudio do menu", "audio"),
                                    prop("texto", "Texto (TTS)", "string"),
                                    prop("opcoesMenu", "Opções (dígito → rótulo)", "keypad"),
                                    prop("timeoutSegundos", "Timeout (s)", "number"),
                                    prop("tentativas", "Tentativas até desistir", "number"))),
                    node(
                            "coletar_entrada",
                            "Coletar entrada",
                            "both",
                            List.of(
                                    prop("variavel", "Variável de destino", "string"),
                                    prop("sensivel", "Dado sensível (não registrar no traço)", "boolean"))),
                    node(
                            "condicao",
                            "Condição",
                            "both",
                            List.of(prop("expressao", "Expressão", "string"))),
                    node(
                            "definir_variavel",
                            "Definir variável",
                            "both",
                            List.of(prop("variavel", "Nome", "string"), prop("valor", "Valor", "string"))),
                    node(
                            "consultar_api",
                            "Consultar API externa",
                            "both",
                            List.of(
                                    prop("settingsKey", "Chave de configuração (Settings)", "string"),
                                    prop("timeoutSegundos", "Timeout (s)", "number"))),
                    node(
                            "enviar_fila",
                            "Enviar para fila",
                            "both",
                            List.of(prop("filaId", "Fila", "select"))),
                    node(
                            "transferir_ramal",
                            "Transferir para ramal",
                            "voice",
                            List.of(prop("ramal", "Ramal de destino", "string"))),
                    node(
                            "horario_funcionamento",
                            "Horário de funcionamento",
                            "both",
                            List.of(prop("calendarioId", "Calendário", "select"))),
                    node(
                            "agente_ia",
                            "Agente de IA",
                            "both",
                            List.of(prop("configuracaoIaId", "Configuração de IA", "select"))),
                    node(
                            "pausar_gravacao",
                            "Pausar/retomar gravação",
                            "voice",
                            List.of(
                                    new FlowGraphNodeType.NodeProperty(
                                            "acao",
                                            "Ação",
                                            "select",
                                            List.of(
                                                    new FlowGraphNodeType.NodeProperty.Option("pausar", "Pausar gravação"),
                                                    new FlowGraphNodeType.NodeProperty.Option("retomar", "Retomar gravação")),
                                            true))),
                    node(
                            "coletar_texto",
                            "Coletar texto (chat)",
                            "chat",
                            List.of(
                                    prop("variavel", "Variável de destino", "string"),
                                    prop("timeoutSegundos", "Timeout (s)", "number"))),
                    node(
                            "pesquisa_satisfacao",
                            "Pesquisa de satisfação",
                            "both",
                            List.of(prop("pesquisaId", "Pesquisa", "select"))),
                    node(
                            "consultar_base",
                            "Consultar base de conhecimento (IA)",
                            "chat",
                            List.of(
                                    prop("variavelPergunta", "Variável com a pergunta", "string"),
                                    prop("filaId", "Fila de fallback (sem 2ª aresta)", "select"))),
                    node("encerrar", "Encerrar", "both", List.of()));

    public List<FlowGraphNodeType> all() {
        return NODE_TYPES;
    }

    public java.util.Optional<FlowGraphNodeType> findByType(String type) {
        return NODE_TYPES.stream().filter(n -> n.type().equals(type)).findFirst();
    }

    private static FlowGraphNodeType node(
            String type, String label, String channel, List<FlowGraphNodeType.NodeProperty> properties) {
        return new FlowGraphNodeType(type, label, channel, IMPLEMENTED_TYPES.contains(type), properties);
    }

    private static FlowGraphNodeType.NodeProperty prop(String name, String label, String propType) {
        return new FlowGraphNodeType.NodeProperty(name, label, propType);
    }
}
