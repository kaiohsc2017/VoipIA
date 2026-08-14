import { useEffect, useRef, useState } from 'react';
import { PlayCircle, X } from 'lucide-react';
import api, { getErrorMessage } from '../../api/client';
import type { FlowSimulationResult } from '../../api/types';

interface SimulationPanelProps {
  flowId: number;
  onClose: () => void;
}

/**
 * SimulationPanel — painel de roteiro do simulador de fluxo (Fase 5d, dry-run). O operador informa
 * as respostas simuladas (uma por linha, consumidas em ordem sempre que o fluxo pedir uma entrada
 * do "cliente": menu, coleta de texto, gravação de resposta) e roda contra o rascunho atual — nunca
 * contra tráfego real, nunca persiste `cc_flow_executions`/`cc_flow_execution_steps`, e os nós de
 * IA (consultar base de conhecimento, pesquisa de satisfação) sempre respondem em modo seco, sem
 * chamar o Gemini de verdade (ver `FlowSimulationService` no backend).
 */
export function SimulationPanel({ flowId, onClose }: SimulationPanelProps) {
  const [respostasRaw, setRespostasRaw] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<FlowSimulationResult | null>(null);

  // Guarda de sequência — mesmo padrão já usado noutras telas do Call Center (ex.: perfil do
  // cliente) para descartar uma resposta antiga que chegue depois de uma requisição mais nova
  // (o operador pode clicar "Rodar simulação" de novo antes da anterior voltar).
  const requestSeqRef = useRef(0);
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);

  const runSimulation = () => {
    const seq = ++requestSeqRef.current;
    setLoading(true);
    setError('');
    setResult(null);
    const respostasSimuladas = respostasRaw
      .split('\n')
      .map(linha => linha.trim())
      .filter(linha => linha.length > 0);

    api
      .post<FlowSimulationResult>(`/callcenter/fluxos/${flowId}/simulate`, {
        variaveis: {},
        respostasSimuladas,
      })
      .then(({ data }) => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setResult(data);
      })
      .catch(err => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setError(getErrorMessage(err, 'Erro ao simular o fluxo.'));
      })
      .finally(() => {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setLoading(false);
      });
  };

  return (
    <div className="flow-properties flow-simulation-panel">
      <div className="flex" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 className="flow-palette-title">Simulador (dry-run)</h3>
        <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={loading} title="Fechar simulador">
          <X size={14} />
        </button>
      </div>
      <p className="flow-palette-hint">
        Roda o rascunho atual sem tocar Asterisk/chat real e sem chamar IA de verdade nos nós de
        consulta à base/pesquisa de satisfação. Uma resposta simulada por linha, consumida em
        ordem sempre que o fluxo pedir uma entrada do cliente.
      </p>
      <label className="form-label" htmlFor="flow-simulation-input">Respostas simuladas</label>
      <textarea
        id="flow-simulation-input"
        className="flow-simulation-input"
        rows={4}
        placeholder={'1\nRobson\nsim'}
        value={respostasRaw}
        disabled={loading}
        onChange={e => setRespostasRaw(e.target.value)}
      />
      <button className="btn btn-primary btn-sm" onClick={runSimulation} disabled={loading} style={{ marginTop: 8 }}>
        <PlayCircle size={14} /> {loading ? 'Simulando…' : 'Rodar simulação'}
      </button>

      {error && <div className="flow-issue flow-issue-error" style={{ marginTop: 8 }}>{error}</div>}

      {result && (
        <div className="flow-simulation-result">
          <div className="flow-simulation-outcome">
            Resultado: <strong>{result.outcome}</strong> (versão {result.versionStatus})
          </div>
          <ol className="flow-simulation-steps">
            {result.steps.map((step, i) => (
              <li key={`${step.nodeId}-${i}`} className="flow-simulation-step">
                <div className="flow-simulation-step-header">
                  <span className="flow-node-type">{step.nodeType ?? '?'}</span>
                  <span>{step.label ?? step.nodeId}</span>
                </div>
                {step.detail && <div className="flow-simulation-step-detail">{step.detail}</div>}
              </li>
            ))}
          </ol>
          {Object.keys(result.finalVariables).length > 0 && (
            <div className="flow-simulation-variables">
              <div className="flow-palette-hint">Variáveis ao final:</div>
              {Object.entries(result.finalVariables).map(([k, v]) => (
                <div key={k} className="flow-node-menu-option">
                  <span>{k}</span>
                  <span>{v}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
