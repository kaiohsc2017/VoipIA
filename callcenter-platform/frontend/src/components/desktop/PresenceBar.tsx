import React from 'react';
import type { CcPauseReason } from '../../api/types';

interface PresenceBarProps {
  agentName: string;
  extension?: string;
  currentState: string;
  currentPauseReasonId?: number | null;
  stateSeconds: number;
  pauseReasons: CcPauseReason[];
  onStateChange: (state: string, pauseReasonId?: number | null) => void;
}

export const PresenceBar: React.FC<PresenceBarProps> = ({
  agentName,
  extension,
  currentState,
  currentPauseReasonId,
  stateSeconds,
  pauseReasons,
  onStateChange,
}) => {
  const formatTime = (totalSec: number) => {
    const mins = Math.floor(totalSec / 60);
    const secs = totalSec % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const getStatusBadgeClass = (state: string) => {
    switch (state) {
      case 'DISPONIVEL':
        return 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20';
      case 'EM_ATENDIMENTO':
        return 'bg-blue-500/10 text-blue-600 dark:text-blue-400 border-blue-500/20';
      case 'PAUSA':
        return 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20';
      default:
        return 'bg-slate-500/10 text-slate-600 dark:text-slate-400 border-slate-500/20';
    }
  };

  return (
    <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-4 shadow-sm mb-6">
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-full bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 font-semibold flex items-center justify-center text-lg border border-indigo-500/20">
          {agentName.slice(0, 2).toUpperCase()}
        </div>
        <div>
          <h2 className="text-base font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-2">
            {agentName}
            {extension && (
              <span className="text-xs font-mono font-normal px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700">
                Ramal {extension}
              </span>
            )}
          </h2>
          <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${getStatusBadgeClass(currentState)}`}>
              ● {currentState}
            </span>
            <span>•</span>
            <span className="font-mono">{formatTime(stateSeconds)} no estado atual</span>
          </div>
        </div>
      </div>

      <div className="flex items-center gap-2 w-full sm:w-auto">
        <button
          onClick={() => onStateChange('DISPONIVEL')}
          className={`flex-1 sm:flex-initial px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
            currentState === 'DISPONIVEL'
              ? 'bg-emerald-600 text-white border-emerald-600'
              : 'border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800'
          }`}
        >
          Disponível
        </button>

        <select
          value={currentState === 'PAUSA' ? currentPauseReasonId || '' : ''}
          onChange={(e) => {
            const val = e.target.value;
            if (val) {
              onStateChange('PAUSA', parseInt(val, 10));
            }
          }}
          className={`flex-1 sm:flex-initial px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
            currentState === 'PAUSA'
              ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300 border-amber-500/30'
              : 'border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 bg-white dark:bg-slate-900'
          }`}
        >
          <option value="" disabled>
            {currentState === 'PAUSA' ? 'Em Pausa...' : 'Entrar em Pausa'}
          </option>
          {pauseReasons.map((reason) => (
            <option key={reason.id} value={reason.id}>
              {reason.label}
            </option>
          ))}
        </select>

        <button
          onClick={() => onStateChange('OFFLINE')}
          className={`flex-1 sm:flex-initial px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
            currentState === 'OFFLINE'
              ? 'bg-slate-700 text-white border-slate-700'
              : 'border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800'
          }`}
        >
          Offline
        </button>
      </div>
    </div>
  );
};
