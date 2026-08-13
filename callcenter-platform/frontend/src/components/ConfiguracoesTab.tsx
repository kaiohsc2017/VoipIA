import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type {
  CcPauseReason, PauseReasonRequest, CcDisposition, DispositionRequest,
  CcRangeType, CcSettingsView, CcUpdateRangeResult,
} from '../api/types';

const EMPTY_PAUSE: PauseReasonRequest = { code: '', label: '', productive: false, active: true };
const EMPTY_DISPOSITION: DispositionRequest = { code: '', label: '', active: true };

/**
 * ConfiguracoesTab — CRUD de motivos de pausa e tabulações (Fase 12.6). Até esta entrega só
 * existia o seed da V47/catálogo inicial, sem UI — a tabulação é obrigatória para o agente sair
 * do ACW (Fase 4), então sem esta tela a operação real ficava presa ao seed.
 */
export function ConfiguracoesTab({ canWrite }: { canWrite: boolean }) {
  return (
    <>
      <div className="page-header">
        <h1>Configurações do Call Center</h1>
        <p>Motivos de pausa (Desktop do Agente) e tabulações (encerramento do ACW)</p>
      </div>
      <div className="page-body">
        <RangesAndNpsSection canWrite={canWrite} />
        <div style={{ height: 32 }} />
        <PauseReasonsSection canWrite={canWrite} />
        <div style={{ height: 32 }} />
        <DispositionsSection canWrite={canWrite} />
      </div>
    </>
  );
}

/**
 * RangesAndNpsSection — ranges de ramal de agente/fila/fluxo (Fase 19 do plano Call Center Parte
 * III) e o interruptor global de pesquisa de satisfação (consumido pela Fase 21). Mudar um range
 * nunca realoca ramal existente (D20) — a tela só avisa quantos ficam fora da faixa nova.
 */
