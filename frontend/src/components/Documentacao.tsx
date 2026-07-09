import DocsLayout from './docs/DocsLayout';
import Instalacao from './docs/sections/Instalacao';
import TelecomModulos from './docs/sections/TelecomModulos';
import TelecomRBAC from './docs/sections/TelecomRBAC';
import Introducao from './docs/sections/Introducao';
import AgentesDashboard from './docs/sections/AgentesDashboard';
import AgentesTipos from './docs/sections/AgentesTipos';
import AgentesAutomacao from './docs/sections/AgentesAutomacao';
import AgentesInfra from './docs/sections/AgentesInfra';
import Sistema from './docs/sections/Sistema';

// Documentação oficial do AsteriskIA — migrada de
// agents-platform/frontend/docs.html (manual da Plataforma de Agentes) e
// expandida com as seções do Telecom (URA, Conectividade, Alertas, RBAC).
export default function Documentacao() {
  return (
    <DocsLayout>
      <Instalacao />
      <TelecomModulos />
      <TelecomRBAC />
      <Introducao />
      <AgentesDashboard />
      <AgentesTipos />
      <AgentesAutomacao />
      <AgentesInfra />
      <Sistema />
    </DocsLayout>
  );
}
