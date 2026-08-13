import { useEffect, useState } from 'react';
import type { Node } from '@xyflow/react';
import type { FlowGraphNodeType, CcAudioFile } from '../../api/types';
import type { GenericNodeData } from './nodes/GenericNode';
import { parseMenuOptions, type MenuOption } from './nodes/MenuNode';
import api, { getErrorMessage } from '../../api/client';
import { AuthedAudio } from '../AuthedAudio';

interface NodePropertiesPanelProps {
  node: Node<GenericNodeData> | null;
  catalog: FlowGraphNodeType[];
  onChange: (nodeId: string, properties: Record<string, string | number | boolean>) => void;
  onDelete: (nodeId: string) => void;
}

/**
 * NodePropertiesPanel — renderiza os campos do nó selecionado de forma genérica, a partir do
 * `properties` que o catálogo (backend) descreve para aquele tipo. Fase 5c somou dois tipos ao
 * `string|number|boolean|select` original: `audio` (seleciona/faz upload na biblioteca do Flow
 * Builder) e `keypad` (editor de opções dígito→rótulo do nó de menu).
 */
export function NodePropertiesPanel({ node, catalog, onChange, onDelete }: NodePropertiesPanelProps) {
  const [audios, setAudios] = useState<CcAudioFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');

  const entry = catalog.find(c => c.type === node?.data.nodeType);
  const needsAudioLibrary = (entry?.properties ?? []).some(p => p.type === 'audio');

  useEffect(() => {
    if (!needsAudioLibrary) return;
    api.get<CcAudioFile[]>('/callcenter/audios').then(({ data }) => setAudios(data)).catch(() => setAudios([]));
  }, [needsAudioLibrary]);

  if (!node) {
    return (
      <aside className="flow-properties">
        <p className="flow-palette-hint">Selecione um nó para editar suas propriedades.</p>
      </aside>
    );
  }

  const properties = entry?.properties ?? [];

  const setProperty = (name: string, value: string | number | boolean) => {
    onChange(node.id, { ...node.data.properties, [name]: value });
  };

  const uploadAudio = async (name: string, file: File) => {
    setUploading(true);
    setUploadError('');
    try {
      const form = new FormData();
      form.append('file', file);
      form.append('name', name || file.name);
      const { data } = await api.post<CcAudioFile>('/callcenter/audios', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setAudios(prev => [...prev, data].sort((a, b) => a.name.localeCompare(b.name)));
      setProperty(currentAudioPropertyName, String(data.id));
    } catch (err) {
      setUploadError(getErrorMessage(err, 'Falha ao enviar áudio.'));
    } finally {
      setUploading(false);
    }
  };

  // Só um campo de áudio por tipo de nó nesta fase (audioPath) — guarda o nome pra o upload
  // já selecionar o property certo.
  const currentAudioPropertyName = properties.find(p => p.type === 'audio')?.name ?? 'audioPath';

  return (
    <aside className="flow-properties">
      <h3 className="flow-palette-title">{node.data.label}</h3>
      <p className="flow-palette-hint">Tipo: {node.data.nodeType}</p>
      {properties.length === 0 && <p className="flow-palette-hint">Este nó não tem propriedades configuráveis.</p>}
      {properties.map(p => {
        const inputId = `flow-node-prop-${node.id}-${p.name}`;
        const value = node.data.properties[p.name];

        if (p.type === 'boolean') {
          return (
            <div className="form-group" key={p.name}>
              <label className="form-label" htmlFor={inputId}>{p.label}</label>
              <input id={inputId} type="checkbox" checked={Boolean(value)} onChange={e => setProperty(p.name, e.target.checked)} />
            </div>
          );
        }

        if (p.type === 'select' && p.options && p.options.length > 0) {
          return (
            <div className="form-group" key={p.name}>
              <label className="form-label" htmlFor={inputId}>{p.label}{p.required && ' *'}</label>
              <select id={inputId} className="form-input" value={String(value ?? '')} onChange={e => setProperty(p.name, e.target.value)}>
                <option value="">Selecione…</option>
                {p.options.map(opt => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </div>
          );
        }

        if (p.type === 'audio') {
          const selected = audios.find(a => String(a.id) === String(value ?? ''));
          return (
            <div className="form-group" key={p.name}>
              <label className="form-label" htmlFor={inputId}>{p.label}</label>
              <select id={inputId} className="form-input" value={String(value ?? '')} onChange={e => setProperty(p.name, e.target.value)}>
                <option value="">Nenhum</option>
                {audios.map(a => (
                  <option key={a.id} value={a.id}>{a.name}</option>
                ))}
              </select>
              {selected && <AuthedAudio path={`/callcenter/audios/${selected.id}/stream`} />}
              <label className="btn btn-ghost btn-sm" style={{ marginTop: 6, display: 'inline-block' }}>
                {uploading ? 'Enviando…' : 'Enviar novo áudio'}
                <input
                  type="file"
                  accept="audio/*"
                  style={{ display: 'none' }}
                  disabled={uploading}
                  onChange={e => {
                    const file = e.target.files?.[0];
                    if (file) uploadAudio(file.name.replace(/\.[^.]+$/, ''), file);
                    e.target.value = '';
                  }}
                />
              </label>
              {uploadError && <p className="flow-palette-hint" style={{ color: 'var(--clr-danger)' }}>{uploadError}</p>}
            </div>
          );
        }

        if (p.type === 'keypad') {
          return <MenuOptionsEditor key={p.name} inputId={inputId} label={p.label} value={typeof value === 'string' ? value : ''} onChange={v => setProperty(p.name, v)} />;
        }

        return (
          <div className="form-group" key={p.name}>
            <label className="form-label" htmlFor={inputId}>{p.label}</label>
            <input
              id={inputId}
              className="form-input"
              type={p.type === 'number' ? 'number' : 'text'}
              value={String(value ?? '')}
              onChange={e => setProperty(p.name, p.type === 'number' ? Number(e.target.value) : e.target.value)}
            />
          </div>
        );
      })}
      <button className="btn btn-danger btn-sm" onClick={() => onDelete(node.id)}>Excluir nó</button>
    </aside>
  );
}

interface EditableOption extends MenuOption {
  rowId: string;
}

let optionRowSeq = 0;
const nextRowId = () => `row-${Date.now()}-${optionRowSeq++}`;

/** Editor de opções dígito→rótulo do nó menu_opcoes (Fase 5c) — persiste como JSON string na
 * propriedade `opcoesMenu` (mesmo formato lido por FlowGraphValidator/MenuNodeHandler).
 *
 * Estado local próprio (não deriva de `parseMenuOptions(value)` a cada render): essa função
 * descarta dígito vazio de propósito (é o parser "não quebra com dado malformado" usado pelo
 * MenuNode pra desenhar handles) — reusá-la como fonte de verdade do formulário apagava a linha
 * inteira no instante em que o operador limpava o dígito pra redigitar (achado de revisão). O
 * dígito só é validado como duplicado contra as OUTRAS linhas, nunca contra ele mesmo. */
function MenuOptionsEditor({ inputId, label, value, onChange }: { inputId: string; label: string; value: string; onChange: (v: string) => void }) {
  const [options, setOptions] = useState<EditableOption[]>(() => parseMenuOptions(value).map(o => ({ ...o, rowId: nextRowId() })));

  const persist = (next: EditableOption[]) => {
    setOptions(next);
    onChange(JSON.stringify(next.map(({ digito, rotulo }) => ({ digito, rotulo }))));
  };

  const addOption = () => {
    const usedDigits = new Set(options.map(o => o.digito));
    const nextDigit = '0123456789'.split('').find(d => !usedDigits.has(d)) ?? '';
    persist([...options, { rowId: nextRowId(), digito: nextDigit, rotulo: '' }]);
  };

  const updateOption = (rowId: string, field: keyof MenuOption, v: string) => {
    if (field === 'digito' && v !== '' && options.some(o => o.rowId !== rowId && o.digito === v)) {
      return; // dígito já usado por outra opção — recusa a tecla em vez de gerar handle duplicado
    }
    persist(options.map(o => (o.rowId === rowId ? { ...o, [field]: v } : o)));
  };

  const removeOption = (rowId: string) => {
    persist(options.filter(o => o.rowId !== rowId));
  };

  return (
    <div className="form-group" key={inputId}>
      <label className="form-label">{label}</label>
      {options.map((opt, i) => (
        <div key={opt.rowId} style={{ display: 'flex', gap: 6, marginBottom: 4 }}>
          <input
            className="form-input"
            style={{ width: 48 }}
            maxLength={1}
            value={opt.digito}
            onChange={e => updateOption(opt.rowId, 'digito', e.target.value.replace(/[^0-9*#]/g, ''))}
            aria-label={`Dígito da opção ${i + 1}`}
          />
          <input
            className="form-input"
            value={opt.rotulo}
            onChange={e => updateOption(opt.rowId, 'rotulo', e.target.value)}
            placeholder="Rótulo (ex.: Vendas)"
            aria-label={`Rótulo da opção ${i + 1}`}
          />
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => removeOption(opt.rowId)}>Remover</button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={addOption}>+ Adicionar opção</button>
    </div>
  );
}
