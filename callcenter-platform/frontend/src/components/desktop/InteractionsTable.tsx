import React, { useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, FileText, AlertCircle } from 'lucide-react';
import type { DesktopCallHistoryItem } from '../../api/types';

interface InteractionsTableProps {
  history: DesktopCallHistoryItem[];
  loading: boolean;
  onFilterChange: (de?: string, ate?: string) => void;
  onOpenAppealModal?: (interactionId: number) => void;
}

export const InteractionsTable: React.FC<InteractionsTableProps> = ({
  history,
  loading,
  onFilterChange,
  onOpenAppealModal,
}) => {
  const [selectedItem, setSelectedItem] = useState<DesktopCallHistoryItem | null>(null);
  const [dateFilter, setDateFilter] = useState('');

  const formatSeconds = (sec?: number | null) => {
    if (sec === undefined || sec === null) return '—';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s.toString().padStart(2, '0')}s`;
  };

  const handleDateChange = (val: string) => {
    setDateFilter(val);
    if (!val) {
      onFilterChange();
    } else {
      onFilterChange(val, val);
    }
  };

  const getNpsBadge = (score?: number) => {
    if (score == null) return <span className="text-slate-400">—</span>;
    let colorClass = 'bg-red-500/10 text-red-600 dark:text-red-400 border-red-500/20';
    if (score >= 9) {
      colorClass = 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20';
    } else if (score >= 7) {
      colorClass = 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20';
    }
    return (
      <span className={`inline-flex items-center px-2 py-0.5 rounded font-mono font-bold text-xs border ${colorClass}`}>
        ★ {score}
      </span>
    );
  };

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
            Histórico de Atendimentos
          </h3>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Consulte o histórico detalhado dos seus atendimentos (até 90 dias)
          </p>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5 bg-slate-50 dark:bg-slate-800/60 p-1 rounded-lg border border-slate-200 dark:border-slate-700">
            <button
              onClick={() => handleDateChange('')}
              className={`px-2.5 py-1 text-xs rounded-md transition-colors ${
                !dateFilter
                  ? 'bg-white dark:bg-slate-900 text-indigo-600 font-semibold shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900'
              }`}
            >
              Hoje
            </button>
            <input
              type="date"
              value={dateFilter}
              onChange={(e) => handleDateChange(e.target.value)}
              className="px-2 py-0.5 text-xs rounded border-0 bg-transparent text-slate-700 dark:text-slate-300 focus:ring-0"
            />
          </div>
        </div>
      </div>

      {loading ? (
        <div className="py-12 text-center text-xs text-slate-500">Carregando histórico...</div>
      ) : history.length === 0 ? (
        <div className="py-12 text-center text-xs text-slate-500">
          Nenhuma interação encontrada para a data selecionada.
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400 font-medium">
                <th className="pb-3 pt-1 px-3">Data/Hora</th>
                <th className="pb-3 pt-1 px-3">Direção</th>
                <th className="pb-3 pt-1 px-3">Contato / ANI</th>
                <th className="pb-3 pt-1 px-3">Fila</th>
                <th className="pb-3 pt-1 px-3">Espera (ASA)</th>
                <th className="pb-3 pt-1 px-3">Falado (TMA)</th>
                <th className="pb-3 pt-1 px-3">Tabulação</th>
                <th className="pb-3 pt-1 px-3 text-center">NPS</th>
                <th className="pb-3 pt-1 px-3 text-right">Ações</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800/60">
              {history.map((item) => (
                <tr
                  key={item.interactionId}
                  className="hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors"
                >
                  <td className="py-3 px-3 font-mono text-slate-600 dark:text-slate-400 whitespace-nowrap">
                    {new Date(item.dateTime).toLocaleTimeString('pt-BR', {
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </td>
                  <td className="py-3 px-3">
                    {item.direction === 'OUTBOUND' ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium bg-amber-500/10 text-amber-600 dark:text-amber-400 border border-amber-500/20">
                        <ArrowUpRight size={12} /> Saída
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium bg-blue-500/10 text-blue-600 dark:text-blue-400 border border-blue-500/20">
                        <ArrowDownLeft size={12} /> Entrada
                      </span>
                    )}
                  </td>
                  <td className="py-3 px-3 font-medium text-slate-900 dark:text-slate-100">
                    <div>{item.contactName || item.ani || '—'}</div>
                    {item.contactName && item.ani && (
                      <div className="text-[10px] font-mono text-slate-400">{item.ani}</div>
                    )}
                  </td>
                  <td className="py-3 px-3 text-slate-600 dark:text-slate-400">
                    {item.queueName || '—'}
                  </td>
                  <td className="py-3 px-3 font-mono text-slate-500 dark:text-slate-400">
                    {formatSeconds(item.waitSeconds)}
                  </td>
                  <td className="py-3 px-3 font-mono font-semibold text-slate-700 dark:text-slate-300">
                    {formatSeconds(item.talkSeconds)}
                  </td>
                  <td className="py-3 px-3 text-slate-600 dark:text-slate-400">
                    {item.dispositionLabel ? (
                      <span className="px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700">
                        {item.dispositionLabel}
                      </span>
                    ) : (
                      <span className="text-slate-400 italic">sem tabulação</span>
                    )}
                  </td>
                  <td className="py-3 px-3 text-center">
                    {getNpsBadge(item.npsScore)}
                  </td>
                  <td className="py-3 px-3 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      {item.transcriptionStatus === 'DISPONIVEL' ? (
                        <button
                          onClick={() => setSelectedItem(item)}
                          className="px-2 py-1 text-[11px] rounded bg-indigo-50 hover:bg-indigo-100 dark:bg-indigo-950/40 dark:hover:bg-indigo-900/60 text-indigo-600 dark:text-indigo-400 font-medium transition-colors flex items-center gap-1"
                          title="Visualizar transcrição de áudio"
                        >
                          <FileText size={12} /> Transcrição
                        </button>
                      ) : (
                        <span className="text-slate-400 text-[10px] italic">
                          {item.transcriptionStatus === 'EM_PROCESSAMENTO'
                            ? 'Processando'
                            : 'Sem áudio'}
                        </span>
                      )}

                      {onOpenAppealModal && (
                        <button
                          onClick={() => onOpenAppealModal(item.interactionId)}
                          className="px-2 py-1 text-[11px] rounded bg-slate-100 hover:bg-slate-200 dark:bg-slate-800 dark:hover:bg-slate-700 text-slate-700 dark:text-slate-300 font-medium transition-colors flex items-center gap-1"
                          title="Ver avaliação ou contestar"
                        >
                          <AlertCircle size={12} /> Avaliação
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedItem && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm">
          <div className="bg-white dark:bg-slate-900 rounded-xl max-w-2xl w-full p-6 border border-slate-200 dark:border-slate-800 shadow-xl max-h-[80vh] flex flex-col">
            <div className="flex items-center justify-between border-b border-slate-200 dark:border-slate-800 pb-3 mb-4">
              <div className="flex items-center gap-2">
                <FileText size={16} className="text-indigo-600" />
                <h4 className="font-semibold text-slate-900 dark:text-slate-100 text-sm">
                  Transcrição do Atendimento #{selectedItem.interactionId}
                </h4>
              </div>
              <button
                onClick={() => setSelectedItem(null)}
                className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto font-mono text-xs whitespace-pre-wrap bg-slate-50 dark:bg-slate-950 p-4 rounded-lg border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-300 leading-relaxed">
              {selectedItem.transcript || 'Nenhum segmento de transcrição encontrado.'}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
