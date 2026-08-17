import React, { useState } from 'react';
import { BarChart3, TrendingUp, Clock, Percent } from 'lucide-react';
import type { DesktopTrendPoint } from '../../api/types';

interface ProductivityPanelProps {
  trends: DesktopTrendPoint[];
  loading: boolean;
  onDaysChange?: (days: number) => void;
}

export const ProductivityPanel: React.FC<ProductivityPanelProps> = ({
  trends,
  loading,
  onDaysChange,
}) => {
  const [selectedDays, setSelectedDays] = useState<number>(7);

  if (loading) {
    return (
      <div className="py-12 text-center text-xs text-slate-500">Carregando métricas de produtividade...</div>
    );
  }

  const handleSelectDays = (d: number) => {
    setSelectedDays(d);
    if (onDaysChange) {
      onDaysChange(d);
    }
  };

  const maxCalls = Math.max(...trends.map((t) => t.answeredCount), 1);
  const totalCalls = trends.reduce((acc, curr) => acc + curr.answeredCount, 0);
  const avgTalk =
    trends.filter((t) => t.avgTalkSeconds != null).length > 0
      ? Math.round(
          trends.reduce((acc, curr) => acc + (curr.avgTalkSeconds || 0), 0) /
            trends.filter((t) => t.avgTalkSeconds != null).length
        )
      : 0;

  const formatSeconds = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s.toString().padStart(2, '0')}s`;
  };

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-6">
          <div>
            <div className="flex items-center gap-2">
              <TrendingUp className="text-indigo-600 dark:text-indigo-400" size={18} />
              <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
                Evolução e Produtividade
              </h3>
            </div>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              Acompanhamento diário de volume de chamadas, tempo falado e ocupação
            </p>
          </div>

          <div className="flex items-center gap-1 bg-slate-100 dark:bg-slate-800 p-1 rounded-lg">
            <button
              onClick={() => handleSelectDays(7)}
              className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
                selectedDays === 7
                  ? 'bg-white dark:bg-slate-900 text-indigo-600 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900'
              }`}
            >
              7 Dias
            </button>
            <button
              onClick={() => handleSelectDays(30)}
              className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
                selectedDays === 30
                  ? 'bg-white dark:bg-slate-900 text-indigo-600 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900'
              }`}
            >
              30 Dias
            </button>
          </div>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 gap-4 mb-6">
          <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-800/40 border border-slate-200 dark:border-slate-800">
            <span className="text-xs text-slate-500 flex items-center gap-1">
              <BarChart3 size={14} /> Total Atendido
            </span>
            <p className="text-xl font-bold text-slate-900 dark:text-slate-100 mt-1">
              {totalCalls} chamadas
            </p>
          </div>
          <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-800/40 border border-slate-200 dark:border-slate-800">
            <span className="text-xs text-slate-500 flex items-center gap-1">
              <Clock size={14} /> TMA Médio do Período
            </span>
            <p className="text-xl font-bold text-slate-900 dark:text-slate-100 mt-1">
              {formatSeconds(avgTalk)}
            </p>
          </div>
          <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-800/40 border border-slate-200 dark:border-slate-800 col-span-2 sm:col-span-1">
            <span className="text-xs text-slate-500 flex items-center gap-1">
              <Percent size={14} /> Média de Atendimentos/Dia
            </span>
            <p className="text-xl font-bold text-indigo-600 dark:text-indigo-400 mt-1">
              {(totalCalls / (trends.length || 1)).toFixed(1)}
            </p>
          </div>
        </div>

        <div className="flex items-end gap-2 sm:gap-3 h-48 pt-6 pb-2 border-b border-slate-100 dark:border-slate-800">
          {trends.map((point) => {
            const heightPct = (point.answeredCount / maxCalls) * 100;
            return (
              <div key={point.date} className="flex-1 flex flex-col items-center gap-2 group">
                <div className="text-[11px] font-bold text-indigo-600 dark:text-indigo-400 opacity-0 group-hover:opacity-100 transition-opacity">
                  {point.answeredCount}
                </div>
                <div className="w-full bg-slate-100 dark:bg-slate-800/60 rounded-t h-full flex items-end overflow-hidden">
                  <div
                    style={{ height: `${Math.max(heightPct, 4)}%` }}
                    className="w-full bg-indigo-500 hover:bg-indigo-600 dark:bg-indigo-600 dark:hover:bg-indigo-500 rounded-t transition-all duration-300 shadow-sm"
                  />
                </div>
                <span className="text-[10px] text-slate-400 font-mono">
                  {new Date(point.date + 'T00:00:00').toLocaleDateString('pt-BR', {
                    weekday: 'short',
                    day: '2-digit',
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
