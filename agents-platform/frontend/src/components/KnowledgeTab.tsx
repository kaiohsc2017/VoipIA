import { useEffect, useRef, useState } from 'react';
import { Upload, Trash2 } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import { ConfirmModal } from './ConfirmModal';
import type { KnowledgeDoc, PaginatedResponse } from '../api/types';

export function KnowledgeTab({ canWrite }: { canWrite: boolean }) {
  const [docs, setDocs] = useState<KnowledgeDoc[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [confirmDoc, setConfirmDoc] = useState<KnowledgeDoc | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const load = () => {
    setLoading(true);
    api.get<PaginatedResponse<KnowledgeDoc> | KnowledgeDoc[]>('/api/knowledge/?limit=200')
      .then(({ data }) => setDocs(Array.isArray(data) ? data : data.items))
      .catch(() => setDocs([]))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const flash = (m: string) => { setMsg(m); setTimeout(() => setMsg(''), 3000); };

  const upload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    // Sobrescreve o Content-Type padrão da instância (application/json) — sem
    // isso, o transformRequest do axios serializa o FormData como JSON (perde
    // o arquivo). 'multipart/form-data' sem boundary é proposital: o adapter
    // do axios detecta e deixa o navegador completar com o boundary correto.
    api.post<KnowledgeDoc>('/api/knowledge/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
      .then(({ data }) => { setDocs(d => [...d, data]); flash(`"${file.name}" adicionado.`); })
      .catch(err => flash(getErrorMessage(err, 'Erro ao enviar arquivo.')))
      .finally(() => { if (fileRef.current) fileRef.current.value = ''; });
  };

  const del = (id: string) => {
    api.delete(`/api/knowledge/${id}`)
      .then(() => setDocs(d => d.filter(x => x.id !== id)))
      .catch(err => flash(getErrorMessage(err, 'Erro ao remover documento.')));
  };

  return (
    <>
      <div className="page-header">
        <div className="flex items-center justify-between">
          <div>
            <h1>Base de Conhecimento</h1>
            <p>Documentos PDF consultados pelos agentes em diagnósticos</p>
          </div>
          {canWrite && (
            <button className="btn btn-primary" onClick={() => fileRef.current?.click()}>
              <Upload size={14} /> Adicionar PDF
            </button>
          )}
          <input ref={fileRef} type="file" accept=".pdf" aria-label="Selecionar arquivo PDF" style={{ display: 'none' }} onChange={upload} />
        </div>
      </div>
      <div className="page-body">
        {confirmDoc && (
          <ConfirmModal
            message={`Remover "${confirmDoc.filename}"?`}
            onConfirm={() => { del(confirmDoc.id); setConfirmDoc(null); }}
            onCancel={() => setConfirmDoc(null)}
          />
        )}
        {msg && <div className="flash-message">{msg}</div>}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Documentos</span>
            <span className="text-muted" style={{ fontSize: '0.8rem' }}>{docs.length} doc(s)</span>
          </div>
          <div className="table-wrapper">
            <table>
              <thead><tr><th>Arquivo</th><th>Tags</th><th>Adicionado</th><th /></tr></thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={4} className="table-empty">Carregando...</td></tr>
                ) : docs.length === 0 ? (
                  <tr><td colSpan={4} className="table-empty">Nenhum PDF adicionado</td></tr>
                ) : docs.map(d => (
                  <tr key={d.id}>
                    <td><div style={{ fontWeight: 500 }}>{d.title || d.filename}</div><div className="td-muted" style={{ fontSize: '0.72rem' }}>{d.filename}</div></td>
                    <td>{(d.tags || []).map(t => <span key={t} className="chip" style={{ marginRight: 4 }}>{t}</span>)}</td>
                    <td className="td-muted">{new Date(d.created_at).toLocaleDateString('pt-BR')}</td>
                    <td>
                      {canWrite && (
                        <button className="btn btn-sm btn-danger" onClick={() => setConfirmDoc(d)}><Trash2 size={12} /></button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}
