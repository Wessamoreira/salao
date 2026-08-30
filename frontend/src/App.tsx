import { useEffect, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErroDaApi, restaurarSessao } from './api/http';
import { Casca } from './layout/Casca';
import { Protegida } from './layout/Protegida';
import { Login } from './telas/Login';
import { SegundoFator } from './telas/SegundoFator';
import { EmBreve } from './telas/EmBreve';

const cliente = new QueryClient({
  defaultOptions: {
    queries: {
      // Sessão expirada não se resolve tentando de novo: só o refresh resolve, e o
      // cliente HTTP já cuidou disso antes de chegar aqui.
      retry: (tentativas, erro) =>
        !(erro instanceof ErroDaApi && erro.status === 401) && tentativas < 2,
      refetchOnWindowFocus: false,
    },
  },
});

export function App() {
  const [restaurando, definirRestaurando] = useState(true);

  // O access token vive em memória e some ao recarregar. No boot, troca-se o cookie
  // HttpOnly por um token novo — é o que faz F5 não derrubar a sessão sem guardar
  // credencial em lugar que um XSS alcance.
  useEffect(() => {
    restaurarSessao().finally(() => definirRestaurando(false));
  }, []);

  if (restaurando) return null;

  return (
    <QueryClientProvider client={cliente}>
      <BrowserRouter>
        <Routes>
          <Route path="/entrar" element={<Login />} />
          <Route path="/entrar/verificacao" element={<SegundoFator />} />
          <Route path="/seguranca/segundo-fator"
            element={<Protegida><SegundoFator /></Protegida>} />

          <Route element={<Protegida><Casca /></Protegida>}>
            <Route index element={<Navigate to="/agenda" replace />} />
            {/* As telas de negócio chegam nas rotinas das fases 1 a 3. A casca existe
                para que cada uma entre sem mexer em rota, sessão ou permissão. */}
            <Route path="/agenda" element={<EmBreve titulo="Agenda" rotina="RT-AGD-011" />} />
            <Route path="/comandas" element={<EmBreve titulo="Atendimento" rotina="RT-ATD-008" />} />
            <Route path="/clientes" element={<EmBreve titulo="Clientes" rotina="RT-CLI-001" />} />
            <Route path="/estoque" element={<EmBreve titulo="Estoque" rotina="RT-EST-001" />} />
            <Route path="/financeiro" element={<EmBreve titulo="Financeiro" rotina="RT-FIN-004" />} />
            <Route path="/config" element={<EmBreve titulo="Configurações" rotina="RT-IAM-007" />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
