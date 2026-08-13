import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  type Connection,
  type Edge,
  type Node,
  type NodeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ArrowLeft, Save, Rocket, Undo2, Redo2 } from 'lucide-react';
import api, { getErrorMessage } from '../../api/client';
import { GenericNode, FlowCatalogContext, type GenericNodeData } from './nodes/GenericNode';
import { MenuNode } from './nodes/MenuNode';
import { NodePalette } from './NodePalette';
import { NodePropertiesPanel } from './NodePropertiesPanel';
import type { FlowGraphDocument, FlowGraphNodeType, FlowGraphValidationResult, FlowVersionView, FlowView } from '../../api/types';

interface FlowEditorProps {
  flow: FlowView;
  canWrite: boolean;
  onBack: () => void;
}

// Fase 5c: 'menu_opcoes' ganha renderização própria (handles nomeados por opção) — os demais
// tipos continuam no nó genérico único da 5a. Grafos publicados antes desta fase têm
// `type: "generic"` gravado em todo nó (inclusive menus antigos, formato v1) e continuam
// renderizando como sempre; só nós menu_opcoes criados a partir de agora ganham `type:
// "menu_opcoes"` (ver addNodeAtCenter/onDrop) e passam a usar o MenuNode.
const NODE_TYPES: NodeTypes = { generic: GenericNode, menu_opcoes: MenuNode };
let nodeSeq = 0;
const nextNodeId = () => `node-${Date.now()}-${nodeSeq++}`;
const renderTypeFor = (domainType: string) => (domainType === 'menu_opcoes' ? 'menu_opcoes' : 'generic');

type FlowNode = Node<GenericNodeData>;

/**
 * FlowEditor — canvas React Flow do Flow Builder (Fase 5a). Persiste/carrega o grafo como JSON
 * nativo do React Flow (`{schemaVersion, nodes, edges}`); a validação real (que bloqueia
 * publicação) é sempre a resposta do backend — o selo "não executável ainda" aqui é só aviso
 * visual antecipado, o motor (ARI/Stasis) só chega na Fase 5b.
 */
