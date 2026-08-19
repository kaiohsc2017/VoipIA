# Documentação das APIs — VoipIA

> **Versão da API:** v1 (`/api/v1`)  
> **Protocolos Suportados:** REST (JSON), WebSocket (STOMP/SockJS), AudioSocket (PCM Linear)  
> **Base URL:** `https://voipia.voiphash.com.br/api/v1`

---

## 1. Padrões Globais & Autenticação

### 1.1 Headers Padrão
Todas as requisições autenticadas devem incluir o header HTTP `Authorization`:
```http
Authorization: Bearer <SEU_JWT_TOKEN>
Content-Type: application/json
Accept: application/json
```

### 1.2 Tratamento de Erros
Em caso de erro, a API retorna uma resposta padronizada:
```json
{
  "message": "Descrição amigável do erro",
  "status": 400,
  "timestamp": "2026-08-19T03:00:00Z"
}
```

---

## 2. Autenticação & Sessão

### 2.1 Login do Usuário
Autentica o usuário com login e senha e emite o token JWT de acesso. O refresh token é gravado automaticamente em um cookie `httpOnly` seguro.

- **Método:** `POST`
- **Endpoint:** `/auth/login`
- **Autenticação:** Pública

#### Request Payload:
```json
{
  "username": "admin",
  "password": "SuaSenhaSegura@123"
}
```

#### Exemplo cURL:
```bash
curl -X POST "https://voipia.voiphash.com.br/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "SuaSenhaSegura@123"}'
```

#### Response (200 OK):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "admin",
  "role": "ADMIN",
  "extension": 9001,
  "firstLoginCompleted": true,
  "requiresTotp": false
}
```

---

### 2.2 Renovação de Token (Refresh Token)
Renova o token JWT de curta duração sem exigir nova digitação de senha, lendo o cookie seguro `voipia_refresh_token`.

- **Método:** `POST`
- **Endpoint:** `/auth/refresh`
- **Autenticação:** Cookie `httpOnly`

#### Exemplo cURL:
```bash
curl -X POST "https://voipia.voiphash.com.br/api/v1/auth/refresh" \
  --cookie "voipia_refresh_token=REFRESH_TOKEN_AQUI"
```

#### Response (200 OK):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9_NOVO_TOKEN..."
}
```

---

### 2.3 Obtenção de Streaming Token
Gera um token JWT efêmero (TTL de 60 segundos) com escopo restrito `scope=stream`, utilizado para autenticar conexões WebSocket e SSE sem expor o JWT principal na query string da URL.

- **Método:** `POST`
- **Endpoint:** `/auth/streaming-token`
- **Autenticação:** Bearer Token

#### Exemplo cURL:
```bash
curl -X POST "https://voipia.voiphash.com.br/api/v1/auth/streaming-token" \
  -H "Authorization: Bearer <TOKEN>"
```

#### Response (200 OK):
```json
{
  "streamingToken": "eyJhbGciOiJIUzI1NiJ9_STREAMING_TOKEN_60S..."
}
```

---

## 3. APIs de Telefonia & URA

### 3.1 Listagem de Chamadas Atendidas (CDR)
Retorna a listagem paginada e filtrada de chamadas registradas pela URA e Call Center.

- **Método:** `GET`
- **Endpoint:** `/calls`
- **Query Params:** `page` (int), `size` (int), `dateFrom` (YYYY-MM-DD), `dateTo` (YYYY-MM-DD), `search` (string)

#### Exemplo cURL:
```bash
curl -X GET "https://voipia.voiphash.com.br/api/v1/calls?page=0&size=20" \
  -H "Authorization: Bearer <TOKEN>"
```

#### Response (200 OK):
```json
{
  "content": [
    {
      "id": 1042,
      "callUuid": "a8f1b4d2-28e4-4c12-9c12-78d123456789",
      "callerNumber": "11999998888",
      "callDate": "2026-08-19T02:45:00Z",
      "callDurationSecs": 58,
      "jiraIssueKey": "SD-4821",
      "clientName": "Acme Corp",
      "businessUnitName": "Operação Sudeste",
      "audioFileName": "rec-1042.wav"
    }
  ],
  "totalPages": 15,
  "totalElements": 298
}
```

---

### 3.2 Registro de Chamada e Abertura Automática de Chamado
Endpoint chamado pelo agente de voz `ai-agent` ao término da conversa na URA para persistir a gravação, custos de tokens e criar o ticket no Jira.

- **Método:** `POST`
- **Endpoint:** `/calls/register`
- **Formato:** `multipart/form-data`

#### Campos:
- `callUuid`: Identificador único da chamada no Asterisk (UUID)
- `uraId`: ID da URA executada (ex: `1`)
- `callerNumber`: Número do telefone de quem ligou
- `collectedData`: JSON com respostas das perguntas feitas pela IA
- `audio`: Arquivo de áudio gravado (WAV)

#### Exemplo cURL:
```bash
curl -X POST "https://voipia.voiphash.com.br/api/v1/calls/register" \
  -H "Authorization: Bearer <TOKEN>" \
  -F "callUuid=a8f1b4d2-28e4-4c12-9c12-78d123456789" \
  -F "uraId=1" \
  -F "callerNumber=11999998888" \
  -F "collectedData={\"nome\": \"Carlos Silva\", \"descricao\": \"Servidor instável\", \"urgencia\": \"Alta\"}" \
  -F "audio=@/tmp/gravacao_chamada.wav"
```

