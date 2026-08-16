import React from 'react';
import type { DesktopTrendPoint } from '../../api/types';

interface ProductivityPanelProps {
  trends: DesktopTrendPoint[];
  loading: boolean;
}

export const ProductivityPanel: React.FC<ProductivityPanelProps> = ({ trends, loading }) => {
  if (loading) {
    return <div className="py-12 text-center text-xs text-slate-500">Carregando métricas...</div>;
  }

  const maxCalls = Math.max(...trends.map((t) => t.answeredCount), 1);

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-1">
          Evolução dos Atendimentos (Últimos 7 dias)
        </h3>
        <p className="text-xs text-slate-500 dark:text-slate-400 mb-6">
          Volume diário de chamadas atendidas
        </p>

        <div className="flex items-end gap-3 h-40 pt-4">
          {trends.map((point) => {
            const heightPct = (point.answeredCount / maxCalls) * 100;
            return (
              <div key={point.date} className="flex-1 flex flex-col items-center gap-2 group">
                <div className="text-xs font-semibold text-slate-700 dark:text-slate-300 opacity-0 group-hover:opacity-100 transition-opacity">
                  {point.answeredCount}
                </div>
                <div className="w-full bg-slate-100 dark:bg-slate-800 rounded-t h-full flex items-end overflow-hidden">
                  <div
                    style={{ height: `${Math.max(heightPct, 4)}%` }}
                    className="w-full bg-indigo-500 rounded-t transition-all duration-300 group-hover:bg-indigo-600"
                  />
                </div>
                <span className="text-[10px] text-slate-400 font-mono">
                  {new Date(point.date + 'T00:00:00').toLocaleDateString('pt-BR', {
                    weekday: 'short',
                  })}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
