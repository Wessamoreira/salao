/*
 * Prévia visual do financeiro, sem backend.
 *
 * Três pessoas que cobrem o que a tela existe para mostrar: uma com saldo cheio, uma que deve ao
 * salão por ter adiantado mais do que ganhou, e uma que zerou.
 *
 *   npm run previa:financeiro   →   http://localhost:5174/preview/financeiro.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { Financeiro } from '../src/telas/financeiro/Financeiro';
import '../src/estilo/global.css';

const ANA = 'p1';
const JULIA = 'p2';
const NICE = 'p3';

const PREVIA = {
  de: '2026-08-01', ate: '2026-08-31',
  totalARepassar: 4312.8, quantosRecebem: 2, versao: 'hash-de-agosto',
  linhas: [
    { profissionalId: ANA, profissionalNome: 'Ana Ribeiro', saldoAnterior: -120,
      movimentoDoPeriodo: 2740.4, saldoFinal: 2620.4, aRepassar: 2620.4 },
    { profissionalId: JULIA, profissionalNome: 'Júlia Menezes', saldoAnterior: 0,
      movimentoDoPeriodo: 1692.4, saldoFinal: 1692.4, aRepassar: 1692.4 },
    { profissionalId: NICE, profissionalNome: 'Nice Fernandes', saldoAnterior: -400,
      movimentoDoPeriodo: 310, saldoFinal: -90, aRepassar: 0 },
  ],
};

const EXTRATOS: Record<string, unknown> = {
  [ANA]: {
    profissionalId: ANA, nome: 'Ana Ribeiro', de: '2026-08-01', ate: '2026-08-31',
    saldoAnterior: -120, aReceber: 2740.4, saldoFinal: 2620.4,
    totais: [
      { tipo: 'COMISSAO', valor: 3200.4 },
      { tipo: 'VALE', valor: -500 },
      { tipo: 'AJUSTE', valor: 40 },
    ],
    linhas: [
      { data: '2026-08-03', tipo: 'COMISSAO', descricao: 'Coloração — 30% (regra do serviço)',
        valor: 60, origemTipo: 'COMANDA', origemId: 'c1', lancamentoId: 'l1' },
      { data: '2026-08-07', tipo: 'COMISSAO', descricao: 'Escova — 30% (regra do serviço)',
        valor: 21, origemTipo: 'COMANDA', origemId: 'c2', lancamentoId: 'l2' },
      { data: '2026-08-12', tipo: 'VALE', descricao: 'Vale em dinheiro — adiantamento do dia 10',
        valor: -500, origemTipo: 'VALE', origemId: 'v1', lancamentoId: 'l3' },
      { data: '2026-08-20', tipo: 'AJUSTE', descricao: 'Ajuste — comissão apurada a menos na 1420',
        valor: 40, origemTipo: 'MANUAL', origemId: 'a1', lancamentoId: 'l4' },
      { data: '2026-08-28', tipo: 'COMISSAO', descricao: 'Coloração — 35% (regra da pessoa)',
        valor: 3119.4, origemTipo: 'COMANDA', origemId: 'c3', lancamentoId: 'l5' },
    ],
  },
};

/** Quem não tem linha no período: a tela precisa dizer isso sem parecer defeito. */
const VAZIO = {
  profissionalId: JULIA, nome: 'Júlia Menezes', de: '2026-08-01', ate: '2026-08-31',
  saldoAnterior: 0, aReceber: 0, saldoFinal: 0, totais: [], linhas: [],
};

const VALES = [
  { id: 'v1', profissionalId: ANA, profissionalNome: 'Ana Ribeiro', valor: 500,
    forma: 'DINHEIRO', dataCompetencia: '2026-08-12', motivo: 'adiantamento do dia 10',
    cancelado: false },
  { id: 'v2', profissionalId: NICE, profissionalNome: 'Nice Fernandes', valor: 400,
    forma: 'PIX', dataCompetencia: '2026-08-05', motivo: null, cancelado: false },
  { id: 'v3', profissionalId: JULIA, profissionalNome: 'Júlia Menezes', valor: 150,
    forma: 'DINHEIRO', dataCompetencia: '2026-08-02', motivo: 'não chegou a sair',
    cancelado: true },
];

window.fetch = async (entrada: RequestInfo | URL) => {
  const url = String(entrada);
  await new Promise((seguir) => setTimeout(seguir, 250));
  let corpo: unknown = {};
  if (url.includes('/me/capabilities')) {
    corpo = { usuarioId: 'u1', nome: 'Marilda', perfil: 'ADMIN',
      estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo',
        moeda: 'BRL', periodicidadeDeFechamento: 'MENSAL' },
      permissoes: ['financeiro:read:all', 'financeiro:advance', 'financeiro:close'],
      grupos: [], menus: [] };
  } else if (url.includes('/v1/fechamentos/previa')) {
    corpo = PREVIA;
  } else if (url.includes('/v1/fechamentos')) {
    corpo = [];
  } else if (url.includes('/v1/extratos')) {
    // `/api/v1/extratos/<id>?de=…` — o id é o quinto pedaço, não o quarto.
    corpo = EXTRATOS[url.split('/')[4].split('?')[0]] ?? VAZIO;
  } else if (url.includes('/v1/vales')) {
    corpo = VALES;
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MemoryRouter initialEntries={['/financeiro']}>
      <QueryClientProvider client={new QueryClient()}>
        <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)' }}>
          <Financeiro />
        </div>
      </QueryClientProvider>
    </MemoryRouter>
  </StrictMode>,
);
