import React, { useState } from 'react';
import type { DesktopCallHistoryItem } from '../../api/types';

interface InteractionsTableProps {
  history: DesktopCallHistoryItem[];
  loading: boolean;
  onFilterChange: (de?: string, ate?: string) => void;
}

export const InteractionsTable: React.FC<InteractionsTableProps> = ({
  history,
  loading,
  onFilterChange,
}) => {
  const [selectedItem, setSelectedItem] = useState<DesktopCallHistoryItem | null>(null);

  const formatSeconds = (sec?: number) => {
    if (sec === undefined || sec === null) return '—';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s}s`;
  };

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 mb-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
            Histórico de Interações
          </h3>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Consulte seus atendimentos recentes por período (até 90 dias)
          </p>
        </div>

        <div className="flex items-center gap-2">
          <input
            type="date"
            onChange={(e) => onFilterChange(e.target.value, e.target.value)}
            className="px-2.5 py-1.5 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-300"
          />
        </div>
      </div>

      {loading ? (
        <div className="py-12 text-center text-xs text-slate-500">Carregando histórico...</div>
      ) : history.length === 0 ? (
        <div className="py-12 text-center text-xs text-slate-500">
          Nenhuma interação encontrada para o período selecionado.
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400 font-medium">
                <th className="pb-3 pt-1 px-3">Data/Hora</th>
                <th className="pb-3 pt-1 px-3">Contato / Ani</th>
                <th className="pb-3 pt-1 px-3">Fila</th>
                <th className="pb-3 pt-1 px-3">Duração</th>
                <th className="pb-3 pt-1 px-3">Tabulação</th>
                <th className="pb-3 pt-1 px-3">NPS</th>
                <th className="pb-3 pt-1 px-3 text-right">Transcrição</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800/60">
              {history.map((item) => (
                <tr
                  key={item.interactionId}
                  className="hover:bg-slate-50 dark:hover:bg-slate-800/40 transition-colors"
                >
                  <td className="py-3 px-3 font-mono text-slate-600 dark:text-slate-400">
                    {new Date(item.dateTime).toLocaleString('pt-BR')}
                  </td>
                  <td className="py-3 px-3 font-medium text-slate-900 dark:text-slate-100">
                    {item.contactName || item.ani || 'Desconhecido'}
                  </td>
                  <td className="py-3 px-3 text-slate-600 dark:text-slate-400">
                    {item.queueName || '—'}
                  </td>
                  <td className="py-3 px-3 font-mono text-slate-600 dark:text-slate-400">
                    {formatSeconds(item.talkSeconds)}
                  </td>
                  <td className="py-3 px-3 text-slate-600 dark:text-slate-400">
                    {item.dispositionLabel || '—'}
                  </td>
                  <td className="py-3 px-3">
                    {item.npsScore != null ? (
                      <span className="inline-flex items-center px-2 py-0.5 rounded font-semibold text-xs bg-indigo-50 dark:bg-indigo-950/40 text-indigo-600 dark:text-indigo-400">
                        {item.npsScore}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="py-3 px-3 text-right">
                    {item.transcriptionStatus === 'DISPONIVEL' ? (
                      <button
                        onClick={() => setSelectedItem(item)}
                        className="px-2.5 py-1 text-xs rounded bg-indigo-50 hover:bg-indigo-100 dark:bg-indigo-950/40 dark:hover:bg-indigo-900/60 text-indigo-600 dark:text-indigo-400 font-medium transition-colors"
                      >
                        Ver Transcrição
                      </button>
                    ) : (
                      <span className="text-slate-400 text-xs italic">
                        {item.transcriptionStatus === 'EM_PROCESSAMENTO'
                          ? 'Processando'
                          : 'Sem gravação'}
                      </span>
                    )}
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
              <h4 className="font-semibold text-slate-900 dark:text-slate-100">
                Transcrição do Atendimento #{selectedItem.interactionId}
              </h4>
              <button
                onClick={() => setSelectedItem(null)}
                className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto font-mono text-xs whitespace-pre-wrap bg-slate-50 dark:bg-slate-950 p-4 rounded-lg border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-300">
              {selectedItem.transcript || 'Sem conteúdo de transcrição.'}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
