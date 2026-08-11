import type { Node } from '@xyflow/react';
import type { FlowGraphNodeType } from '../../api/types';
import type { GenericNodeData } from './nodes/GenericNode';

interface NodePropertiesPanelProps {
  node: Node<GenericNodeData> | null;
  catalog: FlowGraphNodeType[];
  onChange: (nodeId: string, properties: Record<string, string | number | boolean>) => void;
  onDelete: (nodeId: string) => void;
}

/**
 * NodePropertiesPanel — renderiza os campos do nó selecionado de forma genérica, a partir do
 * `properties` que o catálogo (backend) descreve para aquele tipo — sem inventar um schema de
 * opções que o backend não manda (ex: "select" sem lista de opções cai como texto simples).
 */
export function NodePropertiesPanel({ node, catalog, onChange, onDelete }: NodePropertiesPanelProps) {
  if (!node) {
    return (
      <aside className="flow-properties">
        <p className="flow-palette-hint">Selecione um nó para editar suas propriedades.</p>
      </aside>
    );
  }

  const entry = catalog.find(c => c.type === node.data.nodeType);
  const properties = entry?.properties ?? [];

  const setProperty = (name: string, value: string | number | boolean) => {
    onChange(node.id, { ...node.data.properties, [name]: value });
  };

  return (
    <aside className="flow-properties">
      <h3 className="flow-palette-title">{node.data.label}</h3>
      <p className="flow-palette-hint">Tipo: {node.data.nodeType}</p>
      {properties.length === 0 && <p className="flow-palette-hint">Este nó não tem propriedades configuráveis.</p>}
      {properties.map(p => {
        const inputId = `flow-node-prop-${node.id}-${p.name}`;
        return (
          <div className="form-group" key={p.name}>
            <label className="form-label" htmlFor={inputId}>{p.label}</label>
            {p.type === 'boolean' ? (
              <input
                id={inputId}
                type="checkbox"
                checked={Boolean(node.data.properties[p.name])}
                onChange={e => setProperty(p.name, e.target.checked)}
              />
            ) : (
              <input
                id={inputId}
                className="form-input"
                type={p.type === 'number' ? 'number' : 'text'}
                value={String(node.data.properties[p.name] ?? '')}
                onChange={e => setProperty(p.name, p.type === 'number' ? Number(e.target.value) : e.target.value)}
              />
            )}
          </div>
        );
      })}
      <button className="btn btn-danger btn-sm" onClick={() => onDelete(node.id)}>Excluir nó</button>
    </aside>
  );
}
