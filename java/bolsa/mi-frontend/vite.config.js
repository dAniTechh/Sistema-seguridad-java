import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/login': 'http://localhost:8080',
      '/balance': 'http://localhost:8080',
      '/buy': 'http://localhost:8080',
    }
  }
})