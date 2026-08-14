import { Handle, Position } from '@xyflow/react';
import type { GenericNodeData } from './GenericNode';

interface HorarioFuncionamentoNodeProps {
  data: GenericNodeData;
  selected?: boolean;
}

/**
 * HorarioFuncionamentoNode — nó "horario_funcionamento" (Fase 5e.1). 3 handles de saída fixos
 * (`hr-aberto`/`hr-fechado`/`hr-feriado`), mesmo padrão visual de handle nomeado introduzido pelo
 * MenuNode (Fase 5c) — mas fixos, não dinâmicos por configuração (mesma ideia do nó "condição").
 */
export function HorarioFuncionamentoNode({ data, selected }: HorarioFuncionamentoNodeProps) {
  return (
    <div className={`flow-node flow-node-menu${selected ? ' flow-node-selected' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="flow-node-label">{data.label}</div>
      <div className="flow-node-type">horario_funcionamento</div>
      <div className="flow-node-menu-options">
        <div className="flow-node-menu-option">
          <span>Aberto</span>
          <Handle type="source" position={Position.Right} id="hr-aberto" style={{ position: 'relative', transform: 'none', display: 'inline-block' }} />
        </div>
        <div className="flow-node-menu-option">
          <span>Fechado (horário)</span>
          <Handle type="source" position={Position.Right} id="hr-fechado" style={{ position: 'relative', transform: 'none', display: 'inline-block' }} />
        </div>
        <div className="flow-node-menu-option">
          <span>Fechado (feriado)</span>
          <Handle type="source" position={Position.Right} id="hr-feriado" style={{ position: 'relative', transform: 'none', display: 'inline-block' }} />
        </div>
      </div>
    </div>
  );
}
