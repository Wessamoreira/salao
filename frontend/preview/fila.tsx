/*
 * Prévia visual da fila de espera, sem backend.
 *
 * Os componentes e o CSS são os reais; só a rede é falsa. Duas vagas: a coloração cancelada da Ana,
 * com três candidatas — a primeira já chamada sem atender, a terceira exigindo motivo porque a
 * segunda ainda não foi chamada —, e a remarcação da Júlia, com um dia de série na fila.
 *
 *   npm run previa:fila   →   http://localhost:5174/preview/fila.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Fila } from '../src/telas/fila/Fila';
import '../src/estilo/global.css';

const horarios = (dia: string, de: number, ate: number, passo = 15) => {
  const lista: string[] = [];
  for (let m = de; m < ate; m += passo) {
    const h = String(Math.floor(m / 60) + 3).padStart(2, '0');
    lista.push(`${dia}T${h}:${String(m % 60).padStart(2, '0')}:00Z`);
  }
  return lista;
};

const VAGAS = [
  {
    id: 'v1', profissionalId: 'ana', profissional: 'Ana',
    inicio: '2026-09-24T17:00:00Z', fim: '2026-09-24T19:00:00Z', origem: 'CANCELAMENTO',
    candidatas: [
      { esperaId: 'e1', posicao: 1, clienteId: 'c1', cliente: 'Marilda', telefone: '5511987654321',
        servicoId: 's1', servico: 'Corte', aceitaDe: '2026-09-22', aceitaAte: '2026-09-26',
        horaDe: '13:00', horaAte: '18:00', naFilaDesde: '2026-09-12T13:20:00Z',
        horarios: horarios('2026-09-24', 14 * 60, 16 * 60),
        chamadas: [{ resultado: 'NAO_ATENDEU', registradaEm: '2026-09-22T13:42:00Z',
          quem: 'Júlia', motivoDoPulo: null }],
        exigeMotivo: false },
      { esperaId: 'e2', posicao: 2, clienteId: 'c2', cliente: 'Joana Prado',
        telefone: '5511955501234', servicoId: 's2', servico: 'Escova', aceitaDe: '2026-09-24',
        aceitaAte: '2026-09-24', horaDe: null, horaAte: null,
        naFilaDesde: '2026-09-15T18:05:00Z',
        horarios: horarios('2026-09-24', 14 * 60, 15 * 60 + 15), chamadas: [],
        exigeMotivo: false },
      { esperaId: 'e3', posicao: 3, clienteId: 'c3', cliente: 'Bia', telefone: null,
        servicoId: 's1', servico: 'Corte', aceitaDe: '2026-09-21', aceitaAte: '2026-09-30',
        horaDe: null, horaAte: null, naFilaDesde: '2026-09-19T12:00:00Z',
        horarios: horarios('2026-09-24', 14 * 60, 16 * 60), chamadas: [], exigeMotivo: true },
    ],
  },
  {
    id: 'v2', profissionalId: 'julia', profissional: 'Júlia',
    inicio: '2026-09-25T12:00:00Z', fim: '2026-09-25T13:00:00Z', origem: 'REAGENDAMENTO',
    candidatas: [
      { esperaId: 'e4', posicao: 1, clienteId: 'c4', cliente: 'Dona Zilda',
        telefone: '5511933332222', servicoId: 's2', servico: 'Escova',
        aceitaDe: '2026-09-25', aceitaAte: '2026-09-25', horaDe: null, horaAte: null,
        naFilaDesde: '2026-09-18T15:00:00Z', horarios: horarios('2026-09-25', 9 * 60, 9 * 60 + 30),
        chamadas: [], exigeMotivo: false },
    ],
  },
];

const ESPERAS = [
  { id: 'e1', clienteId: 'c1', cliente: 'Marilda', servicoId: 's1', servico: 'Corte',
    profissionalId: null, profissional: null, aceitaDe: '2026-09-22', aceitaAte: '2026-09-26',
    horaDe: '13:00', horaAte: '18:00', observacao: null, naFilaDesde: '2026-09-12T13:20:00Z',
    recorrenciaId: null },
  { id: 'e2', clienteId: 'c2', cliente: 'Joana Prado', servicoId: 's2', servico: 'Escova',
    profissionalId: 'ana', profissional: 'Ana', aceitaDe: '2026-09-24', aceitaAte: '2026-09-24',
    horaDe: null, horaAte: null, observacao: 'Prefere depois do almoço',
    naFilaDesde: '2026-09-15T18:05:00Z', recorrenciaId: null },
  { id: 'e4', clienteId: 'c4', cliente: 'Dona Zilda', servicoId: 's2', servico: 'Escova',
    profissionalId: 'julia', profissional: 'Júlia', aceitaDe: '2026-09-25',
    aceitaAte: '2026-09-25', horaDe: null, horaAte: null, observacao: null,
    naFilaDesde: '2026-09-18T15:00:00Z', recorrenciaId: 'r1' },
  { id: 'e3', clienteId: 'c3', cliente: 'Bia', servicoId: 's1', servico: 'Corte',
    profissionalId: null, profissional: null, aceitaDe: '2026-09-21', aceitaAte: '2026-09-30',
    horaDe: null, horaAte: null, observacao: null, naFilaDesde: '2026-09-19T12:00:00Z',
    recorrenciaId: null },
  { id: 'e5', clienteId: 'c5', cliente: 'Carla Menezes', servicoId: 's3',
    servico: 'Coloração', profissionalId: 'ana', profissional: 'Ana', aceitaDe: '2026-09-28',
    aceitaAte: '2026-10-03', horaDe: '09:00', horaAte: '12:00', observacao: null,
    naFilaDesde: '2026-09-21T14:40:00Z', recorrenciaId: null },
];

window.fetch = async (entrada: RequestInfo | URL, init?: RequestInit) => {
  const url = String(entrada);
  if (url.includes('/painel/eventos')) return new Response(null, { status: 204 });
  let corpo: unknown;
  if (url.includes('/me/capabilities')) {
    corpo = { estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo',
      moeda: 'BRL' }, permissoes: ['agenda:write:all', 'agenda:read:all'], grupos: [], menus: [] };
  } else if (url.includes('/vagas-abertas')) {
    corpo = init?.method === 'POST' ? { id: 'ch1' } : VAGAS;
  } else if (url.includes('/fila-de-espera')) {
    corpo = ESPERAS;
  } else if (url.includes('/servicos')) {
    corpo = [{ id: 's1', nome: 'Corte', ativo: true }, { id: 's2', nome: 'Escova', ativo: true },
      { id: 's3', nome: 'Coloração', ativo: true }];
  } else if (url.includes('/profissionais')) {
    corpo = [{ id: 'ana', nome: 'Ana Souza', apelido: 'Ana' },
      { id: 'julia', nome: 'Júlia Reis', apelido: 'Júlia' }];
  } else if (url.includes('/clientes')) {
    corpo = { interpretacao: 'NOME', aviso: null, itens: [
      { id: 'c9', nome: 'Renata Alves', apelido: null, telefone: '5511944441111', email: null,
        versao: 0 }] };
  } else {
    corpo = {};
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)', padding: 'var(--e-6)' }}>
        <Fila />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
