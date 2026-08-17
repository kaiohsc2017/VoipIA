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
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4 mb-6">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Atendimentos</p>
        <p className="text-2xl font-bold text-slate-900 dark:text-slate-100 mt-1">
          {summary?.callsAnsweredToday ?? 0}
        </p>
        <p className="text-[10px] text-slate-400 mt-0.5">concluídos hoje</p>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">TMA Médio</p>
        <div className="flex items-baseline gap-1.5 mt-1">
          <p className="text-2xl font-bold text-slate-900 dark:text-slate-100">
            {formatSeconds(summary?.avgTalkSeconds ?? null)}
          </p>
        </div>
        {summary?.comparedTo7dAvgTalkPct != null ? (
          <span
            className={`text-[10px] font-medium ${
              summary.comparedTo7dAvgTalkPct <= 0
                ? 'text-emerald-600 dark:text-emerald-400'
                : 'text-amber-600 dark:text-amber-400'
            }`}
          >
            {summary.comparedTo7dAvgTalkPct > 0 ? '+' : ''}
            {summary.comparedTo7dAvgTalkPct.toFixed(1)}% vs 7d
          </span>
        ) : (
          <p className="text-[10px] text-slate-400 mt-0.5">tempo falado</p>
        )}
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Tempo Logado</p>
        <p className="text-2xl font-bold text-slate-900 dark:text-slate-100 mt-1">
          {formatDuration(summary?.loggedSeconds ?? 0)}
        </p>
        <p className="text-[10px] text-slate-400 mt-0.5">
          pausas: {formatDuration(summary?.pauseSeconds ?? 0)}
        </p>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Taxa de Ocupação</p>
        <p className="text-2xl font-bold text-indigo-600 dark:text-indigo-400 mt-1">
          {summary?.occupancyPct != null ? `${summary.occupancyPct.toFixed(1)}%` : '—'}
        </p>
        <p className="text-[10px] text-slate-400 mt-0.5">tempo em atendimento</p>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">Aderência</p>
        <p className="text-2xl font-bold text-emerald-600 dark:text-emerald-400 mt-1">
          {summary?.adherencePct != null ? `${summary.adherencePct.toFixed(1)}%` : '—'}
        </p>
        <p className="text-[10px] text-slate-400 mt-0.5">cumprimento da escala</p>
      </div>

      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
        <p className="text-xs font-medium text-slate-500 dark:text-slate-400">NPS do Dia</p>
        <p className="text-2xl font-bold text-amber-500 dark:text-amber-400 mt-1">
          {summary?.avgNpsScore != null ? summary.avgNpsScore.toFixed(1) : '—'}
        </p>
        <p className="text-[10px] text-slate-400 mt-0.5">avaliação do cliente</p>
      </div>
    </div>
  );
};
