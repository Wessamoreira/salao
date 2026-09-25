/*
 * Prévia visual do cardápio, sem backend.
 *
 * Os componentes e o CSS são os reais; só a rede é falsa. Um cardápio pequeno e verdadeiro: a
 * coloração com pausa (que é o serviço que justifica o modelo de blocos), uma escova simples, um
 * serviço sem preço — o estado que a comanda recusa vender — e um fora do cardápio.
 *
 *   npm run previa:catalogo   →   http://localhost:5174/preview/catalogo.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Catalogo } from '../src/telas/catalogo/Catalogo';
import '../src/estilo/global.css';

const LAVATORIO = { id: 'r1', nome: 'Lavatório', quantidade: 2, ativo: true, versao: 0 };
const CADEIRA = { id: 'r2', nome: 'Cadeira de corte', quantidade: 4, ativo: true, versao: 0 };

const SERVICOS = [
  {
    id: 's1', nome: 'Coloração', descricao: 'raiz e comprimento', ativo: true, versao: 3,
    duracaoTotal: 100, minutosDoProfissional: 60, precoVigente: 180,
    etapas: [
      { tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: ['r2'] },
      { tipo: 'PAUSA', duracaoMinutos: 40, recursosExigidos: [] },
      { tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: ['r1'] },
    ],
  },
  {
    id: 's2', nome: 'Escova', descricao: null, ativo: true, versao: 1,
    duracaoTotal: 45, minutosDoProfissional: 45, precoVigente: 70,
    etapas: [{ tipo: 'ATIVO', duracaoMinutos: 45, recursosExigidos: [] }],
  },
  {
    id: 's3', nome: 'Hidratação', descricao: 'cadastrada ontem', ativo: true, versao: 0,
    duracaoTotal: 50, minutosDoProfissional: 30, precoVigente: null,
    etapas: [
      { tipo: 'ATIVO', duracaoMinutos: 15, recursosExigidos: ['r1'] },
      { tipo: 'PAUSA', duracaoMinutos: 20, recursosExigidos: [] },
      { tipo: 'ATIVO', duracaoMinutos: 15, recursosExigidos: [] },
    ],
  },
  {
    id: 's4', nome: 'Luzes com touca', descricao: null, ativo: false, versao: 7,
    duracaoTotal: 120, minutosDoProfissional: 80, precoVigente: 260,
    etapas: [
      { tipo: 'ATIVO', duracaoMinutos: 50, recursosExigidos: [] },
      { tipo: 'PAUSA', duracaoMinutos: 40, recursosExigidos: [] },
      { tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: ['r1'] },
    ],
  },
];

const PRECOS = [
  { id: 'p3', valor: 195, vigenteDe: '2026-12-01' },
  { id: 'p2', valor: 180, vigenteDe: '2026-06-01' },
  { id: 'p1', valor: 160, vigenteDe: '2025-11-15' },
];

/* Meio segundo de atraso: uma prévia que responde no mesmo quadro nunca mostra a espera. */
window.fetch = async (entrada: RequestInfo | URL) => {
  const url = String(entrada);
  await new Promise((seguir) => setTimeout(seguir, 500));
  let corpo: unknown = {};
  if (url.includes('/me/capabilities')) {
    corpo = { estabelecimento: { id: 'e1', nome: 'Studio Marilda',
      timezone: 'America/Sao_Paulo', moeda: 'BRL' },
      permissoes: ['config:manage'], grupos: [], menus: [] };
  } else if (url.includes('/precos')) {
    corpo = PRECOS;
  } else if (url.includes('/v1/recursos')) {
    corpo = [LAVATORIO, CADEIRA];
  } else if (url.includes('/v1/servicos')) {
    corpo = SERVICOS;
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)' }}>
        <Catalogo />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
