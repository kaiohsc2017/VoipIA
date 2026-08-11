import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base '/insights/' — a SPA é servida sob esse prefixo pelo mesmo nginx do
// frontend Telecom (ver frontend/nginx.conf, location /insights/), igual ao
// padrão da SPA de Agentes (base implícita '/agents/').
export default defineConfig({
  base: '/insights/',
  plugins: [react()],
})
