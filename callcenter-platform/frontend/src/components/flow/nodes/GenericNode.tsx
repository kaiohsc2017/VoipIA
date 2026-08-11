import { createContext, useContext } from 'react';
import { Handle, Position } from '@xyflow/react';
import type { FlowGraphNodeType } from '../../../api/types';

export interface GenericNodeData extends Record<string, unknown> {
  nodeType: string;
  label: string;
  properties: Record<string, string | number | boolean>;
}

interface GenericNodeProps {
  data: GenericNodeData;
  selected?: boolean;
}

/** Catálogo vivo do fluxo — nunca persistido no node, sempre resolvido por `nodeType` para não
 * divergir quando um tipo passar a ser `implementado: true` em fluxos salvos antes da mudança. */
export const FlowCatalogContext = createContext<FlowGraphNodeType[]>([]);

/**
 * GenericNode — nó visual único para todos os tipos do catálogo nesta sub-fase (5a). Um
 * componente dedicado por tipo (ícone/formato próprio) é refinamento visual para uma fase
 * futura — aqui o que importa é mostrar rótulo, tipo e o selo "não executável ainda", já que
 * o motor (ARI/Stasis) só chega na Fase 5b.
 */
export function GenericNode({ data, selected }: GenericNodeProps) {
  const catalog = useContext(FlowCatalogContext);
  const implementado = catalog.find(c => c.type === data.nodeType)?.implementado ?? false;

  return (
    <div className={`flow-node${selected ? ' flow-node-selected' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="flow-node-label">{data.label}</div>
      <div className="flow-node-type">{data.nodeType}</div>
      {!implementado && <span className="badge badge-gray flow-node-badge">não executável ainda</span>}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}