export function FlowEditor({ flow, canWrite, onBack }: FlowEditorProps) {
  const [catalog, setCatalog] = useState<FlowGraphNodeType[]>([]);
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [msg, setMsg] = useState('');
  const [issues, setIssues] = useState<FlowGraphValidationResult | null>(null);

  // Pilha local de desfazer/refazer — snapshots do grafo, sem lib nova.
  const historyRef = useRef<{ stack: FlowGraphDocument[]; index: number }>({ stack: [], index: -1 });
  const skipHistoryRef = useRef(false);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 5000); };

  const applyGraph = useCallback((doc: FlowGraphDocument) => {
    setNodes((doc.nodes ?? []) as FlowNode[]);
    setEdges((doc.edges ?? []) as Edge[]);
  }, [setNodes, setEdges]);

  useEffect(() => {
    api.get<FlowGraphNodeType[]>('/callcenter/fluxos/catalogo').then(({ data }) => setCatalog(data)).catch(() => setCatalog([]));

    api.get<FlowVersionView[]>(`/callcenter/fluxos/${flow.id}/versions`).then(({ data }) => {
      const draft = data.find(v => v.status === 'DRAFT');
      if (!draft) { flash('Fluxo sem rascunho — recarregue a página.'); return; }
      api.get<FlowVersionView>(`/callcenter/fluxos/${flow.id}/versions/${draft.id}`).then(({ data: full }) => {
        let doc: FlowGraphDocument = { schemaVersion: 1, nodes: [], edges: [] };
        if (full.graph) {
          try {
            doc = JSON.parse(full.graph);
          } catch {
            flash('Rascunho corrompido — abrindo com um grafo vazio.');
          }
        }
        applyGraph(doc);
        historyRef.current = { stack: [doc], index: 0 };
      });
    });
    // applyGraph/flash omitidos: identidade estável entre renders (applyGraph via useCallback com
    // deps fixas; flash não depende de estado externo) — só flow.id deve reexecutar o efeito.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flow.id]);

  // Registra snapshot no histórico após cada mudança efetiva de nodes/edges (exceto quando a
  // própria navegação do histórico — desfazer/refazer — é quem está aplicando o snapshot).
  useEffect(() => {
    if (skipHistoryRef.current) { skipHistoryRef.current = false; return; }
    const doc: FlowGraphDocument = { schemaVersion: 2, nodes: nodes as FlowGraphDocument['nodes'], edges: edges as FlowGraphDocument['edges'] };
    const h = historyRef.current;
    const truncated = h.stack.slice(0, h.index + 1);
    truncated.push(doc);
    historyRef.current = { stack: truncated, index: truncated.length - 1 };
    // historyRef/skipHistoryRef omitidos: são refs mutáveis lidos/escritos por identidade estável,
    // nunca disparam re-render nem precisam re-executar o efeito — só nodes/edges importam aqui.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes, edges]);

  const undo = () => {
    const h = historyRef.current;
    if (h.index <= 0) return;
    const doc = h.stack[h.index - 1];
    historyRef.current = { ...h, index: h.index - 1 };
    skipHistoryRef.current = true;
    applyGraph(doc);
  };

  const redo = () => {
    const h = historyRef.current;
    if (h.index >= h.stack.length - 1) return;
    const doc = h.stack[h.index + 1];
    historyRef.current = { ...h, index: h.index + 1 };
    skipHistoryRef.current = true;
    applyGraph(doc);
  };

  const onConnect = useCallback((connection: Connection) => setEdges(eds => addEdge(connection, eds)), [setEdges]);

  const onDrop = useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    const raw = event.dataTransfer.getData('application/asteriskia-flow-node');
    if (!raw) return;
    let nodeType: FlowGraphNodeType;
    try {
      nodeType = JSON.parse(raw);
    } catch {
      return; // payload de drag externo/malformado — ignora silenciosamente, não é um erro do usuário
    }
    const bounds = event.currentTarget.getBoundingClientRect();
    const position = { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
    const newNode: FlowNode = {
      id: nextNodeId(),
      type: renderTypeFor(nodeType.type),
      position,
      data: { nodeType: nodeType.type, label: nodeType.label, properties: {} },
    };
    setNodes(nds => [...nds, newNode]);
  }, [setNodes]);

  /** Adiciona um nó no centro do canvas — equivalente por teclado/clique ao drag-and-drop
   * (paleta é inacessível a quem não usa mouse sem este caminho alternativo). */
  const addNodeAtCenter = useCallback((nodeType: FlowGraphNodeType) => {
    const newNode: FlowNode = {
      id: nextNodeId(),
      type: renderTypeFor(nodeType.type),
      position: { x: 200, y: 150 },
      data: { nodeType: nodeType.type, label: nodeType.label, properties: {} },
    };
    setNodes(nds => [...nds, newNode]);
  }, [setNodes]);

  const onDragOver = useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const selectedNode = nodes.find(n => n.id === selectedNodeId) ?? null;

  const updateNodeProperties = (nodeId: string, properties: Record<string, string | number | boolean>) => {
    setNodes(nds => nds.map(n => (n.id === nodeId ? { ...n, data: { ...n.data, properties } } : n)));
  };

  const deleteNode = (nodeId: string) => {
    setNodes(nds => nds.filter(n => n.id !== nodeId));
    setEdges(eds => eds.filter(e => e.source !== nodeId && e.target !== nodeId));
    setSelectedNodeId(null);
  };

  // Fase 5c: sobe pra 2 — ramificação de menu passou a viajar via sourceHandle de cada aresta
  // (preenchido nativamente pelo React Flow quando o handle de origem é nomeado, ver MenuNode),
  // não mais por id de aresta digitado à mão. Grafos v1 sem sourceHandle continuam lidos pelo
  // parser de fallback do MenuNodeHandler no backend.
  const currentGraph = (): string => JSON.stringify({ schemaVersion: 2, nodes, edges });

  const saveDraft = () => {
    api.put<FlowGraphValidationResult>(`/callcenter/fluxos/${flow.id}/draft`, { graph: currentGraph() })
      .then(({ data }) => { setIssues(data); flash('Rascunho salvo.'); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar rascunho.')));
  };

  const publish = () => {
    api.post<FlowGraphValidationResult>(`/callcenter/fluxos/${flow.id}/publish`)
      .then(({ data }) => {
        setIssues(data);
        flash(data.errors.length === 0 ? 'Fluxo publicado.' : 'Publicação bloqueada — corrija os erros abaixo.');
      })
      .catch(err => flash(getErrorMessage(err, 'Erro ao publicar fluxo.')));
  };

  return (
    <ReactFlowProvider>
      <FlowCatalogContext.Provider value={catalog}>
      <div className="flow-editor-layout">
        <div className="flow-editor-toolbar">
          <button className="btn btn-ghost btn-sm" onClick={onBack}><ArrowLeft size={14} /> Voltar</button>
          <span className="flow-editor-title">{flow.name}</span>
          {canWrite && (
            <div className="flex" style={{ gap: 8, marginLeft: 'auto' }}>
              <button className="btn btn-ghost btn-sm" onClick={undo} title="Desfazer"><Undo2 size={14} /></button>
              <button className="btn btn-ghost btn-sm" onClick={redo} title="Refazer"><Redo2 size={14} /></button>
              <button className="btn btn-ghost btn-sm" onClick={saveDraft}><Save size={14} /> Salvar rascunho</button>
              <button className="btn btn-primary btn-sm" onClick={publish}><Rocket size={14} /> Publicar</button>
            </div>
          )}
        </div>

        {msg && <div className="flash-message" style={{ background: 'var(--bg-primary-soft)', color: 'var(--clr-primary)' }}>{msg}</div>}
        {issues && (issues.errors.length > 0 || issues.warnings.length > 0) && (
          <div className="flow-issues">
            {issues.errors.map((iss, i) => (
              <div key={`err-${i}`} className="flow-issue flow-issue-error">
                {iss.nodeId ? `[${iss.nodeId}] ` : ''}{iss.message}
              </div>
            ))}
            {issues.warnings.map((iss, i) => (
              <div key={`warn-${i}`} className="flow-issue flow-issue-warning">
                {iss.nodeId ? `[${iss.nodeId}] ` : ''}{iss.message}
              </div>
            ))}
          </div>
        )}

        <div className="flow-editor-body">
          {canWrite && <NodePalette catalog={catalog} flowChannel={flow.channel} onAddNode={addNodeAtCenter} />}
          <div className="flow-canvas" onDrop={onDrop} onDragOver={onDragOver}>
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={canWrite ? onNodesChange : undefined}
              onEdgesChange={canWrite ? onEdgesChange : undefined}
              onConnect={canWrite ? onConnect : undefined}
              onNodeClick={(_, node) => setSelectedNodeId(node.id)}
              onPaneClick={() => setSelectedNodeId(null)}
              nodeTypes={NODE_TYPES}
              nodesDraggable={canWrite}
              nodesConnectable={canWrite}
              elementsSelectable={canWrite}
              fitView
            >
              <Background />
              <Controls />
              <MiniMap />
            </ReactFlow>
          </div>
          {canWrite && (
            <NodePropertiesPanel
              // Força remontagem ao trocar de nó — sem isso, estado local de upload de áudio
              // (Fase 5c) vazava entre nós: iniciar um upload, selecionar outro nó antes de
              // terminar, e o "Enviando…"/erro aparecia sob o nó errado (achado de revisão).
              key={selectedNode?.id ?? 'none'}
              node={selectedNode}
              catalog={catalog}
              onChange={updateNodeProperties}
              onDelete={deleteNode}
            />
          )}
        </div>
      </div>
      </FlowCatalogContext.Provider>
    </ReactFlowProvider>
  );
}
