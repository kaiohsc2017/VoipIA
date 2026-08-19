import React, { useEffect, useState } from 'react';
import { Mic, MicOff, PhoneOff, Phone } from 'lucide-react';
import type {
  AgentStateView,
  CcPauseReason,
  InteractionView,
  ContactHistoryItem,
  ContactProfileView,
} from '../../api/types';

interface OperationSidebarProps {
  state: AgentStateView | null;
  interaction: InteractionView | null;
  pauseReasons: CcPauseReason[];
  onStateChange: (state: string, pauseReasonId?: number | null) => void;
  // WebRTC / SIP Phone
  status: 'idle' | 'ringing' | 'active' | 'registering' | 'held';
  remote: string;
  durationSeconds: number;
  muted: boolean;
  isIncoming: boolean;
  dialValue: string;
  setDialValue: (val: string) => void;
  doDial: () => void;
  doAnswer: () => void;
  doHangup: () => void;
  doMute: () => void;
  doDtmf: (key: string) => void;
  // Copilot & Profile
  contactHistory?: ContactHistoryItem[];
  profile?: ContactProfileView | null;
}

export const OperationSidebar: React.FC<OperationSidebarProps> = ({
  state,
  interaction,
  pauseReasons,
  onStateChange,
  status,
  remote,
  durationSeconds,
  muted,
  isIncoming,
  dialValue,
  setDialValue,
  doDial,
  doAnswer,
  doHangup,
  doMute,
  doDtmf,
  profile,
}) => {
  const [localSeconds, setLocalSeconds] = useState(state?.secondsInState || 0);

  useEffect(() => {
    setLocalSeconds(state?.secondsInState || 0);
  }, [state?.secondsInState]);

  useEffect(() => {
    const timer = setInterval(() => {
      setLocalSeconds((prev) => prev + 1);
    }, 1000);
    return () => clearInterval(timer);
  }, [state?.state, state?.pauseReasonId]);

  const formatTimer = (totalSec: number) => {
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const formatCallTimer = (totalSec: number) => {
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const agentName = state?.agentName || 'Agente';
  const extension = state?.sipExtension ? `Ramal ${state.sipExtension}` : 'Ramal —';
  const currentSt = state?.state || 'OFFLINE';
  const isCallActive = status === 'active' || status === 'ringing' || Boolean(interaction);
  const activeRemote = remote || interaction?.ani || 'Desconhecido';

  return (
    <aside className="w-72 xl:w-80 flex-shrink-0 bg-[#0c101c] text-slate-100 p-4 flex flex-col gap-4 border-r border-slate-800/80 overflow-y-auto h-full select-none">
      {/* ─── 1. Perfil & Presença do Agente ─── */}
      <div className="space-y-3 pb-3 border-b border-slate-800/80">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-indigo-950/80 border border-indigo-500/40 text-indigo-300 font-bold text-sm flex items-center justify-center shadow-inner">
            {agentName.split(' ').map((n) => n[0]).slice(0, 2).join('').toUpperCase()}
          </div>
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-white truncate">{agentName}</h2>
            <p className="text-[11px] text-slate-400 font-mono">{extension}</p>
          </div>
        </div>

        {/* Botão de Estado Atual */}
        <button
          onClick={() => onStateChange('DISPONIVEL')}
          className={`w-full px-3 py-2 rounded-xl text-xs font-medium flex items-center justify-between transition-all ${
            currentSt === 'DISPONIVEL'
              ? 'bg-emerald-950/70 border border-emerald-500/50 text-emerald-300 shadow-sm'
              : 'bg-slate-900 border border-slate-800 text-slate-300 hover:bg-slate-800'
          }`}
        >
          <div className="flex items-center gap-2">
            <span
              className={`w-2 h-2 rounded-full ${
                currentSt === 'DISPONIVEL' ? 'bg-emerald-400 animate-pulse' : 'bg-slate-500'
              }`}
            />
            <span>{currentSt === 'DISPONIVEL' ? 'Disponível' : 'Ficar Disponível'}</span>
          </div>
          <span className="font-mono text-[11px] text-slate-400">{formatTimer(localSeconds)}</span>
        </button>

        {/* Lista de Motivos de Pausa Reais do Backend */}
        <div className="space-y-1 pt-1">
          {pauseReasons.map((pr) => {
            const isSelected = currentSt === 'PAUSA' && state?.pauseReasonId === pr.id;
            return (
              <button
                key={pr.id}
                onClick={() => onStateChange('PAUSA', pr.id)}
                className={`w-full text-left px-3 py-1.5 rounded-lg text-xs flex items-center gap-2 transition-colors ${
                  isSelected
                    ? 'bg-amber-950/60 text-amber-300 font-semibold border border-amber-600/40'
                    : 'text-slate-300 hover:bg-slate-800/60 hover:text-white'
                }`}
              >
                <span className="w-1.5 h-1.5 rounded-full bg-amber-400" />
                <span>{pr.label}</span>
              </button>
            );
          })}

          <button
            onClick={() => onStateChange('OFFLINE')}
            className={`w-full text-left px-3 py-1.5 rounded-lg text-xs flex items-center gap-2 transition-colors ${
              currentSt === 'OFFLINE'
                ? 'bg-slate-800 text-white font-semibold'
                : 'text-slate-400 hover:bg-slate-800/60 hover:text-slate-200'
            }`}
          >
            <span className="w-1.5 h-1.5 rounded-full bg-slate-400" />
            <span>Sair da fila (Offline)</span>
          </button>
        </div>

        <p className="text-[10px] text-slate-500 italic leading-snug pt-1">
          Motivos de pausa cadastrados no sistema.
        </p>
      </div>

      {/* ─── 2. Softphone & Atendimento em Curso ─── */}
      <div className="bg-[#1e293b]/70 border border-slate-700/60 rounded-xl p-3.5 space-y-3 shadow-inner">
        {isCallActive ? (
          <>
            <div className="flex items-center justify-between">
              <span
                className={`text-[10px] font-bold px-2 py-0.5 rounded tracking-wide ${
                  status === 'ringing'
                    ? 'bg-amber-500/20 text-amber-400 border border-amber-500/40'
                    : 'bg-rose-500/20 text-rose-400 border border-rose-500/40'
                }`}
              >
                {status === 'ringing'
                  ? isIncoming
                    ? 'CHAMADA ENTRANTE'
                    : 'CHAMANDO...'
                  : 'EM ATENDIMENTO'}
              </span>
              <span className="text-xs font-mono font-bold text-white tracking-wider">
                {formatCallTimer(durationSeconds)}
              </span>
            </div>

            <div>
              <div className="text-base font-bold text-white tracking-wide font-mono">
                {activeRemote}
              </div>
              {interaction?.queueName && (
                <div className="text-[11px] text-slate-300 mt-0.5 leading-tight">
                  Fila: {interaction.queueName}
                </div>
              )}
              {profile?.resumoPerfil && (
                <div className="text-[11px] text-sky-400 font-medium mt-1 leading-tight">
                  {profile.resumoPerfil}
                </div>
              )}
            </div>

            {/* Controles de Chamada */}
            <div className="grid grid-cols-3 gap-1.5 pt-1">
              <button
                onClick={doMute}
                className={`py-1 text-xs rounded border transition-colors flex items-center justify-center gap-1 ${
                  muted
                    ? 'bg-amber-600 text-white border-amber-600'
                    : 'border-slate-600 bg-slate-800 text-slate-200 hover:bg-slate-700'
                }`}
              >
                {muted ? <MicOff size={13} /> : <Mic size={13} />}
                <span>Mudo</span>
              </button>
              <button
                onClick={() => {}}
                className="py-1 text-xs rounded border border-slate-600 bg-slate-800 text-slate-200 hover:bg-slate-700 transition-colors text-center"
              >
                Transferir
              </button>
              <button
                onClick={() => {}}
                className="py-1 text-xs rounded border border-slate-600 bg-slate-800 text-slate-200 hover:bg-slate-700 transition-colors text-center"
              >
                Teclado
              </button>
            </div>

            {status === 'ringing' && isIncoming && (
              <button
                onClick={doAnswer}
                className="w-full py-2 bg-emerald-600 hover:bg-emerald-700 text-white font-bold text-xs rounded-lg transition-colors flex items-center justify-center gap-1.5 shadow-sm"
              >
                <Phone size={14} /> Atender Chamada
              </button>
            )}

            <button
              onClick={doHangup}
              className="w-full py-1.5 bg-red-600 hover:bg-red-700 text-white font-bold text-xs rounded-lg transition-colors flex items-center justify-center gap-1.5 shadow-sm"
            >
              <PhoneOff size={14} /> Encerrar
            </button>
          </>
        ) : (
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-[10px] font-bold tracking-wider text-slate-400 uppercase">
                DISCADOR
              </span>
              <span className="text-[10px] text-emerald-400 font-mono">Pronto</span>
            </div>
            <div className="flex items-center gap-1.5">
              <input
                className="flex-1 px-2.5 py-1.5 text-xs rounded-lg border border-slate-700 bg-slate-900 text-white placeholder-slate-500 focus:outline-none focus:border-indigo-500"
                placeholder="Ramal ou número"
                value={dialValue}
                onChange={(e) => setDialValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') doDial();
                }}
              />
              <button
                onClick={doDial}
                disabled={!dialValue.trim()}
                className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold rounded-lg disabled:opacity-40 transition-colors"
              >
                Ligar
              </button>
            </div>
          </div>
        )}

        {/* Teclado Numérico 3x4 */}
        <div className="grid grid-cols-3 gap-1.5 pt-1">
          {['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#'].map((k) => (
            <button
              key={k}
              onClick={() => {
                if (status === 'active') doDtmf(k);
                else setDialValue(dialValue + k);
              }}
              className="py-1.5 bg-slate-800 hover:bg-slate-700 active:bg-indigo-600 text-slate-200 font-mono text-xs font-medium rounded-lg border border-slate-700/60 transition-colors"
            >
              {k}
            </button>
          ))}
        </div>
      </div>

      {/* ─── 3. Copiloto de IA ─── */}
      <div className="bg-[#1e293b]/70 border border-slate-700/60 rounded-xl p-3.5 space-y-2.5 shadow-inner">
        <div>
          <span className="text-[10px] font-bold tracking-wider text-indigo-400 uppercase">
            COPILOTO DE IA
          </span>
          {profile?.riscoEscalonamento != null && (
            <div className="text-[11px] text-slate-300 mt-0.5">
              Risco:{' '}
              <span
                className={`font-medium ${
                  profile.riscoEscalonamento >= 0.5 ? 'text-amber-400' : 'text-emerald-400'
                }`}
              >
                {profile.riscoEscalonamento >= 0.5 ? 'Médio/Alto' : 'Baixo'}
              </span>
            </div>
          )}
        </div>

        {profile?.resumoPerfil ? (
          <div className="bg-slate-900/80 border border-slate-800 rounded-lg p-2.5 space-y-1">
            <h4 className="text-xs font-bold text-slate-200">Resumo do Contato</h4>
            <p className="text-[11px] text-slate-400 leading-tight">{profile.resumoPerfil}</p>
          </div>
        ) : (
          <p className="text-[11px] text-slate-500 italic">
            Aguardando atendimento para exibir orientações e contexto do cliente.
          </p>
        )}
      </div>
    </aside>
  );
};
