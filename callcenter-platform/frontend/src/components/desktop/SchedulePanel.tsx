import React from 'react';
import type { DesktopScheduleView } from '../../api/types';

interface SchedulePanelProps {
  schedule: DesktopScheduleView | null;
  loading: boolean;
}

export const SchedulePanel: React.FC<SchedulePanelProps> = ({ schedule, loading }) => {
  if (loading) {
    return <div className="py-12 text-center text-xs text-slate-500">Carregando escala...</div>;
  }

  const formatHours = (sec?: number) => {
    if (!sec) return '0h';
    return `${(sec / 3600).toFixed(1)}h`;
  };

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
      <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100 mb-1">
        Escala e Aderência de Hoje
      </h3>
      <p className="text-xs text-slate-500 dark:text-slate-400 mb-6">
        Informações do seu turno de trabalho e cumprimento da jornada
      </p>

      {schedule?.adherenceStatus === 'SEM_ESCALA' ? (
        <div className="py-8 text-center text-xs text-slate-500 bg-slate-50 dark:bg-slate-800/40 rounded-lg border border-slate-200 dark:border-slate-800">
          Nenhuma escala cadastrada para o dia de hoje.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 rounded-lg bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-800">
            <span className="text-xs text-slate-500">Turno Programado</span>
            <p className="text-lg font-semibold text-slate-900 dark:text-slate-100 mt-1">
              {schedule?.shiftLabel}
            </p>
            <p className="text-xs font-mono text-slate-500 mt-1">
              {schedule?.shiftStart ? new Date(schedule.shiftStart).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '—'} às{' '}
              {schedule?.shiftEnd ? new Date(schedule.shiftEnd).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '—'}
            </p>
          </div>

          <div className="p-4 rounded-lg bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-800">
            <span className="text-xs text-slate-500 font-medium">Horas Logadas / Planejadas</span>
            <p className="text-lg font-semibold text-slate-900 dark:text-slate-100 mt-1">
              {formatHours(schedule?.loggedSeconds)} / {formatHours(schedule?.scheduledSeconds)}
            </p>
          </div>

          <div className="p-4 rounded-lg bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-800">
            <span className="text-xs text-slate-500 font-medium">Índice de Aderência</span>
            <p className="text-lg font-bold text-emerald-600 dark:text-emerald-400 mt-1">
              {schedule?.adherencePct != null ? `${schedule.adherencePct.toFixed(1)}%` : '—'}
            </p>
          </div>
        </div>
      )}
    </div>
  );
};
