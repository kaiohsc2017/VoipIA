import { Section, SubSection, Card, CardGrid, CardSm, FieldTable, FieldName, Callout, CodeBlock, Steps, Step, Key, Str, Cmt } from '../DocsUI';

// Processo de instalação automatizada — reflete install.sh (Ubuntu 22.04/24.04)
// e install-oracle9.sh (Oracle Linux 9), ambos versionados na raiz do repositório.
// Os dois scripts provisionam a mesma stack; o que muda é gerenciador de
// pacotes, firewall e (no caso do Oracle Linux) o tratamento de SELinux.
export default function Instalacao() {
  return (
    <>
      <Section id="instalacao-visao-geral" title="Requisitos e Visão Geral">
        <p>
          O VoipIA é instalado por um script único que provisiona todo o stack — Docker, banco,
          Asterisk, backend, frontend, IA, TURN/coturn, firewall e o watcher de lockdown SIP — a
          partir de uma VPS limpa. Existem duas variantes, uma por família de sistema operacional:
        </p>

        <Card>
          <FieldTable
            headers={['Script', 'Sistema', 'Gerenciador de pacotes', 'Firewall']}
            rows={[
              [<FieldName>install.sh</FieldName>, 'Ubuntu 22.04 LTS / 24.04 LTS', 'apt-get', 'ufw'],
              [<FieldName>install-oracle9.sh</FieldName>, 'Oracle Linux 9 (aceita RHEL/Rocky/AlmaLinux 9)', 'dnf', 'firewalld'],
            ]}
          />
        </Card>

        <CardGrid>
          <CardSm title="RAM recomendada">
            4 GB+. O script continua a instalação com aviso se detectar menos de 3.5 GB — o
            Asterisk, o backend Java e o AI Agent competem por memória sob carga.
          </CardSm>
          <CardSm title="Acesso">
            Root (ou sudo). Ambos os scripts recusam execução como usuário não-privilegiado.
          </CardSm>
          <CardSm title="DNS">
            O domínio usado pelo Caddy (<code>app.voiphash.com.br</code>) precisa apontar para o IP
            público da VPS <em>antes</em> de rodar o script — o Let's Encrypt valida o domínio por
            HTTP-01 durante o primeiro boot do Caddy.
          </CardSm>
          <CardSm title="Portas obrigatórias">
            22 (SSH), 80/443 (HTTP/HTTPS + QUIC), 5060 (SIP), 8088 (WebRTC WS), 16000-16500 (RTP),
            3478/5349 (TURN/TURNS) e 49152-49652 (relay TURN). Ambos os scripts abrem essas portas
            automaticamente no firewall nativo do sistema.
          </CardSm>
        </CardGrid>

        <Callout tone="info">
          Os dois scripts geram um <code>.env</code> novo com credenciais aleatórias (senhas de
          ramal SIP, JWT secret, senha do Postgres, credencial TURN etc.) na primeira execução —
          eles nunca sobrescrevem um <code>.env</code> já existente. Rodar o script de novo em uma
          instalação já configurada é seguro.
        </Callout>
      </Section>

      <Section id="instalacao-ubuntu" title="Instalação — Ubuntu 22.04 / 24.04">
        <CodeBlock label="Instalação em uma linha">
          <Cmt>{'# como root, numa VPS Ubuntu limpa\n'}</Cmt>
          {'curl -fsSL https://raw.githubusercontent.com/kaiohsc2017/VoipIA/main/install.sh | bash'}
        </CodeBlock>

        <p>Ou, clonando o repositório primeiro (recomendado para revisar o script antes de rodar):</p>
        <CodeBlock>
          {'git clone https://github.com/kaiohsc2017/VoipIA.git /opt/VoipIA\n'}
          {'cd /opt/VoipIA && bash install.sh'}
        </CodeBlock>

        <SubSection title="O que o script faz, em ordem">
          <Steps>
            <Step num={1} title="Verificações do sistema">
              Confirma Ubuntu 22.04/24.04, execução como root, detecta IP público e RAM disponível.
            </Step>
            <Step num={2} title="Dependências e Docker">
              Instala <code>curl wget git unzip jq ufw fail2ban gettext-base</code> via
              <code>apt-get</code>, depois Docker Engine + Compose v2 a partir do repositório
              oficial da Docker (se ainda não estiverem instalados).
            </Step>
            <Step num={3} title="Repositório e diretórios">
              Clona (ou atualiza, com <code>git pull</code>) o repositório em{' '}
              <code>/opt/VoipIA</code>. Cria <code>env/</code> com permissão <code>700</code> —
              só root lê o <code>.env</code> com os segredos.
            </Step>
            <Step num={4} title="Geração do .env">
              Gera senhas/segredos aleatórios (<code>openssl rand</code>) para admin, JWT, AMI,
              chave interna, ramais SIP (1001/1002/9001/9002) e credencial TURN. Campos de
              provedores externos (Gemini, Jira, Zabbix, Telegram) ficam em branco — configuráveis
              depois pelo painel em Settings.
            </Step>
            <Step num={5} title="Firewall (ufw) e lockdown SIP">
              Zera e reconfigura o <code>ufw</code> com as portas obrigatórias, instala o serviço
              systemd <code>voipia-lockdown</code> (watcher que reage a tentativas de acesso
              indevido às portas SIP) e aplica as regras nftables raw que isolam os IPs internos dos
              containers (<code>security/apply-raw-rules.sh</code>).
            </Step>
            <Step num={6} title="Build e subida dos containers">
              <code>docker compose build --no-cache</code> seguido de <code>up -d</code>. Se o build
              falhar e houver <code>GEMINI_API_KEY</code> configurada, o script consulta o Gemini
              para sugerir um diagnóstico — os comandos sugeridos <strong>nunca são aplicados
              automaticamente</strong>, exigem confirmação interativa explícita.
            </Step>
            <Step num={7} title="Verificação">
              Aguarda o Caddy emitir o certificado TLS, reinicia o <code>coturn</code> para montá-lo
              (TURNS/TLS só funciona depois disso), verifica a saúde dos 10 containers do stack e
              testa <code>https://app.voiphash.com.br/api/health</code>.
            </Step>
          </Steps>
        </SubSection>

        <Callout tone="ok">
          Ao final, o script imprime a senha do usuário <code>admin</code> gerada e a senha do ramal
          SIP <code>9002</code> (softphone físico) — guarde essas credenciais, elas não são
          reexibidas depois.
        </Callout>

        <SubSection title="Atualizar uma instalação existente">
          <CodeBlock>{'cd /opt/VoipIA && bash install.sh --update'}</CodeBlock>
          <p>
            Faz <code>git pull</code>, rebuild completo (<code>--no-cache</code>) e recarrega o
            Caddyfile via socket Unix — sem precisar derrubar o stack manualmente.
          </p>
        </SubSection>
      </Section>

      <Section id="instalacao-oracle" title="Instalação — Oracle Linux 9">
        <p>
          Mesmo stack do Ubuntu, mesmo <code>.env</code>, mesmo <code>docker-compose.yml</code> — o
          que muda é só o provisionamento do sistema operacional (pacotes, firewall e SELinux).
        </p>

        <CodeBlock label="Instalação">
          {'git clone https://github.com/kaiohsc2017/VoipIA.git /opt/VoipIA\n'}
          {'cd /opt/VoipIA && bash install-oracle9.sh'}
        </CodeBlock>

        <SubSection title="Diferenças em relação ao install.sh">
          <Card>
            <FieldTable
              headers={['Etapa', 'Ubuntu (install.sh)', 'Oracle Linux 9 (install-oracle9.sh)']}
              rows={[
                ['Pacotes', <code>apt-get</code>, <code>dnf</code>],
                ['Firewall', <code>ufw</code>, <code>firewalld</code>],
                ['Docker CE', 'repositório oficial ubuntu', 'repositório oficial centos/rhel (compatível com OL9)'],
                ['EPEL', 'não se aplica', <>necessário para instalar <code>fail2ban</code> — via <code>oracle-epel-release-el9</code></>],
                ['SELinux', 'não se aplica', 'ajustado de Enforcing para Permissive (ver callout abaixo)'],
                ['Conflitos de pacote', 'não se aplica', <>remove <code>podman</code>/<code>buildah</code>/<code>runc</code> antes de instalar o docker-ce, para não travar o <code>dnf</code> em conflito de pacotes</>],
              ]}
            />
          </Card>
        </SubSection>

        <Callout tone="warn">
          <strong>SELinux:</strong> Oracle Linux 9 vem com SELinux <code>Enforcing</code> por padrão,
          o que bloqueia os bind mounts que o <code>docker-compose.yml</code> usa (
          <code>env/.env</code>, <code>asterisk/config</code>, <code>security/state</code> etc.) sem
          relabeling dedicado. Para uma instalação limpa, o script muda o SELinux para{' '}
          <code>Permissive</code> (persistido em <code>/etc/selinux/config</code>). Para reforçar
          depois, a alternativa é adicionar o sufixo <code>:z</code> em cada bind mount do compose e
          voltar para <code>Enforcing</code> — isso não é feito automaticamente porque o{' '}
          <code>docker-compose.yml</code> é compartilhado com o ambiente Ubuntu de produção.
        </Callout>

        <SubSection title="Ordem importa: firewalld antes do Docker">
          <p>
            O script configura o <code>firewalld</code> (portas + masquerade) e só então inicia o
            Docker. Reiniciar o <code>firewalld</code> depois que o Docker já criou suas próprias
            chains de NAT/FORWARD apaga essas regras — os containers ficam sem rede até o Docker ser
            reiniciado. Se precisar abrir uma porta nova depois da instalação:
          </p>
          <CodeBlock>
            {'firewall-cmd --permanent --add-port=PORTA/PROTO\n'}
            {'firewall-cmd --reload'}
          </CodeBlock>
        </SubSection>

        <SubSection title="Atualizar uma instalação existente">
          <CodeBlock>{'cd /opt/VoipIA && bash install-oracle9.sh --update'}</CodeBlock>
        </SubSection>
      </Section>

      <Section id="instalacao-pos" title="Pós-Instalação">
        <p>Independente do sistema operacional, os passos finais são os mesmos:</p>
        <Steps>
          <Step num={1} title="Configurar a IA">
            Painel → Settings → Inteligência Artificial. Cole a API Key do Google Gemini
            (aistudio.google.com). Use <code><Key>STT</Key>/<Key>LLM</Key></code>:{' '}
            <Str>gemini-2.5-flash</Str> e <Key>TTS</Key>: <Str>gemini-2.5-flash-preview-tts</Str> —{' '}
            <code>gemini-2.0-flash</code> foi descontinuado.
          </Step>
          <Step num={2} title="Configurar Jira e Zabbix (opcional)">
            Painel → Settings → Jira / Zabbix, se for usar abertura automática de chamados ou
            alertas por ligação.
          </Step>
          <Step num={3} title="Confirmar o IP público no .env">
            <code>grep SIP_PUBLIC_IP /opt/VoipIA/env/.env</code> — precisa bater com o IP público
            real da VPS, senão o RTP/WebRTC não funciona por trás de NAT.
          </Step>
          <Step num={4} title="Testar a chamada interna">
            No softphone WebRTC embutido no painel, disque <code>1000</code> — deve ouvir a mensagem
            de boas-vindas da URA legada.
          </Step>
        </Steps>
      </Section>
    </>
  );
}
