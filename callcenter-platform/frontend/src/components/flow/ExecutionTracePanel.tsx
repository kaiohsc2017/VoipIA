import { useEffect, useRef, useState } from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  type Edge,
  type Node,
  type NodeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { History, X, Lock } from 'lucide-react';
import api, { getErrorMessage } from '../../api/client';
import { GenericNode, FlowCatalogContext, type GenericNodeData } from './nodes/GenericNode';
import { MenuNode } from './nodes/MenuNode';
import { HorarioFuncionamentoNode } from './nodes/HorarioFuncionamentoNode';
import type {
  FlowExecutionStepView,
  FlowExecutionView,
  FlowGraphDocument,
  FlowGraphNodeType,
  FlowVersionView,
} from '../../api/types';

interface ExecutionTracePanelProps {
  flowId: number;
  onClose: () => void;
}

// Mesmo mapeamento de renderização por tipo de nó do FlowEditor (Fase 5c/5e.1) — reusado aqui em
// modo somente leitura, nunca duplicado como definição própria de comportamento de nó.
const NODE_TYPES: NodeTypes = {
  generic: GenericNode,
  menu_opcoes: MenuNode,
  horario_funcionamento: HorarioFuncionamentoNode,
};
const RENDER_TYPES_WITH_FIXED_HANDLES = new Set(['menu_opcoes', 'horario_funcionamento']);
const renderTypeFor = (domainType: string) => (RENDER_TYPES_WITH_FIXED_HANDLES.has(domainType) ? domainType : 'generic');

type TraceNode = Node<GenericNodeData>;

function toIsoLocal(dateOnly: string, endOfDay: boolean): string {
  return `${dateOnly}T${endOfDay ? '23:59:59' : '00:00:00'}`;
}

/**
 * ExecutionTracePanel — traço de execuções reais do fluxo (Fase 5f.2): "onde o cliente esteve, em
 * que ordem, qual saída seguiu". Período é sempre obrigatório na busca de execuções — o backend
 * rejeita a chamada sem `from`/`to` (`cc_flow_execution_steps` é particionada por mês, ver
 * `CallCenterFlowExecutionController`). O grafo é renderizado reusando os mesmos componentes de nó
 * do `FlowEditor`, em modo somente leitura (sem arrastar/conectar/editar).
 */
