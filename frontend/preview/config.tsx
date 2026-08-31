/* Prévia visual da tela de configuração. Ver preview/clientes.tsx para o porquê. */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Configuracao } from '../src/telas/config/Configuracao';
import '../src/estilo/global.css';

let atual = {
  id: 'e1', nome: 'Studio Marilda', fuso: 'America/Sao_Paulo', moeda: 'BRL',
  baseComissao: 'BRUTO', descontoAfetaComissao: false,
  periodicidadeDeFechamento: 'MENSAL', paisPadrao: '55', dddPadrao: null as string | null,
  ativo: true,
};

window.fetch = async (_entrada: RequestInfo | URL, init?: RequestInit) => {
  if (init?.method === 'PUT') {
    const enviado = JSON.parse(String(init.body));
    atual = { ...atual, ...enviado, fuso: enviado.fusoIana };
  }
  return new Response(JSON.stringify(atual), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)',
        padding: 'var(--e-8)' }}>
        <Configuracao />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
