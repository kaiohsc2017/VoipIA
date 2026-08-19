import React, { useState } from 'react';
import { ChevronLeft, ChevronRight, Play, Volume2 } from 'lucide-react';
import type { DesktopCallHistoryItem } from '../../api/types';

interface InteractionsTableProps {
  history: DesktopCallHistoryItem[];
  loading?: boolean;
  onFilterChange?: (de?: string, ate?: string) => void;
  onOpenAppealModal?: (interactionId: number) => void;
}

export const InteractionsTable: React.FC<InteractionsTableProps> = ({
  history,
  loading = false,
  onFilterChange,
}) => {
  const [selectedDate, setSelectedDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [directionFilter, setDirectionFilter] = useState('todas');
  const [queueFilter, setQueueFilter] = useState('todas');
  const [dispositionFilter, setDispositionFilter] = useState('todas');
  const [playingId, setPlayingId] = useState<number | string | null>(null);

  const formatSec = (sec?: number | null) => {
    if (!sec) return '0m 00s';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s.toString().padStart(2, '0')}s`;
  };

  const formatWait = (sec?: number | null) => {
    if (!sec) return '00:00';
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const rows = history.map((h, i) => ({
    id: h.interactionId || i,
    data: h.dateTime
      ? new Date(h.dateTime).toLocaleString('pt-BR', {
          day: '2-digit',
          month: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—',
    remoto: h.ani || h.contactName || 'Desconhecido',
    direcao: h.direction === 'OUTBOUND' ? 'Saída' : 'Entrada',
    fila: h.queueName || '—',
    espera: formatWait(h.waitSeconds),
    conversa: formatSec(h.talkSeconds),
    finalizacao: h.dispositionLabel || 'Sem tabulação',
    nps: h.npsScore != null ? String(h.npsScore) : '—',
    gravacao: h.recordingUrl ? 'pronto' : 'processando',
  }));

  const filteredRows = rows.filter((r) => {
    if (directionFilter !== 'todas' && r.direcao.toLowerCase() !== directionFilter.toLowerCase()) return false;
    if (queueFilter !== 'todas' && r.fila.toLowerCase() !== queueFilter.toLowerCase()) return false;
    if (dispositionFilter !== 'todas' && r.finalizacao.toLowerCase() !== dispositionFilter.toLowerCase()) return false;
    return true;
  });

  const getFinalizacaoClass = (label: string) => {
    const l = label.toLowerCase();
    if (l.includes('transf')) return 'bg-rose-100 text-rose-800 border-rose-200';
    if (l.includes('info')) return 'bg-slate-100 text-slate-700 border-slate-200';
    if (l.includes('resolv')) return 'bg-emerald-100 text-emerald-800 border-emerald-200';
    return 'bg-slate-100 text-slate-700 border-slate-200';
  };

  const handleDateShift = (deltaDays: number) => {
    const d = new Date(selectedDate);
    d.setDate(d.getDate() + deltaDays);
    const newDateStr = d.toISOString().slice(0, 10);
    setSelectedDate(newDateStr);
    if (onFilterChange) onFilterChange(newDateStr, newDateStr);
  };

  const formattedDisplayDate = new Date(selectedDate + 'T12:00:00').toLocaleDateString('pt-BR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  return (
    <div className="bg-white border border-slate-200/90 rounded-xl p-5 shadow-xs">
      {/* ─── Header de Controles e Filtros ─── */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-4 border-b border-slate-100">
        <div>
          <h3 className="text-sm font-bold text-slate-900">Minhas interações</h3>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {/* Seletor de Data */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg px-2 py-1">
            <button
              onClick={() => handleDateShift(-1)}
              className="p-0.5 text-slate-500 hover:text-slate-800 rounded"
              title="Dia anterior"
            >
              <ChevronLeft size={14} />
            </button>
            <span className="text-xs font-semibold text-slate-800 px-2">
              {formattedDisplayDate}
            </span>
            <button
              onClick={() => handleDateShift(1)}
              className="p-0.5 text-slate-500 hover:text-slate-800 rounded"
              title="Dia seguinte"
            >
              <ChevronRight size={14} />
            </button>
          </div>

          {/* Filtros Dropdowns */}
          <select
            value={directionFilter}
            onChange={(e) => setDirectionFilter(e.target.value)}
            className="text-xs border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white text-slate-700 focus:outline-none focus:border-indigo-500"
          >
            <option value="todas">Direção: todas</option>
            <option value="entrada">Entrada</option>
            <option value="saída">Saída</option>
          </select>

          <select
            value={queueFilter}
            onChange={(e) => setQueueFilter(e.target.value)}
            className="text-xs border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white text-slate-700 focus:outline-none focus:border-indigo-500"
          >
            <option value="todas">Fila: todas</option>
          </select>

          <select
            value={dispositionFilter}
            onChange={(e) => setDispositionFilter(e.target.value)}
            className="text-xs border border-slate-200 rounded-lg px-2.5 py-1.5 bg-white text-slate-700 focus:outline-none focus:border-indigo-500"
          >
            <option value="todas">Tabulação: todas</option>
          </select>
        </div>
      </div>

      {/* ─── Tabela ─── */}
      <div className="overflow-x-auto py-2">
        <table className="w-full text-left text-xs">
          <thead>
            <tr className="text-[10px] font-bold text-slate-400 uppercase border-b border-slate-100">
              <th className="py-3 px-2 font-medium">DATA</th>
              <th className="py-3 px-2 font-medium">REMOTO</th>
              <th className="py-3 px-2 font-medium">DIREÇÃO</th>
              <th className="py-3 px-2 font-medium">FILA</th>
              <th className="py-3 px-2 font-medium">ESPERA</th>
              <th className="py-3 px-2 font-medium">CONVERSA</th>
              <th className="py-3 px-2 font-medium">FINALIZAÇÃO</th>
              <th className="py-3 px-2 font-medium text-center">NPS</th>
              <th className="py-3 px-2 font-medium text-right">GRAVAÇÃO</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={9} className="py-10 text-center text-xs text-slate-400">
                  Carregando interações...
                </td>
              </tr>
            ) : filteredRows.length === 0 ? (
              <tr>
                <td colSpan={9} className="py-10 text-center text-xs text-slate-400 italic">
                  Nenhuma interação encontrada para a data selecionada.
                </td>
              </tr>
            ) : (
              filteredRows.map((r) => (
                <tr key={r.id} className="hover:bg-slate-50/80 transition-colors">
                  <td className="py-3 px-2 font-mono text-slate-600">{r.data}</td>
                  <td className="py-3 px-2 font-mono text-slate-900 font-medium">{r.remoto}</td>
                  <td className="py-3 px-2">
                    <span
                      className={`inline-block px-2 py-0.5 rounded text-[10px] font-medium ${
                        r.direcao === 'Saída'
                          ? 'bg-amber-50 text-amber-700 border border-amber-200'
                          : 'bg-blue-50 text-blue-700 border border-blue-200'
                      }`}
                    >
                      {r.direcao}
                    </span>
                  </td>
                  <td className="py-3 px-2 text-slate-600">{r.fila}</td>
                  <td className="py-3 px-2 font-mono text-slate-600">{r.espera}</td>
                  <td className="py-3 px-2 font-mono text-slate-800 font-medium">{r.conversa}</td>
                  <td className="py-3 px-2">
                    <span
                      className={`inline-block px-2 py-0.5 rounded-full text-[10px] font-medium border ${getFinalizacaoClass(
                        r.finalizacao
                      )}`}
                    >
                      {r.finalizacao}
                    </span>
                  </td>
                  <td className="py-3 px-2 font-mono font-bold text-center text-slate-800">
                    {r.nps}
                  </td>
                  <td className="py-3 px-2 text-right">
                    {r.gravacao === 'pronto' ? (
                      <button
                        onClick={() => setPlayingId(playingId === r.id ? null : r.id)}
                        className={`px-2.5 py-1 rounded text-[11px] font-medium border transition-colors inline-flex items-center gap-1 ${
                          playingId === r.id
                            ? 'bg-indigo-600 text-white border-indigo-600 shadow-xs'
                            : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
                        }`}
                      >
                        {playingId === r.id ? <Volume2 size={12} /> : <Play size={12} />}
                        <span>{playingId === r.id ? 'Tocando' : 'Ouvir'}</span>
                      </button>
                    ) : (
                      <span className="text-[11px] text-slate-400 italic">sem gravação</span>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* ─── Footer Resumo ─── */}
      <div className="pt-3 border-t border-slate-100 text-xs text-slate-500 font-medium">
        {filteredRows.length} {filteredRows.length === 1 ? 'interação encontrada' : 'interações encontradas'}
      </div>
    </div>
  );
};