export function ExecutionTracePanel({ flowId, onClose }: ExecutionTracePanelProps) {
  const today = new Date().toISOString().slice(0, 10);
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [executions, setExecutions] = useState<FlowExecutionView[] | null>(null);
  const [selected, setSelected] = useState<FlowExecutionView | null>(null);
  const [steps, setSteps] = useState<FlowExecutionStepView[]>([]);
  const [catalog, setCatalog] = useState<FlowGraphNodeType[]>([]);
  const [nodes, setNodes] = useState<TraceNode[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const requestSeqRef = useRef(0);
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);

  useEffect(() => {
    api.get<FlowGraphNodeType[]>('/callcenter/fluxos/catalogo').then(({ data }) => setCatalog(data)).catch(() => setCatalog([]));
  }, []);

  const searchExecutions = () => {
    const seq = ++requestSeqRef.current;
    setLoading(true);
    setError('');
    setSelected(null);
    setExecutions(null);
    api
      .get<{ content: FlowExecutionView[] }>(`/callcenter/fluxos/${flowId}/execucoes`, {
        params: { from: toIsoLocal(from, false), to: toIsoLocal(to, true), size: 50 },
      })
      .then(({ data }) => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setExecutions(Array.isArray(data?.content) ? data.content : []);
      })
      .catch(err => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setError(getErrorMessage(err, 'Erro ao buscar execuções do período.'));
      })
      .finally(() => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setLoading(false);
      });
  };

  const openExecution = (execution: FlowExecutionView) => {
    const seq = ++requestSeqRef.current;
    setSelected(execution);
    setLoading(true);
    setError('');
    setNodes([]);
    setEdges([]);
    setSteps([]);

    Promise.all([
      api.get<FlowExecutionStepView[]>(`/callcenter/fluxos/${flowId}/execucoes/${execution.id}/passos`),
      api.get<FlowVersionView>(`/callcenter/fluxos/${flowId}/versions/${execution.flowVersionId}`),
    ])
      .then(([stepsRes, versionRes]) => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        const stepList = stepsRes.data;
        setSteps(stepList);
        const visitedNodeIds = new Set(stepList.map(s => s.nodeId));
        const takenEdgeIds = new Set(stepList.map(s => s.takenEdge).filter((id): id is string => !!id));

        let doc: FlowGraphDocument = { schemaVersion: 2, nodes: [], edges: [] };
        if (versionRes.data.graph) {
          try {
            doc = JSON.parse(versionRes.data.graph);
          } catch {
            setError('Grafo da versão usada nesta execução está corrompido — mostrando só a lista de passos.');
          }
        }
        setNodes(
          (doc.nodes ?? []).map(n => ({
            id: n.id,
            type: renderTypeFor(n.data.nodeType),
            position: n.position,
            data: n.data,
            className: visitedNodeIds.has(n.id) ? 'flow-node-trace-visited' : 'flow-node-trace-unvisited',
            draggable: false,
            selectable: false,
          })),
        );
        setEdges(
          (doc.edges ?? []).map(e => ({
            ...e,
            className: takenEdgeIds.has(e.id) ? 'flow-edge-trace-taken' : 'flow-edge-trace-untaken',
            animated: takenEdgeIds.has(e.id),
          })),
        );
      })
      .catch(err => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setError(getErrorMessage(err, 'Erro ao carregar o traço desta execução.'));
      })
      .finally(() => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setLoading(false);
      });
  };

  // Passo marcado como sensível pelo nó (config `sensivel=true`, ver FlowGraphNodeCatalog) nunca
  // chega com valor real do backend — `detail` já vem nulo para esses nós. Aqui só decoramos a UI
  // para deixar explícito que o passo existiu, sem inventar nenhum mascaramento novo.
  const isSensitiveNode = (nodeId: string): boolean => {
    const graphNode = nodes.find(n => n.id === nodeId);
    return graphNode?.data.properties?.sensivel === true;
  };

  return (
    <div className="flow-properties flow-trace-panel">
      <div className="flex" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 className="flow-palette-title"><History size={14} /> Traço de execução</h3>
        <button className="btn btn-ghost btn-sm" onClick={onClose} title="Fechar traço de execução">
          <X size={14} />
        </button>
      </div>

      {!selected && (
        <>
          <p className="flow-palette-hint">
            Informe um período para listar as execuções reais deste fluxo — a busca sempre exige
            data inicial e final.
          </p>
          <div className="flex" style={{ gap: 8, alignItems: 'flex-end' }}>
            <div>
              <label className="form-label" htmlFor="flow-trace-from">De</label>
              <input id="flow-trace-from" type="date" className="form-input" value={from} onChange={e => setFrom(e.target.value)} />
            </div>
            <div>
              <label className="form-label" htmlFor="flow-trace-to">Até</label>
              <input id="flow-trace-to" type="date" className="form-input" value={to} onChange={e => setTo(e.target.value)} />
            </div>
            <button className="btn btn-primary btn-sm" onClick={searchExecutions} disabled={loading}>
              {loading ? 'Buscando…' : 'Buscar'}
            </button>
          </div>

          {error && <div className="flow-issue flow-issue-error" style={{ marginTop: 8 }}>{error}</div>}

          {executions && executions.length === 0 && (
            <p className="flow-palette-hint" style={{ marginTop: 8 }}>Nenhuma execução no período informado.</p>
          )}
          {executions && executions.length > 0 && (
            <ul className="flow-trace-execution-list">
              {executions.map(exec => (
                <li key={exec.id}>
                  <button className="btn btn-ghost btn-sm flow-trace-execution-item" onClick={() => openExecution(exec)}>
                    <span>{new Date(exec.startedAt).toLocaleString('pt-BR')}</span>
                    <span className="badge badge-gray">{exec.outcome ?? 'em andamento'}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {selected && (
        <>
          <button className="btn btn-ghost btn-sm" onClick={() => setSelected(null)} style={{ marginBottom: 8 }}>
            ← Voltar à lista
          </button>
          {error && <div className="flow-issue flow-issue-error" style={{ marginBottom: 8 }}>{error}</div>}

          <div className="flow-trace-canvas">
            <ReactFlowProvider>
              <FlowCatalogContext.Provider value={catalog}>
                <ReactFlow
                  nodes={nodes}
                  edges={edges}
                  nodeTypes={NODE_TYPES}
                  nodesDraggable={false}
                  nodesConnectable={false}
                  elementsSelectable={false}
                  fitView
                >
                  <Background />
                  <Controls showInteractive={false} />
                </ReactFlow>
              </FlowCatalogContext.Provider>
            </ReactFlowProvider>
          </div>

          <ol className="flow-simulation-steps">
            {steps.map((step, i) => (
              <li key={`${step.id}-${i}`} className="flow-simulation-step">
                <div className="flow-simulation-step-header">
                  <span className="flow-node-type">{step.nodeType}</span>
                  <span>{new Date(step.enteredAt).toLocaleTimeString('pt-BR')}</span>
                  {step.takenEdge && <span className="flow-palette-hint">→ {step.takenEdge}</span>}
                </div>
                {isSensitiveNode(step.nodeId) && (
                  <div className="flow-simulation-step-detail flex" style={{ gap: 4, alignItems: 'center' }}>
                    <Lock size={12} /> Dado sensível — valor não registrado no traço
                  </div>
                )}
                {!isSensitiveNode(step.nodeId) && step.detail && (
                  <div className="flow-simulation-step-detail">{step.detail}</div>
                )}
              </li>
            ))}
          </ol>
        </>
      )}
    </div>
  );
}
