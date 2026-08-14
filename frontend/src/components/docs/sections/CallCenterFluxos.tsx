import { Section, SubSection, Card, FieldTable, FieldName, Badge, Callout } from '../DocsUI';

export default function CallCenterFluxos() {
  return (
    <>
      <Section id="callcenter-fluxos" title="Flow Builder — Voz e Chat">
        <p>
          Editor visual de fluxo (React Flow) que gera um grafo publicável, executado pelo mesmo
          motor (<code>FlowExecutionEngine</code>) tanto para voz (driver ARI) quanto para chat
          (driver de polling) — um nó implementado funciona nos dois canais sem código duplicado.
        </p>

        <SubSection title="Catálogo de nós — o que está implementado de fato">
          <Card>
            <FieldTable
              headers={['Nó', 'Status']}
              rows={[
                [<FieldName>menu_opcoes</FieldName>, <Badge tone="ok">implementado</Badge>],
                [<FieldName>coletar_texto / coletar_entrada</FieldName>, <Badge tone="ok">implementado</Badge>],
                [<FieldName>definir_variavel</FieldName>, <Badge tone="ok">implementado</Badge>],
                [<FieldName>tocar_audio</FieldName>, <Badge tone="ok">implementado</Badge>],
                [<FieldName>transferir_fila</FieldName>, <Badge tone="ok">implementado</Badge>],
                [<FieldName>consultar_base</FieldName>, <Badge tone="ok">implementado (RAG, Fase 25)</Badge>],
                [<FieldName>pesquisa_satisfacao</FieldName>, <Badge tone="ok">implementado (Fase 21)</Badge>],
                [<FieldName>consultar_api</FieldName>, <Badge tone="err">catálogo apenas — sem handler</Badge>],
              ]}
            />
          </Card>
          <Callout tone="err">
            <strong>Especificação de segurança obrigatória para <code>consultar_api</code></strong>{' '}
            (nenhum PR que implemente o handler deve ser aceito sem cobrir todos os itens, cada um
            com teste dedicado — Fase 10):
            <ol>
              <li>URL/host/porta do destino nunca vêm de texto livre editável no fluxo — resolvem de uma entrada pré-cadastrada em Settings por quem tem permissão de configuração, nunca de quem só edita fluxos.</li>
              <li>Mesmo guard de SSRF já usado em <code>CallCenterKbFetchService</code> (bloqueio de IP privado/loopback/link-local/IPv6 ULA), reaplicado integralmente — e restrito por allowlist de host cadastrado, não "qualquer host público" (o nó roda durante uma chamada/chat em andamento, com risco maior de abuso em massa do que a KB).</li>
              <li>Sem seguir redirect 3xx (desabilitar explicitamente, não confiar em default de biblioteca).</li>
              <li>Timeout curto com teto superior hardcoded (ex.: 10s) — sem isso, trava a thread do pool de execução durante uma chamada real.</li>
              <li>Teto de tamanho de corpo de resposta.</li>
              <li>Resposta tratada sempre como string opaca de tamanho limitado — nunca reinterpretada automaticamente como URL/comando por outro nó (evita SSRF de segundo grau).</li>
              <li>Falha nunca derruba a chamada — segue a aresta de erro do nó ou cai no fallback padrão.</li>
              <li>Se a API externa exigir autenticação, o header vem de Settings (nunca do fluxo) e nunca aparece em log de erro/traço.</li>
            </ol>
          </Callout>
        </SubSection>

        <SubSection title="Base de conhecimento / RAG (Fase 25)">
          <p>
            Artigos e fontes externas por URL, indexados por embeddings locais (CPU, container{' '}
            <code>insights</code> — custo de embedding zero, só a geração final chama a API do
            Gemini). O nó <code>consultar_base</code> responde apenas com base nos trechos
            recuperados, citando o artigo; sem trecho relevante acima do limiar, escala para fila
            humana — nunca inventa resposta.
          </p>
        </SubSection>

        <SubSection title="Biblioteca de áudios (Fase 5c)">
          <p>
            Upload transcodificado para PCM 8kHz/16-bit mono via <code>ffmpeg</code> — o arquivo
            original nunca é mantido. Teto de 3 transcodes simultâneos e 6 uploads/min por usuário
            (Fase 10) — antes, upload concorrente sem limite competia por CPU com o motor ARI/AMI no
            mesmo container.
          </p>
        </SubSection>
      </Section>
    </>
  );
}
