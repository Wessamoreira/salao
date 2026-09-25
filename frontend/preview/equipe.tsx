/*
 * Prévia visual da equipe, sem backend.
 *
 * Três pessoas que cobrem os casos que a tela existe para mostrar: uma parceira com jornada
 * cheia e almoço, uma empregada que só faz alguns serviços, e uma parceira SEM contrato
 * assinado — que é a pendência legal que some do radar sem um lembrete.
 *
 *   npm run previa:equipe   →   http://localhost:5174/preview/equipe.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { Equipe } from '../src/telas/equipe/Equipe';
import '../src/estilo/global.css';

const PESSOAS = [
  { id: 'p1', nome: 'Ana Ribeiro', apelido: 'Ana', documento: '52998224725',
    vinculo: 'EMPREGADO', telefone: '5511987654321', email: 'ana@salao.test',
    comissaoPercentual: 40, contratoInicio: '2026-01-10', contratoFim: null,
    contratoAssinadoEm: '2026-01-08', ativo: true, versao: 2 },
  { id: 'p2', nome: 'Júlia Menezes', apelido: null, documento: '11222333000181',
    vinculo: 'PARCEIRO', telefone: '5511955554444', email: null,
    comissaoPercentual: 55, contratoInicio: '2026-03-01', contratoFim: null,
    contratoAssinadoEm: null, ativo: true, versao: 1 },
  { id: 'p3', nome: 'Nice Fernandes', apelido: 'Nice', documento: '39053344705',
    vinculo: 'AUTONOMO', telefone: null, email: null, comissaoPercentual: null,
    contratoInicio: null, contratoFim: null, contratoAssinadoEm: null, ativo: false, versao: 4 },
];

const JORNADAS: Record<string, unknown> = {
  p1: { '2': [{ inicio: '09:00', fim: '13:00' }, { inicio: '14:00', fim: '18:00' }],
        '3': [{ inicio: '09:00', fim: '13:00' }, { inicio: '14:00', fim: '18:00' }],
        '4': [{ inicio: '09:00', fim: '13:00' }, { inicio: '14:00', fim: '18:00' }],
        '5': [{ inicio: '09:00', fim: '13:00' }, { inicio: '14:00', fim: '18:00' }],
        '6': [{ inicio: '09:00', fim: '16:00' }] },
  p2: { '3': [{ inicio: '12:00', fim: '20:00' }], '5': [{ inicio: '12:00', fim: '20:00' }] },
  p3: {},
};

const HABILIDADES: Record<string, unknown> = {
  p1: { executaTodos: true, servicos: [], duracoes: {} },
  p2: { executaTodos: false, servicos: ['s1'], duracoes: { s1: 120 } },
  p3: { executaTodos: true, servicos: [], duracoes: {} },
};

const SERVICOS = [
  { id: 's1', nome: 'Coloração', descricao: null, ativo: true, versao: 1,
    duracaoTotal: 100, minutosDoProfissional: 60, precoVigente: 180, etapas: [] },
  { id: 's2', nome: 'Escova', descricao: null, ativo: true, versao: 1,
    duracaoTotal: 45, minutosDoProfissional: 45, precoVigente: 70, etapas: [] },
];

const BLOQUEIOS = [
  { id: 'b1', profissionalId: 'p1', motivo: 'FERIAS', observacao: null,
    dataInicio: '2026-10-05', dataFim: '2026-10-19', horaInicio: null, horaFim: null },
  { id: 'b2', profissionalId: null, motivo: 'FERIADO', observacao: 'Natal',
    dataInicio: '2026-12-25', dataFim: '2026-12-25', horaInicio: null, horaFim: null },
];

window.fetch = async (entrada: RequestInfo | URL) => {
  const url = String(entrada);
  await new Promise((seguir) => setTimeout(seguir, 400));
  let corpo: unknown = {};
  if (url.includes('/me/capabilities')) {
    corpo = { estabelecimento: { id: 'e1', nome: 'Studio Marilda',
      timezone: 'America/Sao_Paulo', moeda: 'BRL' },
      permissoes: ['usuario:manage'], grupos: [], menus: [] };
  } else if (url.includes('/jornada')) {
    corpo = JORNADAS[url.split('/')[4]] ?? {};
  } else if (url.includes('/habilidades')) {
    corpo = HABILIDADES[url.split('/')[4]] ?? { executaTodos: true, servicos: [], duracoes: {} };
  } else if (url.includes('/v1/bloqueios-de-jornada')) {
    corpo = BLOQUEIOS;
  } else if (url.includes('/v1/profissionais')) {
    corpo = PESSOAS;
  } else if (url.includes('/v1/servicos')) {
    corpo = SERVICOS;
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MemoryRouter initialEntries={['/equipe']}>
      <QueryClientProvider client={new QueryClient()}>
        <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)' }}>
          <Equipe />
        </div>
      </QueryClientProvider>
    </MemoryRouter>
  </StrictMode>,
);
