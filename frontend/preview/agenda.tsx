/*
 * Prévia visual da grade, sem backend.
 *
 * A tela de clientes ensinou que três violações do design system passaram por todos os
 * testes e só apareceram no navegador — serifa em texto operacional, --tinta-tenue em
 * dado essencial, vermelho numa ação que não é perigo. Grade é densa e cheia de
 * sobreposição: é onde esse tipo de erro mais se esconde.
 *
 *   npm run previa:agenda   →   http://localhost:5174/preview/agenda.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { Agenda } from '../src/telas/agenda/Agenda';
import '../src/estilo/global.css';

import { RESPOSTA_DA_AGENDA as RESPOSTA, VAGAS } from './dados-da-agenda';


window.fetch = (async (entrada: RequestInfo | URL) => {
  const url = String(entrada);
  const corpo = url.includes('/me/capabilities')
    ? { estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo',
        moeda: 'BRL', periodicidadeDeFechamento: 'MENSAL' },
        // RT-AGD-013 — com a permissão de escrever, a prévia mostra o gesto.
        permissoes: ['agenda:read:all', 'agenda:write:all'], grupos: [], menus: [],
        flags: { podeAgendarParaOutros: true }, limites: {} }
    : url.includes('/vagas-abertas') ? VAGAS : RESPOSTA;
  return {
    ok: true, status: 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => corpo,
  };
}) as unknown as typeof fetch;

const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={cliente}>
      {/* O aviso de vaga tem um link: sem roteador, ele nem renderiza. */}
      <MemoryRouter>
        <div style={{ height: '100vh', padding: 'var(--e-4)', background: 'var(--papel-fundo)' }}>
          <Agenda />
        </div>
      </MemoryRouter>
    </QueryClientProvider>
  </StrictMode>,
);
