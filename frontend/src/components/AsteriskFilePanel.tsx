import React from 'react';

interface AsteriskFilePanelProps {
  panelId: string;
  icon: string;
  title: string;
  description: React.ReactNode;
  hint: React.ReactNode;
  value: string;
  original: string;
  saving: boolean;
  isLoading?: boolean;
  reloadStatus: string;
  reloadLabel: string;
  saveLabel: string;
  open: boolean;
  minRows?: number;
  onToggle: () => void;
  onChange: (v: string) => void;
  onDiscard: () => void;
  onSave: () => void;
}

export function AsteriskFilePanel({
  panelId, icon, title, description, hint,
  value, original, saving, isLoading = false, reloadStatus, reloadLabel, saveLabel,
  open, minRows = 12, onToggle, onChange, onDiscard, onSave,
}: AsteriskFilePanelProps) {
  const changed = value !== original;

  return (
    <div className="stat-card" style={{ padding: 0, overflow: 'hidden' }}>

      {/* Cabeçalho */}
      <button
        onClick={onToggle}
        style={{
          width: '100%', display: 'flex', alignItems: 'center',
          gap: 12, padding: '16px 20px', background: 'none',
          border: 'none', cursor: 'pointer', textAlign: 'left',
          color: 'var(--text-primary)',
        }}
      >
        <span style={{ fontSize: '1.4rem' }}>{icon}</span>
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600, fontSize: '0.95rem', display: 'flex', alignItems: 'center', gap: 8 }}>
            {title}
            {changed && (
              <span style={{
                fontSize: '0.65rem', padding: '1px 7px', borderRadius: 20,
                background: 'rgba(255,159,10,0.12)', color: '#92400e',
                border: '1px solid rgba(255,159,10,0.35)',
              }}>● alterado</span>
            )}
          </div>
          <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', marginTop: 2 }}>
            {description}
          </div>
        </div>
        <span style={{
          fontSize: '0.65rem', padding: '2px 8px', borderRadius: 6, fontWeight: 500,
          background: 'rgba(99,102,241,0.08)', color: 'var(--clr-primary)',
          border: '1px solid rgba(99,102,241,0.2)',
        }}>asterisk</span>
        <span style={{
          color: 'var(--text-muted)', transition: 'transform .2s',
          display: 'inline-block', transform: open ? 'rotate(180deg)' : 'rotate(0)',
        }}>▾</span>
      </button>

      {open && (
        <div style={{ padding: '0 20px 20px', borderTop: '1px solid var(--border-glass)' }}>

          {/* Hint */}
          <div style={{
            marginTop: 14, padding: '10px 14px', borderRadius: 8,
            background: 'rgba(99,102,241,0.05)', border: '1px solid rgba(99,102,241,0.15)',
            fontSize: '0.78rem', color: 'var(--clr-primary)', lineHeight: 1.6,
          }}>
            {hint}
          </div>

          {/* Textarea ou loading */}
          {isLoading ? (
            <div style={{
              marginTop: 14, padding: '28px', borderRadius: 8, textAlign: 'center',
              background: 'var(--bg-input)', border: '1px solid var(--border-glass)',
              color: 'var(--text-muted)', fontSize: '0.85rem',
            }}>
              <span className="spinner" style={{ width: 16, height: 16, display: 'inline-block', marginRight: 8, verticalAlign: 'middle' }} />
              Carregando configuração…
            </div>
          ) : (
            <textarea
              key={panelId}
              className="form-textarea"
              value={value}
              onChange={e => onChange(e.target.value)}
              spellCheck={false}
              rows={Math.max(minRows, value.split('\n').length + 2)}
              style={{
                marginTop: 14, boxSizing: 'border-box', width: '100%',
                fontFamily: '"JetBrains Mono","Fira Code","Courier New",monospace',
                fontSize: '0.82rem', lineHeight: 1.7,
              }}
            />
          )}

          {/* Rodapé */}
          <div style={{
            marginTop: 14, display: 'flex', alignItems: 'center',
            gap: 10, flexWrap: 'wrap',
            borderTop: '1px solid var(--border-glass)', paddingTop: 14,
          }}>
            {reloadStatus && (
              <span style={{
                fontSize: '0.78rem',
                color: reloadStatus === 'ok' ? '#059669' : '#92400e',
              }}>
                {reloadStatus === 'ok'
                  ? `✅ ${reloadLabel} recarregado`
                  : `⚠️ Reload ${reloadLabel}: ${reloadStatus}`}
              </span>
            )}
            <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
              <button
                className="btn btn-ghost btn-sm"
                onClick={onDiscard}
                disabled={!changed || saving}
                style={{ opacity: !changed ? 0.4 : 1 }}
              >
                ↩ Descartar
              </button>
              <button
                className="btn btn-primary btn-sm"
                onClick={onSave}
                disabled={saving || !value.trim()}
                style={{ minWidth: 200 }}
              >
                {saving
                  ? <><span className="spinner" style={{ width: 11, height: 11, margin: '0 5px 0 0', borderTopColor: '#fff' }} />Salvando…</>
                  : saveLabel}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
