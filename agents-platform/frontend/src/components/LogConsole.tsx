import { useEffect, useRef } from 'react';
import type { LogEntry } from '../api/types';

const LEVEL_CLASS: Record<LogEntry['level'], string> = {
  info: 'log-info',
  success: 'log-success',
  warning: 'log-warning',
  error: 'log-error',
};

interface LogConsoleProps {
  logs: LogEntry[];
  loading?: boolean;
  emptyMessage?: string;
  height?: number;
}

/**
 * LogConsole — console de log compartilhado, dedup de LogModal (index.html:526-610)
 * e LogsPage (index.html:1172-1248), que reimplementavam a mesma renderização.
 * Faz auto-scroll para o final sempre que `logs` muda.
 */
export function LogConsole({ logs, loading, emptyMessage = 'Nenhum log encontrado.', height }: LogConsoleProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [logs]);

  return (
    <div className="log-console" ref={ref} style={height ? { height } : undefined}>
      {loading ? (
        <div className="log-line"><span className="log-info">Carregando...</span></div>
      ) : logs.length === 0 ? (
        <div className="log-line"><span className="log-info">{emptyMessage}</span></div>
      ) : (
        logs.map((l, i) => (
          <div key={i} className="log-line">
            <span className="log-ts">{new Date(l.ts).toLocaleTimeString('pt-BR')}</span>
            {l.server && <span className="log-server">[{l.server}]</span>}
            <span className={LEVEL_CLASS[l.level] ?? 'log-info'}>{l.message}</span>
          </div>
        ))
      )}
    </div>
  );
}
