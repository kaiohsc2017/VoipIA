import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import { useAuthSession } from '../hooks/useAuthSession';
import type {
  CcKbArticle, CcKbArticleRequest, CcKbExternalSource, CcKbExternalSourceRequest,
  CcKbStatsView, CostAlertConfigView,
} from '../api/types';

const EMPTY_ARTICLE: CcKbArticleRequest = { title: '', body: '', tags: '' };

function formatUsd(value: number) {
  return `US$ ${value.toFixed(2)}`;
}

/** Alerta de gasto de IA da frente callcenter_autosservico (Fase 25, §25.4) — mesmo padrão de
 * NpsCostAlertPanel em PesquisasTab.tsx, duplicado aqui pelo mesmo motivo (esta SPA não tem
 * acesso ao módulo Financeiro do shell Telecom). Embedding é local/CPU (custo zero) — só a
 * geração final via Gemini gera gasto real. */
function KbCostAlertPanel() {
  const { hasWrite } = useAuthSession();
  const canEdit = hasWrite('financeiro.callcenter_autosservico');
  const [config, setConfig] = useState<CostAlertConfigView | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [threshold, setThreshold] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api.get<CostAlertConfigView>('/financeiro/cost-alerts/callcenter_autosservico')
      .then(({ data }) => { setConfig(data); setEnabled(data.enabled); setThreshold(String(data.thresholdUsd)); })
      .catch(() => setConfig(null));
  }, []);

  const save = () => {
    const thresholdUsd = Number(threshold);
    if (!Number.isFinite(thresholdUsd) || thresholdUsd < 0) return;
    setSaving(true);
    api.put<CostAlertConfigView>('/financeiro/cost-alerts/callcenter_autosservico', { thresholdUsd, enabled })
      .then(({ data }) => setConfig(data))
      .finally(() => setSaving(false));
  };

  return (
    <div className="card" style={{ marginTop: 24, maxWidth: 480 }}>
      <h3 style={{ marginBottom: 4, fontSize: '0.95rem' }}>🔔 Alerta de gasto de IA (Financeiro)</h3>
      <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 16 }}>
        Custo da resposta final gerada pelo nó "Consultar base de conhecimento" do chat — a busca
        vetorial em si (embeddings) roda localmente, sem custo. Ultrapassar o limite mensal
        dispara um alerta único no Telegram.
      </p>
      <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.85rem', marginBottom: 14 }}>
        <input type="checkbox" checked={enabled} disabled={!canEdit} onChange={e => setEnabled(e.target.checked)} />
        Habilitar alerta
      </label>
      <div className="form-group" style={{ marginBottom: 16 }}>
        <label className="form-label">Limite mensal (USD)</label>
        <input type="number" min="0" step="0.01" className="form-input" style={{ maxWidth: 200 }}
          value={threshold} disabled={!canEdit} onChange={e => setThreshold(e.target.value)} />
      </div>
      {config && (
        <p style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: 16 }}>
          Gasto no mês corrente: <strong>{formatUsd(config.currentMonthSpendUsd)}</strong>
        </p>
      )}
      {canEdit && <button className="btn btn-primary btn-sm" disabled={saving} onClick={save}>Salvar</button>}
    </div>
  );
}

function ContainmentStatsCard() {
  const [stats, setStats] = useState<CcKbStatsView | null>(null);

  useEffect(() => {
    api.get<CcKbStatsView>('/callcenter/kb/stats').then(({ data }) => setStats(data)).catch(() => setStats(null));
  }, []);

  if (!stats) return null;
  return (
    <div className="card" style={{ marginBottom: 20, maxWidth: 480 }}>
      <h3 style={{ marginBottom: 4, fontSize: '0.95rem' }}>📊 Taxa de contenção do bot (mês corrente)</h3>
      <p style={{ fontSize: '1.4rem', fontWeight: 600 }}>{(stats.containmentRate * 100).toFixed(1)}%</p>
      <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
        {stats.matched} de {stats.total} pergunta(s) respondidas sem escalar para fila humana.
      </p>
    </div>
  );
}

