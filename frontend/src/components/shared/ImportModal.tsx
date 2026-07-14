import { useRef, useState } from 'react';
import api, { getErrorMessage } from '../../api/client';

export interface ImportSummary {
  importados: number;
  erros: number;
  detalhes: { linha: number; erro: string }[];
}

interface ImportModalProps {
  title: string;
  importUrl: string;
  templateUrl: string;
  templateFilename: string;
  instructions: string[];
  onClose: () => void;
  onImported: () => void;
}

/** Baixa um blob de resposta da API como arquivo, sem manter o link na página. */
export function triggerDownload(data: Blob, filename: string) {
  const url = URL.createObjectURL(new Blob([data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  }));
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', filename);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Modal de importação em lote via planilha XLSX — baixar modelo, selecionar
 * arquivo (clique ou drag & drop) e importar, com resumo de importados/erros.
 * Mesmo padrão visual já usado em ModuloConectividade.tsx para testes de
 * conectividade; extraído para componente compartilhado no terceiro uso
 * (Números 0800 e Linhas).
 */
export default function ImportModal({ title, importUrl, templateUrl, templateFilename, instructions, onClose, onImported }: ImportModalProps) {
  const [file, setFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [result, setResult] = useState<ImportSummary | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const downloadTemplate = async () => {
    try {
      const res = await api.get(templateUrl, { responseType: 'blob' });
      triggerDownload(res.data, templateFilename);
    } catch {
      alert('Erro ao baixar o modelo.');
    }
  };

  const handleImport = async () => {
    if (!file) return;
    setImporting(true);
    setResult(null);
    const formData = new FormData();
    formData.append('file', file);
    try {
      const res = await api.post(importUrl, formData, { headers: { 'Content-Type': 'multipart/form-data' } });
      setResult(res.data);
      if (res.data.importados > 0) onImported();
    } catch (err) {
      setResult({
        importados: 0, erros: 1,
        detalhes: [{ linha: 0, erro: getErrorMessage(err, 'Erro ao enviar arquivo.') }],
      });
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" style={{ maxWidth: 600 }}>
        <div className="modal-header">
          <h2>📥 {title}</h2>
          <button className="btn-close" onClick={onClose}>×</button>
        </div>
        <div className="modal-body">

          <div style={{
            background: 'rgba(0,122,255,0.08)', border: '1px solid rgba(0,122,255,0.2)',
            borderRadius: 10, padding: '12px 16px', marginBottom: 20, fontSize: '0.83rem',
            color: 'var(--text-muted)', lineHeight: 1.7,
          }}>
            <div style={{ fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 }}>📋 Instruções</div>
            <ul style={{ paddingLeft: 16, margin: 0 }}>
              {instructions.map((inst, i) => <li key={i}>{inst}</li>)}
            </ul>
          </div>

          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginBottom: 20 }}>
            <button
              className="btn btn-ghost btn-sm"
              onClick={downloadTemplate}
              title="Baixar planilha modelo com os campos corretos e valores de referência"
              style={{ borderColor: 'rgba(52,199,89,0.4)', color: '#34c759', whiteSpace: 'nowrap', flexShrink: 0 }}
            >
              ⬇ Baixar Modelo .xlsx
            </button>

            <div style={{ flex: 1 }}>
              <input
                ref={fileInputRef}
                type="file"
                accept=".xlsx,.xls"
                style={{ display: 'none' }}
                onChange={e => { setFile(e.target.files?.[0] ?? null); setResult(null); }}
              />
              <div
                onClick={() => fileInputRef.current?.click()}
                style={{
                  border: `2px dashed ${file ? 'rgba(0,122,255,0.6)' : 'rgba(255,255,255,0.12)'}`,
                  borderRadius: 10, padding: '16px 20px', cursor: 'pointer',
                  textAlign: 'center', transition: 'all .2s',
                  background: file ? 'rgba(0,122,255,0.06)' : 'transparent',
                }}
                onDragOver={e => e.preventDefault()}
                onDrop={e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) { setFile(f); setResult(null); } }}
              >
                {file ? (
                  <div>
                    <div style={{ fontSize: '1.2rem', marginBottom: 4 }}>📄</div>
                    <div style={{ fontWeight: 500, fontSize: '0.88rem' }}>{file.name}</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 2 }}>
                      {(file.size / 1024).toFixed(1)} KB · Clique para trocar
                    </div>
                  </div>
                ) : (
                  <div>
                    <div style={{ fontSize: '1.5rem', marginBottom: 4 }}>📂</div>
                    <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                      Clique para selecionar ou arraste o arquivo aqui
                    </div>
                    <div style={{ fontSize: '0.75rem', color: 'rgba(148,163,184,0.5)', marginTop: 4 }}>
                      .xlsx · .xls
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>

          {result && (
            <div style={{
              background: result.erros === 0 ? 'rgba(52,199,89,0.08)' : 'rgba(255,159,10,0.08)',
              border: `1px solid ${result.erros === 0 ? 'rgba(52,199,89,0.3)' : 'rgba(255,159,10,0.3)'}`,
              borderRadius: 10, padding: '14px 18px', fontSize: '0.85rem',
            }}>
              <div style={{ display: 'flex', gap: 20, marginBottom: result.detalhes.length > 0 ? 12 : 0 }}>
                <div>
                  <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>Importados</div>
                  <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#34c759' }}>{result.importados}</div>
                </div>
                {result.erros > 0 && (
                  <div>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>Com erro</div>
                    <div style={{ fontSize: '1.4rem', fontWeight: 700, color: '#ff6b6b' }}>{result.erros}</div>
                  </div>
                )}
              </div>
              {result.detalhes.length > 0 && (
                <div style={{ maxHeight: 180, overflowY: 'auto' }}>
                  <div style={{ fontWeight: 500, marginBottom: 6, fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                    Detalhes dos erros:
                  </div>
                  {result.detalhes.map((d, i) => (
                    <div key={i} style={{
                      background: 'rgba(0,0,0,0.2)', borderRadius: 6, padding: '6px 10px',
                      marginBottom: 6, fontFamily: 'monospace', fontSize: '0.75rem',
                    }}>
                      <span style={{ color: '#ff6b6b' }}>Linha {d.linha}:</span>{' '}
                      <span style={{ color: '#ff9f0a' }}>{d.erro}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

        </div>
        <div className="modal-footer">
          <button className="btn btn-ghost" onClick={onClose}>{result ? 'Fechar' : 'Cancelar'}</button>
          {!result && (
            <button className="btn btn-primary" onClick={handleImport} disabled={!file || importing} style={{ minWidth: 130 }}>
              {importing
                ? <><span className="spinner" style={{ width: 12, height: 12, margin: '0 6px 0 0' }} />Importando…</>
                : '📥 Importar'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
