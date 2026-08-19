import React from 'react';
import type { DesktopScheduleView } from '../../api/types';

interface SchedulePanelProps {
  schedule: DesktopScheduleView | null;
  loading: boolean;
}

export const SchedulePanel: React.FC<SchedulePanelProps> = ({
  schedule,
  loading = false,
}) => {
  const formatSec = (sec?: number | null) => {
    if (!sec) return '0h 00m';
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    return `${h}h ${m.toString().padStart(2, '0')}m`;
  };

  return (
    <div className="space-y-5">
      {/* ─── 1. Card Superior: Aderência à Escala ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-sm font-bold text-slate-900">Aderência à escala</h3>
          <span className="text-xs text-slate-400 font-medium">escala de hoje</span>
        </div>

        {loading ? (
          <div className="py-12 text-center text-xs text-slate-400">Carregando escala...</div>
        ) : schedule ? (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="text-[10px] font-bold text-slate-400 uppercase border-b border-slate-100">
                  <th className="py-2.5 px-2 font-medium">TURNO</th>
                  <th className="py-2.5 px-2 font-medium">PREVISTO</th>
                  <th className="py-2.5 px-2 font-medium">LOGADO</th>
                  <th className="py-2.5 px-2 font-medium text-right">ADERÊNCIA</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                <tr className="hover:bg-slate-50/70 transition-colors">
                  <td className="py-2.5 px-2 font-medium text-slate-900">
                    {schedule.shiftLabel || `${schedule.shiftStart || '08:00'}–${schedule.shiftEnd || '17:00'}`}
                  </td>
                  <td className="py-2.5 px-2 font-mono text-slate-600">
                    {formatSec(schedule.scheduledSeconds)}
                  </td>
                  <td className="py-2.5 px-2 font-mono text-slate-600">
                    {formatSec(schedule.loggedSeconds)}
                  </td>
                  <td className="py-2.5 px-2 text-right">
                    <span className="inline-block px-2.5 py-0.5 rounded-full text-[11px] font-medium bg-emerald-100 text-emerald-800 border border-emerald-200">
                      {schedule.adherencePct != null ? `${schedule.adherencePct.toFixed(0)}%` : '—'}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        ) : (
          <div className="py-12 text-center text-xs text-slate-400 italic">
            Nenhuma escala de turno cadastrada para a data de hoje.
          </div>
        )}

        <div className="pt-3 mt-2 border-t border-slate-100 text-xs text-slate-500">
          Dia sem turno cadastrado aparece como <em>não se aplica</em> — nunca como aderência zero.
        </div>
      </div>

      {/* ─── 2. Card Inferior: Agendamentos de Treinamento ─── */}
      <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs">
        <h3 className="text-sm font-bold text-slate-900 mb-3">Agendamentos de treinamento</h3>
        <div className="border border-dashed border-slate-200 rounded-xl p-6 text-center space-y-1.5 bg-slate-50/40">
          <p className="text-xs font-semibold text-slate-600">Sem treinamentos agendados</p>
          <p className="text-xs text-slate-400 max-w-xl mx-auto leading-relaxed">
            Nenhum agendamento de capacitação ou desenvolvimento cadastrado para este agente.
          </p>
        </div>
      </div>
    </div>
  );
};