function ExternalSourcesSection({ canWrite }: { canWrite: boolean }) {
  const [sources, setSources] = useState<CcKbExternalSource[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [url, setUrl] = useState('');
  const [msg, setMsg] = useState('');
  const [confirmSource, setConfirmSource] = useState<CcKbExternalSource | null>(null);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<CcKbExternalSource[]>('/callcenter/kb/sources').then(({ data }) => setSources(data)).catch(() => setSources([]));
  };
  useEffect(load, []);

  const save = () => {
    const req: CcKbExternalSourceRequest = { url };
    api.post('/callcenter/kb/sources', req)
      .then(() => { load(); setShowForm(false); setUrl(''); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao cadastrar fonte externa.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/kb/sources/${id}`)
      .then(() => setSources(list => list.filter(s => s.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover fonte externa.')));
  };

  return (
    <div style={{ marginTop: 32 }}>
      <div className="flex items-center justify-between" style={{ marginBottom: 12 }}>
        <h2 style={{ fontSize: '1.1rem' }}>Fontes externas (URL)</h2>
        {canWrite && <button className="btn btn-primary btn-sm" onClick={() => setShowForm(true)}><Plus size={14} /> Nova fonte</button>}
      </div>
      <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 12 }}>
        Buscadas e indexadas 1x/dia — nunca ao vivo durante uma conversa. Falha de busca não
        apaga o índice anterior daquela fonte.
      </p>
      {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
      {confirmSource && (
        <ConfirmModal
          message={`Remover a fonte "${confirmSource.url}"?`}
          onConfirm={() => { del(confirmSource.id); setConfirmSource(null); }}
          onCancel={() => setConfirmSource(null)}
        />
      )}
      {canWrite && showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Nova fonte externa</h2>
              <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
            </div>
            <div className="modal-body">
              <div className="form-group">
                <label className="form-label">URL</label>
                <input className="form-input" value={url} onChange={e => setUrl(e.target.value)} placeholder="https://..." />
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
              <button className="btn btn-primary" onClick={save} disabled={!url.trim()}>Salvar</button>
            </div>
          </div>
        </div>
      )}
      <div className="table-wrapper">
        <table>
          <thead>
            <tr><th>URL</th><th>Última busca</th><th>Status</th>{canWrite && <th></th>}</tr>
          </thead>
          <tbody>
            {sources.map(s => (
              <tr key={s.id}>
                <td>{s.url}</td>
                <td>{s.lastFetchedAt ? new Date(s.lastFetchedAt).toLocaleString('pt-BR') : '—'}</td>
                <td>
                  {s.lastFetchSuccess === null ? '—' : s.lastFetchSuccess ? '✅ OK' : `❌ ${s.lastFetchError ?? 'falha'}`}
                </td>
                {canWrite && (
                  <td><button className="btn btn-ghost btn-sm" onClick={() => setConfirmSource(s)}><Trash2 size={14} /></button></td>
                )}
              </tr>
            ))}
            {sources.length === 0 && (
              <tr><td colSpan={canWrite ? 4 : 3} className="table-empty">Nenhuma fonte externa cadastrada.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function KbTab({ canWrite }: { canWrite: boolean }) {
  const [articles, setArticles] = useState<CcKbArticle[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<CcKbArticle | null>(null);
  const [confirmArticle, setConfirmArticle] = useState<CcKbArticle | null>(null);
  const [msg, setMsg] = useState('');
  const [fd, setFd] = useState<CcKbArticleRequest>(EMPTY_ARTICLE);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 4000); };
  const load = () => {
    api.get<CcKbArticle[]>('/callcenter/kb/articles').then(({ data }) => setArticles(data)).catch(() => setArticles([]));
  };
  useEffect(load, []);

  const openForm = (a: CcKbArticle | null) => {
    setEditing(a);
    setFd(a ? { title: a.title, body: a.body, tags: a.tags ?? '' } : EMPTY_ARTICLE);
    setShowForm(true);
  };

  const save = () => {
    const req = editing
      ? api.put(`/callcenter/kb/articles/${editing.id}`, fd)
      : api.post('/callcenter/kb/articles', fd);
    req.then(() => { load(); setShowForm(false); setEditing(null); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao salvar artigo.')));
  };

  const del = (id: number) => {
    api.delete(`/callcenter/kb/articles/${id}`)
      .then(() => load())
      .catch(err => flash(getErrorMessage(err, 'Erro ao desativar artigo.')));
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div>
            <h1>Base de Conhecimento</h1>
            <p>Artigos e fontes externas consultados pelo nó "Consultar base de conhecimento" do chat (IA de autosserviço)</p>
          </div>
          {canWrite && <button className="btn btn-primary" onClick={() => openForm(null)}><Plus size={14} /> Novo artigo</button>}
        </div>
      </div>
      <div className="page-body">
        <ContainmentStatsCard />
        {msg && <div className="flash-message" style={{ background: 'var(--bg-danger-soft)', color: 'var(--clr-danger)' }}>{msg}</div>}
        {confirmArticle && (
          <ConfirmModal
            message={`Desativar o artigo "${confirmArticle.title}"? Ele para de aparecer em buscas do bot.`}
            onConfirm={() => { del(confirmArticle.id); setConfirmArticle(null); }}
            onCancel={() => setConfirmArticle(null)}
          />
        )}
        {canWrite && showForm && (
          <div className="modal-overlay" onClick={() => setShowForm(false)}>
            <div className="modal" onClick={e => e.stopPropagation()}>
              <div className="modal-header">
                <h2>{editing ? 'Editar artigo' : 'Novo artigo'}</h2>
                <button className="btn-close" onClick={() => setShowForm(false)}>×</button>
              </div>
              <div className="modal-body">
                <div className="form-group">
                  <label className="form-label">Título</label>
                  <input className="form-input" value={fd.title} onChange={e => setFd(f => ({ ...f, title: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Corpo</label>
                  <textarea className="form-input" rows={8} value={fd.body} onChange={e => setFd(f => ({ ...f, body: e.target.value }))} />
                </div>
                <div className="form-group">
                  <label className="form-label">Tags (opcional)</label>
                  <input className="form-input" value={fd.tags ?? ''} onChange={e => setFd(f => ({ ...f, tags: e.target.value }))} />
                </div>
              </div>
              <div className="modal-footer">
                <button className="btn btn-ghost" onClick={() => setShowForm(false)}>Cancelar</button>
                <button className="btn btn-primary" onClick={save} disabled={!fd.title.trim() || !fd.body.trim()}>Salvar</button>
              </div>
            </div>
          </div>
        )}
        <div className="table-wrapper">
          <table>
            <thead>
              <tr><th>Título</th><th>Tags</th><th>Versão</th><th>Indexado</th><th>Ativo</th>{canWrite && <th></th>}</tr>
            </thead>
            <tbody>
              {articles.map(a => (
                <tr key={a.id}>
                  <td>{a.title}</td>
                  <td>{a.tags}</td>
                  <td>{a.version}</td>
                  <td>{a.indexedVersion === a.version ? '✅' : '⏳ pendente'}</td>
                  <td>{a.active ? '✅' : '—'}</td>
                  {canWrite && (
                    <td>
                      <button className="btn btn-ghost btn-sm" onClick={() => openForm(a)}><Pencil size={14} /></button>
                      {a.active && <button className="btn btn-ghost btn-sm" onClick={() => setConfirmArticle(a)}><Trash2 size={14} /></button>}
                    </td>
                  )}
                </tr>
              ))}
              {articles.length === 0 && (
                <tr><td colSpan={canWrite ? 6 : 5} className="table-empty">Nenhum artigo cadastrado.</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <ExternalSourcesSection canWrite={canWrite} />
        <KbCostAlertPanel />
      </div>
    </>
  );
}