#### Response (201 Created):
```json
{
  "id": 1043,
  "callUuid": "a8f1b4d2-28e4-4c12-9c12-78d123456789",
  "jiraIssueKey": "SD-4822",
  "status": "REGISTERED"
}
```

---

### 3.3 Status do Tronco SIP com Operadora
Verifica em tempo real a conectividade do Asterisk com o tronco SIP da operadora via comando AMI.

- **Método:** `GET`
- **Endpoint:** `/stats/trunk-status`

#### Response (200 OK):
```json
{
  "status": "ONLINE",
  "rttMs": 18,
  "checkedAt": "2026-08-19T03:10:00Z"
}
```

---

## 4. APIs do Módulo Call Center

### 4.1 Resumo Operacional do Agente Logado (Desktop do Agente)
Carrega os KPIs diários, tempo logado e status atual do operador.

- **Método:** `GET`
- **Endpoint:** `/callcenter/desktop/me/summary`

#### Response (200 OK):
```json
{
  "agentName": "Ana Oliveira",
  "sipExtension": "4001",
  "state": "DISPONIVEL",
  "loggedSeconds": 14400,
  "pauseSeconds": 1200,
  "adherencePct": 96.5,
  "totalCallsHandled": 32,
  "averageHandleTimeSec": 240,
  "qualityScoreAvg": 94.0
}
```

---

### 4.2 Alteração de Estado / Pausa do Agente
Atualiza o estado do agente na fila (Disponível, Pausa, Offline).

- **Método:** `POST`
- **Endpoint:** `/callcenter/desktop/me/state`

#### Request Payload:
```json
{
  "state": "PAUSA",
  "pauseReasonId": 2
}
```

#### Response (200 OK):
```json
{
  "success": true,
  "state": "PAUSA",
  "pauseReason": "Pausa Lanche / Café",
  "updatedAt": "2026-08-19T03:15:00Z"
}
```

---

### 4.3 Envio de Contestação de Avaliação de Qualidade
Permite ao agente contestar uma nota de monitoria atribuída pela supervisão ou IA.

- **Método:** `POST`
- **Endpoint:** `/callcenter/desktop/avaliacoes/{id}/contestar`

#### Request Payload:
```json
{
  "reason": "Discordo do desconto no critério de saudação, pois confirmei os dados aos 0:15 do áudio."
}
```

#### Response (200 OK):
```json
{
  "appealId": 89,
  "evaluationId": 450,
  "status": "PENDING_SUPERVISOR_REVIEW",
  "createdAt": "2026-08-19T03:16:00Z"
}
```

---

## 5. APIs do Módulo Insights (Analítico & Transcrição)

### 5.1 Obter Transcrição Diarizada da Chamada
Retorna a transcrição separada por locutores com timestamps e marcação de sentimento.

- **Método:** `GET`
- **Endpoint:** `/insights/calls/{id}/transcription`

#### Response (200 OK):
```json
{
  "callId": 1042,
  "sentiment": "POSITIVE",
  "sentimentScore": 0.85,
  "summary": "Cliente solicitou desbloqueio de acesso. Problema resolvido com sucesso.",
  "segments": [
    {
      "speaker": "AGENT",
      "startTime": 0.0,
      "endTime": 4.2,
      "text": "Olá, bom dia! Meu nome é Ana, como posso ajudar hoje?"
    },
    {
      "speaker": "CUSTOMER",
      "startTime": 4.5,
      "endTime": 9.1,
      "text": "Bom dia, Ana. Preciso de suporte para desbloquear meu login."
    }
  ]
}
```

---

## 6. APIs Financeiras (Auditoria de Custos de IA)

### 6.1 Resumo de Custos de IA por Período
Retorna o custo agrupado por mês dos serviços de LLM, STT e TTS.

- **Método:** `GET`
- **Endpoint:** `/calls/costs/summary`
- **Query Params:** `dateFrom=2026-01-01&dateTo=2026-12-31`

#### Response (200 OK):
```json
[
  {
    "month": 8,
    "year": 2026,
    "totalCostUsd": 42.18,
    "sttCostUsd": 12.40,
    "ttsCostUsd": 18.50,
    "llmCostUsd": 11.28,
    "totalCalls": 1840
  }
]
```

---

## 7. APIs de Administração & Usuários

### 7.1 Criação de Usuário
- **Método:** `POST`
- **Endpoint:** `/users`
- **Permissão:** `ADMIN` ou `telecom.users` com escrita

#### Request Payload:
```json
{
  "username": "joao.silva",
  "displayName": "João Silva",
  "email": "joao.silva@empresa.com.br",
  "password": "SenhaInicialForte@123",
  "role": "USER",
  "sipExtension": 4005,
  "accessGroupId": 2,
  "businessUnitIds": [1, 3]
}
```

#### Response (201 Created):
```json
{
  "id": 48,
  "username": "joao.silva",
  "displayName": "João Silva",
  "role": "USER",
  "sipExtension": 4005,
  "isActive": true
}
```
