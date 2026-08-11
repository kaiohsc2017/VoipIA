import type { DragEvent } from 'react';
import type { FlowChannel, FlowGraphNodeType } from '../../api/types';

interface NodePaletteProps {
  catalog: FlowGraphNodeType[];
  flowChannel: FlowChannel;
  /** Adiciona o nó ao canvas — usado pelo clique/teclado, alternativa ao drag-and-drop
   * (obrigatório: arrastar-e-soltar não é acessível a quem navega só por teclado). */
  onAddNode: (nodeType: FlowGraphNodeType) => void;
}

/**
 * NodePalette — lista os tipos de nó do catálogo (servido pelo backend, GET .../catalogo — fonte
 * única, nunca duplicado aqui), filtrados pelo canal do fluxo atual: nó "both" sempre aparece; nó
 * "voice"/"chat" só quando bate com o canal do fluxo.
 */
export function NodePalette({ catalog, flowChannel, onAddNode }: NodePaletteProps) {
  const visible = catalog.filter(n => n.channel === 'both' || n.channel === flowChannel);

  const onDragStart = (event: DragEvent<HTMLButtonElement>, nodeType: FlowGraphNodeType) => {
    event.dataTransfer.setData('application/asteriskia-flow-node', JSON.stringify(nodeType));
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <aside className="flow-palette">
      <h3 className="flow-palette-title">Nós</h3>
      <p className="flow-palette-hint">Arraste para o canvas, ou clique para adicionar ao centro</p>
      {visible.map(n => (
        <button
          key={n.type}
          type="button"
          className="flow-palette-item"
          draggable
          onDragStart={e => onDragStart(e, n)}
          onClick={() => onAddNode(n)}
        >
          <span>{n.label}</span>
          {!n.implementado && <span className="badge badge-gray flow-palette-badge">não executável</span>}
        </button>
      ))}
      {visible.length === 0 && <p className="flow-palette-hint">Nenhum nó disponível para este canal.</p>}
    </aside>
  );
}
