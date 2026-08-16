import React from 'react';
import type { DesktopSummaryView } from '../../api/types';

interface KpiStripProps {
  summary: DesktopSummaryView | null;
}

export const KpiStrip: React.FC<KpiStripProps> = ({ summary }) => {
  const formatDuration = (totalSec: number) => {
    const hours = Math.floor(totalSec / 3600);
    const mins = Math.floor((totalSec % 3600) / 60);
    if (hours > 0) return `${hours}h ${mins}m`;
    return `${mins}m`;
  };

  const formatSeconds = (sec: number | null) => {
    if (sec === null || sec === undefined) return '—';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s}s`;
  };

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mb-6">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Atendimentos Hoje</p>
        <p className="text-2xl font-bold text-slate-900 dark:text-slate-100 mt-1">
          {summary?.callsAnsweredToday ?? 0}
        </p>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">TMA Médio</p>
        <div className="flex items-baseline gap-2 mt-1">
          <p className="text-2xl font-bold text-slate-900 dark:text-slate-100">
            {formatSeconds(summary?.avgTalkSeconds ?? null)}
          </p>
          {summary?.comparedTo7dAvgTalkPct != null && (
            <span
              className={`text-xs font-medium ${
                summary.comparedTo7dAvgTalkPct <= 0
                  ? 'text-emerald-600 dark:text-emerald-400'
                  : 'text-amber-600 dark:text-amber-400'
              }`}
            >
              {summary.comparedTo7dAvgTalkPct > 0 ? '+' : ''}
              {summary.comparedTo7dAvgTalkPct.toFixed(1)}% vs 7d
            </span>
          )}
        </div>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Tempo Logado</p>
        <p className="text-2xl font-bold text-slate-900 dark:text-slate-100 mt-1">
          {formatDuration(summary?.loggedSeconds ?? 0)}
        </p>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Aderência à Escala</p>
        <p className="text-2xl font-bold text-slate-900 dark:text-slate-100 mt-1">
          {summary?.adherencePct != null ? `${summary.adherencePct.toFixed(1)}%` : '—'}
        </p>
      </div>
    </div>
  );
};
