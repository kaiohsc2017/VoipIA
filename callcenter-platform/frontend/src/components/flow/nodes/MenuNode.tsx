import { Handle, Position } from '@xyflow/react';
import type { GenericNodeData } from './GenericNode';

export interface MenuOption {
  digito: string;
  rotulo: string;
}

interface MenuNodeProps {
  data: GenericNodeData;
  selected?: boolean;
}

/** Lê `opcoesMenu` (JSON `[{digito,rotulo}]`, ver NodePropertiesPanel) — nunca lança em conteúdo
 * malformado, só some com os handles de opção (o operador ainda vê o nó e pode reconfigurar). */
export function parseMenuOptions(raw: unknown): MenuOption[] {
  if (typeof raw !== 'string' || !raw.trim()) return [];
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((o): o is MenuOption => typeof o?.digito === 'string' && o.digito.trim() !== '')
      .map(o => ({ digito: o.digito, rotulo: typeof o.rotulo === 'string' ? o.rotulo : '' }));
  } catch {
    return [];
  }
}

/**
 * MenuNode — nó "menu_opcoes" (Fase 5c). Um handle de saída nomeado por opção (`opt-<digito>`),
 * mais os ramos fixos `opt-timeout`/`opt-invalido` — substitui o handle único genérico, que
 * obrigava o operador a digitar ids de aresta que a UI nem exibia.
 */
export function MenuNode({ data, selected }: MenuNodeProps) {
  const options = parseMenuOptions(data.properties.opcoesMenu);

  return (
    <div className={`flow-node flow-node-menu${selected ? ' flow-node-selected' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="flow-node-label">{data.label}</div>
      <div className="flow-node-type">menu_opcoes</div>
      {options.length === 0 && (
        <div className="flow-node-menu-hint">Sem opções configuradas — abra o painel de propriedades.</div>
      )}
      <div className="flow-node-menu-options">
        {options.map(opt => (
          <div key={opt.digito} className="flow-node-menu-option">
            <span>{opt.digito} — {opt.rotulo || '(sem rótulo)'}</span>
            <Handle type="source" position={Position.Right} id={`opt-${opt.digito}`} style={{ position: 'relative', transform: 'none', display: 'inline-block' }} />
          </div>
        ))}
        <div className="flow-node-menu-option">
          <span>Timeout</span>
          <Handle type="source" position={Position.Right} id="opt-timeout" style={{ position: 'relative', transform: 'none', display: 'inline-block' }} />
        </div>
        <div className="flow-node-menu-option">
          <span>Opção inválida</span>
          <Handle type="source" position={Position.Right} id="opt-invalido" style={{ position: 'relative', transform: 'none', display: 'inline-block' }} />
        </div>
      </div>
    </div>
  );
}
