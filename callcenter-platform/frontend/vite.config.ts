import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base '/callcenter/' — a SPA é servida sob esse prefixo pelo mesmo nginx do
// frontend Telecom (location /callcenter/), mesmo padrão de Insights/Agentes.
export default defineConfig({
  base: '/callcenter/',
  plugins: [react()],
})