function RangesAndNpsSection({ canWrite }: { canWrite: boolean }) {
  const [settings, setSettings] = useState<CcSettingsView | null>(null);
  const [editing, setEditing] = useState<{ type: CcRangeType; start: string; end: string } | null>(null);
  const [warning, setWarning] = useState<{ type: CcRangeType; outsideCount: number } | null>(null);
  const [msg, setMsg] = useState('');

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 5000); };
  const flashWarning = (w: { type: CcRangeType; outsideCount: number }) => {
    setWarning(w);
    setTimeout(() => setWarning(null), 8000);
  };
  const load = () => {
    api.get<CcSettingsView>('/callcenter/settings')
      .then(({ data }) => setSettings(data))
      .catch(() => setSettings(null));
  };
  useEffect(load, []);

  const rangeOf = (type: CcRangeType) =>
    settings && (type === 'AGENT' ? settings.agentRange : type === 'QUEUE' ? settings.queueRange : settings.flowRange);

  const openEdit = (type: CcRangeType) => {
    const range = rangeOf(type);
    if (!range) return;
    setEditing({ type, start: String(range.start), end: String(range.end) });
    setWarning(null);
  };

  const editingStart = editing ? Number(editing.start) : NaN;
  const editingEnd = editing ? Number(editing.end) : NaN;
  const editingIsValid = Number.isFinite(editingStart) && Number.isFinite(editingEnd) && editingStart < editingEnd;

  const saveRange = () => {
    if (!editing || !editingIsValid) return;
    api.put<CcUpdateRangeResult>(`/callcenter/settings/ranges/${editing.type.toLowerCase()}`, { start: editingStart, end: editingEnd })
      .then(({ data }) => {
        load();
        flashWarning({ type: editing.type, outsideCount: data.extensionsOutsideRange });
        setEditing(null);
      })
      .catch(err => flash(getErrorMessage(err, 'Erro ao atualizar range.')));
  };

  const toggleNps = (enabled: boolean) => {
    api.put('/callcenter/settings/nps-enabled', { enabled })
      .then(load)
      .catch(err => flash(getErrorMessage(err, 'Erro ao atualizar o interruptor de NPS.')));
  };

  if (!settings) return null;

  return (
    <div>
      <div className="flex items-center justify-between">
        <h2 style={{ margin: 0 }}>Ranges de ramal e pesquisa de satisfação</h2>
      </div>
      {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
      {warning && (
        <div className="flash-message" style={{ background: 'var(--bg-warning-soft, #fff8e1)', color: 'var(--clr-warning, #a06a00)' }}>
          Range atualizado. {warning.outsideCount > 0
            ? `${warning.outsideCount} ramal(is) ativo(s) ficaram fora da nova faixa — nada foi realocado, eles continuam funcionando com o ramal atual.`
            : 'Nenhum ramal ativo ficou fora da nova faixa.'}
        </div>
      )}
      <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 4 }}>
        Mudar um range vale só para as próximas alocações — ninguém que já tem ramal é realocado.
        Cada faixa precisa ser um bloco de milhar completo (ex.: 4000-4999), é o único formato que
        o dialplan atual roteia sem edição manual.
      </p>
      <div className="table-wrapper">
        <table>
          <thead><tr><th>Faixa</th><th>Início</th><th>Fim</th>{canWrite && <th></th>}</tr></thead>
          <tbody>
            {(['AGENT', 'QUEUE', 'FLOW'] as CcRangeType[]).map(type => {
              const range = rangeOf(type);
              if (!range) return null;
              return (
                <tr key={type}>
                  <td>{range.label}</td>
                  <td>{range.start}</td>
                  <td>{range.end}</td>
                  {canWrite && (
                    <td>
                      <button className="btn btn-ghost btn-sm" onClick={() => openEdit(type)}><Pencil size={14} /></button>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div style={{ marginTop: 20 }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            type="checkbox"
            checked={settings.npsEnabledGlobally}
            disabled={!canWrite}
            onChange={e => toggleNps(e.target.checked)}
          />
          Pesquisa de satisfação (NPS) ativada globalmente
        </label>
        <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 4 }}>
          Desligado aqui, nenhuma fila pesquisa, mesmo que tenha uma pesquisa configurada. Ligado,
          cada fila decide se e qual pesquisa usar (aba Filas).
        </p>
      </div>

      {canWrite && editing && (
        <div className="modal-overlay" onClick={() => setEditing(null)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Editar faixa de {rangeOf(editing.type)?.label}</h2>
              <button className="btn-close" onClick={() => setEditing(null)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Início</label>
                  <input
                    className="form-input" type="number" value={editing.start}
                    onChange={e => setEditing(v => v && ({ ...v, start: e.target.value }))}
                  />
                </div>
                <div className="form-group">
                  <label className="form-label">Fim</label>
                  <input
                    className="form-input" type="number" value={editing.end}
                    onChange={e => setEditing(v => v && ({ ...v, end: e.target.value }))}
                  />
                </div>
              </div>
            </div>
            {!editingIsValid && (
              <p style={{ color: 'var(--clr-danger)', fontSize: 13, margin: '0 16px' }}>
                Início e fim devem ser números válidos, com início menor que fim.
              </p>
            )}
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setEditing(null)}>Cancelar</button>
              <button className="btn btn-primary" onClick={saveRange} disabled={!editingIsValid}>Salvar</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function PauseReasonsSection({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<CcPauseReason[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcPauseReason | null>(null);
  const [confirmItem, setConfirmItem] = useState<CcPauseReason | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<PauseReasonRequest>(EMPTY_PAUSE);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<CcPauseReason[]>('/callcenter/pause-reasons')
      .then(({ data }) => setItems(data))
      .catch(() => setItems([]));
  };
  useEffect(load, []);

  const openForm = (r: CcPauseReason | null) => {
    setEditing(r);
    setFd(r ? { code: r.code, label: r.label, productive: r.productive, active: r.active } : EMPTY_PAUSE);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/pause-reasons/${editing.id}`, fd)
      : api.post('/callcenter/pause-reasons', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar motivo de pausa.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/pause-reasons/${id}`)
      .then(() => setItems(list => list.filter(r => r.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover motivo de pausa.')));
  };

  return (
    <div>
      <div className="flex items-center justify-between">
        <h2 style={{ margin: 0 }}>Motivos de pausa</h2>
        {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo motivo</button>}
      </div>
      {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
      {confirmItem && (
        <ConfirmModal
          message={`Remover o motivo de pausa "${confirmItem.label}"?`}
          onConfirm={() => { del(confirmItem.id); setConfirmItem(null); }}
          onCancel={() => setConfirmItem(null)}
        />
      )}
      {canWrite && showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editing ? 'Editar Motivo de Pausa' : 'Novo Motivo de Pausa'}</h2>
              <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Código</label>
                  <input className="form-input" value={fd.code} onChange={e => setFd(f => ({ ...f, code: e.target.value.toUpperCase() }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Rótulo</label>
                  <input className="form-input" value={fd.label} onChange={e => setFd(f => ({ ...f, label: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={!!fd.productive} onChange={e => setFd(f => ({ ...f, productive: e.target.checked }))} />
                    Produtiva
                  </label>
                </div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={fd.active !== false} onChange={e => setFd(f => ({ ...f, active: e.target.checked }))} />
                    Ativo
                  </label>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={!fd.code.trim() || !fd.label.trim()}>Salvar</button>
            </div>
          </div>
        </div>
      )}
      <div className="table-wrapper">
        <table>
          <thead><tr><th>Código</th><th>Rótulo</th><th>Produtiva</th><th>Ativo</th>{canWrite && <th></th>}</tr></thead>
          <tbody>
            {items.map(r => (
              <tr key={r.id}>
                <td>{r.code}</td>
                <td>{r.label}</td>
                <td>{r.productive ? 'Sim' : 'Não'}</td>
                <td>{r.active ? 'Sim' : 'Não'}</td>
                {canWrite && (
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => openForm(r)}><Pencil size={14} /></button>
                    <button className="btn btn-ghost btn-sm" onClick={() => setConfirmItem(r)}><Trash2 size={14} /></button>
                  </td>
                )}
              </tr>
            ))}
            {items.length === 0 && <tr><td colSpan={canWrite ? 5 : 4} className="table-empty">Nenhum motivo de pausa cadastrado.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function DispositionsSection({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<CcDisposition[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcDisposition | null>(null);
  const [confirmItem, setConfirmItem] = useState<CcDisposition | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<DispositionRequest>(EMPTY_DISPOSITION);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<CcDisposition[]>('/callcenter/dispositions')
      .then(({ data }) => setItems(data))
      .catch(() => setItems([]));
  };
  useEffect(load, []);

  const openForm = (d: CcDisposition | null) => {
    setEditing(d);
    setFd(d ? { code: d.code, label: d.label, active: d.active } : EMPTY_DISPOSITION);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/dispositions/${editing.id}`, fd)
      : api.post('/callcenter/dispositions', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar tabulação.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/dispositions/${id}`)
      .then(() => setItems(list => list.filter(d => d.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover tabulação.')));
  };

  return (
    <div>
      <div className="flex items-center justify-between">
        <h2 style={{ margin: 0 }}>Tabulações</h2>
        {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Nova tabulação</button>}
      </div>
      {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
      {confirmItem && (
        <ConfirmModal
          message={`Remover a tabulação "${confirmItem.label}"?`}
          onConfirm={() => { del(confirmItem.id); setConfirmItem(null); }}
          onCancel={() => setConfirmItem(null)}
        />
      )}
      {canWrite && showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editing ? 'Editar Tabulação' : 'Nova Tabulação'}</h2>
              <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-grid">
                <div className="form-group">
                  <label className="form-label">Código</label>
                  <input className="form-input" value={fd.code} onChange={e => setFd(f => ({ ...f, code: e.target.value.toUpperCase() }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Rótulo</label>
                  <input className="form-input" value={fd.label} onChange={e => setFd(f => ({ ...f, label: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <input type="checkbox" checked={fd.active !== false} onChange={e => setFd(f => ({ ...f, active: e.target.checked }))} />
                    Ativo
                  </label>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={!fd.code.trim() || !fd.label.trim()}>Salvar</button>
            </div>
          </div>
        </div>
      )}
      <div className="table-wrapper">
        <table>
          <thead><tr><th>Código</th><th>Rótulo</th><th>Ativo</th>{canWrite && <th></th>}</tr></thead>
          <tbody>
            {items.map(d => (
              <tr key={d.id}>
                <td>{d.code}</td>
                <td>{d.label}</td>
                <td>{d.active ? 'Sim' : 'Não'}</td>
                {canWrite && (
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => openForm(d)}><Pencil size={14} /></button>
                    <button className="btn btn-ghost btn-sm" onClick={() => setConfirmItem(d)}><Trash2 size={14} /></button>
                  </td>
                )}
              </tr>
            ))}
            {items.length === 0 && <tr><td colSpan={canWrite ? 4 : 3} className="table-empty">Nenhuma tabulação cadastrada.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
