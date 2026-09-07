import { useEffect, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErroDaApi, restaurarSessao } from './api/http';
import { Casca } from './layout/Casca';
import { Protegida } from './layout/Protegida';
import { Login } from './telas/Login';
import { SegundoFator } from './telas/SegundoFator';
import { Agenda } from './telas/agenda/Agenda';
import { Painel } from './telas/agenda/Painel';
import { Clientes } from './telas/clientes/Clientes';
import { Comandas } from './telas/comandas/Comandas';
import { Configuracao } from './telas/config/Configuracao';
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
            <Route path="/agenda" element={<Agenda />} />
            <Route path="/comandas" element={<Comandas />} />
            <Route path="/clientes" element={<Clientes />} />
            <Route path="/estoque" element={<EmBreve titulo="Estoque" rotina="RT-EST-001" />} />
            <Route path="/financeiro" element={<EmBreve titulo="Financeiro" rotina="RT-FIN-004" />} />
            <Route path="/config" element={<Configuracao />} />
          </Route>

          {/*
              O painel fica FORA da casca: ele roda numa tela pendurada na parede, sem
              menu, sem navegação e sem nada em que alguém possa clicar por engano.
            */}
          <Route path="/painel" element={<Protegida><Painel /></Protegida>} />

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
