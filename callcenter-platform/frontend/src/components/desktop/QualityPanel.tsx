import React, { useState } from 'react';
import {
  Award,
  AlertTriangle,
  CheckCircle,
  HelpCircle,
  Clock,
  Send,
  ChevronDown,
  ChevronUp,
  Target,
  Check,
  RotateCcw,
} from 'lucide-react';
import type {
  DesktopQualityView,
  DesktopEvaluationDetailView,
  CoachingPlanView,
} from '../../api/types';

interface QualityPanelProps {
  quality: DesktopQualityView | null;
  evaluations: DesktopEvaluationDetailView[];
  coachingPlans: CoachingPlanView[];
  loading: boolean;
  onOpenAppeal: (evalDetail: DesktopEvaluationDetailView) => void;
  onUpdateCoachingStatus: (planId: number, newStatus: 'EM_ANDAMENTO' | 'CONCLUIDO') => void;
}

export const QualityPanel: React.FC<QualityPanelProps> = ({
  quality,
  evaluations,
  coachingPlans,
  loading,
  onOpenAppeal,
  onUpdateCoachingStatus,
}) => {
  const [expandedEvalId, setExpandedEvalId] = useState<number | null>(null);

  if (loading) {
    return (
      <div className="py-12 text-center text-xs text-slate-500">
        Carregando dados de qualidade e coaching...
      </div>
    );
  }

  const toggleExpand = (id: number) => {
    setExpandedEvalId(expandedEvalId === id ? null : id);
  };

  const getScoreColor = (score: number) => {
    if (score >= 85) return 'text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 border-emerald-500/20';
    if (score >= 70) return 'text-blue-600 dark:text-blue-400 bg-blue-500/10 border-blue-500/20';
    if (score >= 50) return 'text-amber-600 dark:text-amber-400 bg-amber-500/10 border-amber-500/20';
    return 'text-red-600 dark:text-red-400 bg-red-500/10 border-red-500/20';
  };

  return (
    <div className="space-y-6">
      {/* ─── 1. Resumo Consolidado ─────────────────────────────────────────── */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-1">
          <Award className="text-indigo-600 dark:text-indigo-400" size={18} />
          <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
            Painel de Monitoria de Qualidade
          </h3>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400 mb-6">
          Acompanhe suas notas de atendimento, feedbacks e planos de coaching personalizados
        </p>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="flex items-center gap-4 p-5 rounded-xl bg-indigo-50/50 dark:bg-indigo-950/20 border border-indigo-100 dark:border-indigo-900/40">
            <div className="w-16 h-16 rounded-full bg-indigo-600 text-white font-bold text-2xl flex items-center justify-center shadow-md">
              {quality?.avgScore != null ? quality.avgScore.toFixed(0) : '—'}
            </div>
            <div>
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400">
                Nota Média Geral
              </p>
              <p className="text-sm font-semibold text-slate-900 dark:text-slate-100 mt-0.5">
                {quality?.totalEvaluations ?? 0} chamadas avaliadas
              </p>
            </div>
          </div>

          <div className="p-4 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/20">
            <h4 className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 mb-2 flex items-center gap-1.5">
              <CheckCircle size={14} /> Pontos Fortes
            </h4>
            {quality?.strongPoints && quality.strongPoints.length > 0 ? (
              <ul className="space-y-1 text-xs text-slate-600 dark:text-slate-300">
                {quality.strongPoints.map((pt, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <span className="text-emerald-500">•</span> <span>{pt}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-slate-400 italic">Nenhum ponto registrado.</p>
            )}
          </div>

          <div className="p-4 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/20">
            <h4 className="text-xs font-semibold text-amber-600 dark:text-amber-400 mb-2 flex items-center gap-1.5">
              <AlertTriangle size={14} /> Oportunidades de Melhoria
            </h4>
            {quality?.improvementPoints && quality.improvementPoints.length > 0 ? (
              <ul className="space-y-1 text-xs text-slate-600 dark:text-slate-300">
                {quality.improvementPoints.map((pt, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <span className="text-amber-500">•</span> <span>{pt}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs text-slate-400 italic">Nenhum ponto pendente.</p>
            )}
          </div>
        </div>
      </div>

      {/* ─── 2. Planos de Ação e Coaching ─────────────────────────────────── */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Target className="text-indigo-600 dark:text-indigo-400" size={18} />
            <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
              Planos de Ação & Coaching Ativos
            </h3>
          </div>
          <span className="text-xs px-2.5 py-0.5 rounded-full font-medium bg-indigo-50 dark:bg-indigo-950/40 text-indigo-600 dark:text-indigo-400">
            {coachingPlans.filter((p) => p.status === 'EM_ANDAMENTO').length} em andamento
          </span>
        </div>

        {coachingPlans.length === 0 ? (
          <div className="py-8 text-center text-xs text-slate-500 bg-slate-50 dark:bg-slate-800/30 rounded-lg border border-slate-200 dark:border-slate-800">
            Nenhum plano de ação atribuído no momento. Parabéns pelo desempenho!
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {coachingPlans.map((plan) => {
              const isCompleted = plan.status === 'CONCLUIDO';
              return (
                <div
                  key={plan.id}
                  className={`p-4 rounded-xl border transition-all ${
                    isCompleted
                      ? 'bg-slate-50/60 dark:bg-slate-900/40 border-slate-200 dark:border-slate-800 opacity-75'
                      : 'bg-white dark:bg-slate-900 border-indigo-200 dark:border-indigo-900/50 shadow-xs'
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <h4 className="text-xs font-bold text-slate-900 dark:text-slate-100">
                        {plan.title}
                      </h4>
                      {plan.scorecardItemQuestion && (
                        <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                          Critério: {plan.scorecardItemQuestion}
                        </p>
                      )}
                    </div>
                    <span
                      className={`text-[10px] px-2 py-0.5 rounded-full font-semibold ${
                        isCompleted
                          ? 'bg-emerald-500/10 text-emerald-600 border border-emerald-500/20'
                          : 'bg-amber-500/10 text-amber-600 border border-amber-500/20'
                      }`}
                    >
                      {isCompleted ? 'Concluído' : 'Em Andamento'}
                    </span>
                  </div>

                  <p className="text-xs text-slate-700 dark:text-slate-300 mt-2 whitespace-pre-wrap leading-relaxed">
                    {plan.description}
                  </p>

                  <div className="flex items-center justify-between pt-3 mt-3 border-t border-slate-100 dark:border-slate-800/80 text-[11px]">
                    <div className="flex items-center gap-1.5 text-slate-500">
                      <Clock size={12} />
                      <span>
                        Prazo: {plan.deadline ? new Date(plan.deadline).toLocaleDateString('pt-BR') : '—'}
                      </span>
                    </div>

                    <button
                      onClick={() =>
                        onUpdateCoachingStatus(plan.id, isCompleted ? 'EM_ANDAMENTO' : 'CONCLUIDO')
                      }
                      className={`px-2.5 py-1 rounded text-xs font-medium transition-colors flex items-center gap-1 ${
                        isCompleted
                          ? 'bg-slate-100 hover:bg-slate-200 text-slate-600 dark:bg-slate-800'
                          : 'bg-emerald-600 hover:bg-emerald-700 text-white'
                      }`}
                    >
                      {isCompleted ? (
                        <>
                          <RotateCcw size={12} /> Reabrir
                        </>
                      ) : (
                        <>
                          <Check size={12} /> Marcar Concluído
                        </>
                      )}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ─── 3. Lista de Avaliações de Chamadas ───────────────────────────── */}
      <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm">
        <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100 mb-4">
          Avaliações de Chamadas Recentes
        </h3>

        {evaluations.length === 0 ? (
          <div className="py-8 text-center text-xs text-slate-500 bg-slate-50 dark:bg-slate-800/30 rounded-lg border border-slate-200 dark:border-slate-800">
            Nenhuma avaliação registrada no período selecionado.
          </div>
        ) : (
          <div className="space-y-3">
            {evaluations.map((evalDetail) => {
              const isExpanded = expandedEvalId === evalDetail.evaluationId;
              const hasAppeal = evalDetail.appeal != null;

              return (
                <div
                  key={evalDetail.evaluationId}
                  className="border border-slate-200 dark:border-slate-800 rounded-xl overflow-hidden bg-white dark:bg-slate-900 shadow-xs"
                >
                  <div
                    onClick={() => toggleExpand(evalDetail.evaluationId)}
                    className="p-4 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 cursor-pointer hover:bg-slate-50/80 dark:hover:bg-slate-800/40 transition-colors"
                  >
                    <div className="flex items-center gap-3">
                      <div
                        className={`w-10 h-10 rounded-lg font-bold text-sm flex items-center justify-center border ${getScoreColor(
                          evalDetail.notaTotal
                        )}`}
                      >
                        {evalDetail.notaTotal.toFixed(0)}
                      </div>
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-semibold text-slate-900 dark:text-slate-100">
                            Atendimento #{evalDetail.interactionId ?? evalDetail.evaluationId}
                          </span>
                          {evalDetail.isFailed && (
                            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-red-500/10 text-red-600 dark:text-red-400 border border-red-500/20">
                              REPROVADO (AUTO-FAIL)
                            </span>
                          )}
                          {hasAppeal && (
                            <span
                              className={`px-2 py-0.5 rounded text-[10px] font-semibold border ${
                                evalDetail.appeal?.status === 'APROVADA'
                                  ? 'bg-emerald-500/10 text-emerald-600 border-emerald-500/20'
                                  : evalDetail.appeal?.status === 'REJEITADA'
                                  ? 'bg-red-500/10 text-red-600 border-red-500/20'
                                  : 'bg-amber-500/10 text-amber-600 border-amber-500/20'
                              }`}
                            >
                              Contestação: {evalDetail.appeal?.status}
                            </span>
                          )}
                        </div>
                        <p className="text-[11px] text-slate-500 mt-0.5">
                          {evalDetail.callDateTime
                            ? new Date(evalDetail.callDateTime).toLocaleString('pt-BR')
                            : '—'}{' '}
                          · Fila: {evalDetail.queueName ?? '—'} · Contato: {evalDetail.ani ?? '—'}
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 self-end sm:self-center">
                      {!hasAppeal && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            onOpenAppeal(evalDetail);
                          }}
                          className="px-3 py-1.5 text-xs font-medium rounded-lg bg-indigo-50 hover:bg-indigo-100 text-indigo-600 dark:bg-indigo-950/40 dark:hover:bg-indigo-900/60 dark:text-indigo-400 transition-colors flex items-center gap-1"
                        >
                          <Send size={12} /> Contestar Nota
                        </button>
                      )}
                      <div className="p-1 text-slate-400">
                        {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </div>
                    </div>
                  </div>

                  {isExpanded && (
                    <div className="p-4 border-t border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-950/40 space-y-4">
                      {evalDetail.failReason && (
                        <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-600 dark:text-red-400 text-xs font-medium">
                          Motivo da Reprovação Crítica: {evalDetail.failReason}
                        </div>
                      )}

                      {evalDetail.appeal && (
                        <div className="p-3.5 rounded-xl bg-amber-500/5 border border-amber-500/20 space-y-2 text-xs">
                          <p className="font-semibold text-amber-700 dark:text-amber-400 flex items-center gap-1.5">
                            <HelpCircle size={14} /> Sua Contestação ({evalDetail.appeal.status}):
                          </p>
                          <p className="text-slate-700 dark:text-slate-300 italic whitespace-pre-wrap">
                            "{evalDetail.appeal.reason}"
                          </p>
                          {evalDetail.appeal.supervisorNotes && (
                            <div className="pt-2 border-t border-amber-200/40 dark:border-amber-900/40">
                              <span className="font-semibold text-slate-700 dark:text-slate-300">
                                Parecer do Supervisor ({evalDetail.appeal.reviewedBy}):
                              </span>
                              <p className="text-slate-600 dark:text-slate-400 mt-0.5">
                                {evalDetail.appeal.supervisorNotes}
                              </p>
                            </div>
                          )}
                        </div>
                      )}

                      <h5 className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                        Critérios da Ficha: {evalDetail.scorecardName}
                      </h5>

                      <div className="space-y-2">
                        {evalDetail.items.map((item) => (
                          <div
                            key={item.itemId}
                            className="p-3 rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-xs space-y-1.5"
                          >
                            <div className="flex items-center justify-between">
                              <span className="font-medium text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                                {item.isCritical && (
                                  <span className="text-red-500 font-bold" title="Item Crítico (Auto-Fail)">
                                    *
                                  </span>
                                )}
                                {item.pergunta}
                              </span>
                              <span className="font-mono font-bold text-slate-700 dark:text-slate-300">
                                {item.nota} / {item.notaMaxima} (peso {item.peso})
                              </span>
                            </div>

                            {item.justificativa && (
                              <p className="text-slate-600 dark:text-slate-400 text-[11px]">
                                {item.justificativa}
                              </p>
                            )}

                            {item.trechoReferencia && (
                              <div className="text-[10px] font-mono bg-slate-50 dark:bg-slate-950 p-2 rounded border border-slate-100 dark:border-slate-800 text-slate-500">
                                <strong>Trecho:</strong> "{item.trechoReferencia}"
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
