/* Prévia do painel do balcão. Ver preview/agenda.tsx para o porquê da prévia existir. */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Painel } from '../src/telas/agenda/Painel';
import '../src/estilo/global.css';

const HOJE = new Intl.DateTimeFormat('en-CA').format(new Date());

function as(hora: number, minuto = 0): string {
  const [ano, mes, dia] = HOJE.split('-').map(Number);
  return new Date(Date.UTC(ano, mes - 1, dia, hora + 3, minuto)).toISOString();
}

const RESPOSTA = {
  estabelecimento: 'Studio Marilda',
  fuso: 'America/Sao_Paulo',
  atualizadoEm: new Date().toISOString(),
  restantes: 4,
  linhas: [
    { inicio: as(10), cliente: 'Marilda S.', profissional: 'Ana', situacao: 'EM_ATENDIMENTO' },
    { inicio: as(11, 30), cliente: 'Josefa A.', profissional: 'Ana', situacao: 'CONFIRMADO' },
    { inicio: as(14), cliente: 'Vera L.', profissional: 'Júlia', situacao: 'CONFIRMADO' },
    { inicio: as(16, 30), cliente: 'Cleide R.', profissional: 'Nice', situacao: 'SOLICITADO' },
  ],
};

window.fetch = (async () => ({
  ok: true, status: 200, body: null,
  headers: new Headers({ 'content-type': 'application/json' }),
  json: async () => RESPOSTA,
})) as unknown as typeof fetch;

const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={cliente}><Painel /></QueryClientProvider>
  </StrictMode>,
);
