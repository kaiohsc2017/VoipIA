import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base '/agents/' — a SPA é servida sob esse prefixo pelo mesmo nginx do
// frontend Telecom (ver frontend/nginx.conf, location /agents/), mesmo
// padrão da SPA de Insights (base '/insights/').
export default defineConfig({
  base: '/agents/',
  plugins: [react()],
})
