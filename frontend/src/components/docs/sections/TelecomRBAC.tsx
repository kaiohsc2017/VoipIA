import { Section, SubSection, Card, FieldTable, FieldName, Callout, Badge } from '../DocsUI';

// Conteúdo novo — RBAC granular (grupos de acesso, migration V22) e os
// ramais/softphone do Telecom, escrito a partir do CLAUDE.md e do
// código-fonte atual (Sidebar.tsx, ResourceCatalog.java, AccessGroupService).
export default function TelecomRBAC() {
  return (
    <>
      <Section id="telecom-rbac" title="Grupos de Acesso (RBAC)">
        <p>
          O acesso a cada menu do sistema é controlado por <strong>grupos de acesso</strong>{' '}
          configuráveis, em vez de um binário fixo Administrador/Usuário. Cada grupo tem permissão
          de leitura e/ou escrita por menu (<code>resource_key</code>, ex:{' '}
          <code>telecom.settings</code>, <code>agents.secrets</code>), gerenciada pela página{' '}
          <strong>"Grupos de Acesso"</strong> (apenas administradores).
        </p>

        <Card>
          <FieldTable
            headers={['Peça', 'Papel']}
            rows={[
              [<FieldName>access_groups / access_group_permissions</FieldName>, 'Tabelas que guardam os grupos e a matriz de permissões por resource_key'],
              [<FieldName>Catálogo de recursos</FieldName>, 'Lista fixa em código (ResourceCatalog.java, espelhada em Sidebar.tsx e no NAV da Plataforma de Agentes) — os menus são fixos, só a matriz de permissões é dinâmica'],
              [<FieldName>Claim role</FieldName>, <>Continua emitida no JWT em paralelo (dual-emit) por compatibilidade — tokens antigos sem a claim <code>perm</code> continuam válidos até expirar</>],
              [<FieldName>Claim perm</FieldName>, <><code>{'{resource_key: "r"|"w"|"rw"}'}</code> — resolvida do grupo do usuário no login/refresh/2FA</>],
            ]}
          />
        </Card>

        <SubSection title="Como é aplicado">
          <p>
            No <strong>backend Java</strong>, cada rota exige <code>ROLE_ADMIN</code> ou{' '}
            <code>PERM_READ_&lt;resource&gt;</code>/<code>PERM_WRITE_&lt;resource&gt;</code>{' '}
            conforme o método HTTP. A <strong>Plataforma de Agentes (FastAPI)</strong> não tem login
            próprio — reusa o mesmo JWT/segredo (<code>BACKEND_JWT_SECRET</code>) e valida a mesma
            claim <code>perm</code>. No <strong>frontend</strong>, a claim é decodificada apenas
            como sinalização de UI (sem validar assinatura) para esconder itens de menu e botões sem
            permissão — a aplicação real da regra é sempre feita no backend.
          </p>
        </SubSection>

        <Callout tone="info">
          Um administrador (role legada <code>ADMIN</code>) sempre enxerga tudo, mesmo com um token
          emitido antes do RBAC granular existir.
        </Callout>
      </Section>

      <Section id="telecom-softphone" title="Softphone e Ramais">
        <p>
          O Telecom inclui um softphone WebRTC embutido no próprio painel (via JsSIP), além de
          suportar softphones/telefones físicos externos.
        </p>

        <Card>
          <FieldTable
            headers={['Ramal', 'Uso']}
            rows={[
              [<Badge tone="purple">9001</Badge>, 'Softphone WebRTC embutido no frontend React'],
              [<Badge tone="info">9002</Badge>, 'Softphone físico / cliente externo (ex: Zoiper)'],
              [<Badge tone="gray">1001 / 1002</Badge>, 'Ramais internos de teste'],
            ]}
          />
        </Card>

        <p>
          As senhas SIP não ficam hardcoded no template versionado do Asterisk — são geradas/injetadas
          via variáveis de ambiente no boot do container. A conexão WebRTC (<code>wss://</code>) é
          terminada em TLS pelo Caddy e repassada como <code>ws://</code> para o Asterisk.
        </p>

        <Callout tone="ok">
          O tronco SIP de entrada/saída é autenticado por IP (peer fixo), sem usuário/senha — o
          acesso é restrito por firewall ao IP conhecido da operadora.
        </Callout>
      </Section>
    </>
  );
}
