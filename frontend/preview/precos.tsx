/*
 * Prévia visual dos preços, sem backend.
 *
 * Os componentes e o CSS são os reais; só a rede é falsa. Cinco sugestões, uma de cada motivo —
 * inclusive um produto sem preço de venda, que é o caso mais gritante de margem abaixo do mínimo —,
 * e a trilha com uma aplicação ajustada e uma recusa.
 *
 *   npm run previa:precos   →   http://localhost:5174/preview/precos.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Precos } from '../src/telas/precos/Precos';
import '../src/estilo/global.css';

const SUGESTOES = [
  { id: 's0', produtoId: 'p0', produto: 'Tônico capilar 120ml',
    motivo: 'QUEIMA_DE_VENCIMENTO', rotulo: 'Queima de vencimento',
    precoAtual: 139.9, custoMedio: 100, precoSugerido: 109.9,
    margemAtual: 39.9, margemSugerida: 9.9,
    racional: 'Lote vence em 5 dias, com 10 em estoque. Na última semana a queima vai até o custo médio de R$ 100,00; R$ 109,90 na régua.',
    explicacao: null },
  { id: 's1', produtoId: 'p1', produto: 'Máscara de reconstrução 500g',
    motivo: 'MARGEM_ABAIXO_DO_MINIMO', rotulo: 'Margem abaixo do mínimo',
    precoAtual: 38, custoMedio: 31.2, precoSugerido: 49.9,
    margemAtual: 21.79, margemSugerida: 59.94,
    racional: 'Custo médio R$ 31,20; preço R$ 38,00 dá 21,8% de margem, abaixo dos 30% mínimos.',
    explicacao: null },
  { id: 's2', produtoId: 'p2', produto: 'Óleo de argan 60ml',
    motivo: 'MARGEM_ABAIXO_DO_MINIMO', rotulo: 'Margem abaixo do mínimo',
    precoAtual: null, custoMedio: 22, precoSugerido: 29.9,
    margemAtual: null, margemSugerida: 35.91,
    racional: 'Sem preço de venda. Custo médio de R$ 22,00 com 30,0% de margem mínima dá R$ 29,90 na régua.',
    explicacao: null },
  { id: 's3', produtoId: 'p3', produto: 'Condicionador matizador 300ml',
    motivo: 'ENCALHADO', rotulo: 'Encalhado',
    precoAtual: 49.9, custoMedio: 10, precoSugerido: 19.9,
    margemAtual: 399, margemSugerida: 99,
    racional: '78 dias sem vender com 8 em estoque. Baixar até a margem mínima de 30,0% dá R$ 19,90 na régua.',
    explicacao: null },
  { id: 's4', produtoId: 'p4', produto: 'Escova térmica',
    motivo: 'PRECO_QUEBRADO', rotulo: 'Preço quebrado',
    precoAtual: 87.4, custoMedio: 40, precoSugerido: 89.9,
    margemAtual: 118.5, margemSugerida: 124.75,
    racional: 'Preço R$ 87,40 fora da régua de balcão; R$ 89,90 se lê de relance, e a margem sobe para 124,8%.',
    explicacao: null },
];

const PAINEL = { margemMinima: 30, geradaEm: '2026-09-20T14:02:00Z', itens: SUGESTOES };

const DECISOES = [
  { id: 'd1', produtoId: 'p9', produto: 'Shampoo hidratante 1L', decisao: 'APLICADA',
    motivo: 'MARGEM_ABAIXO_DO_MINIMO', precoAnterior: 42, custoMedio: 35, precoSugerido: 59.9,
    precoAplicado: 49.9, racional: 'Custo médio R$ 35,00; preço R$ 42,00 dá 20,0% de margem, abaixo dos 30% mínimos.',
    justificativa: null, quemDecidiu: 'Wesley', decididaEm: '2026-09-20T14:30:00Z' },
  { id: 'd2', produtoId: 'p8', produto: 'Ampola de queratina', decisao: 'RECUSADA',
    motivo: 'ENCALHADO', precoAnterior: 29.9, custoMedio: 12, precoSugerido: 19.9,
    precoAplicado: null, racional: '64 dias sem vender com 5 em estoque. Baixar até a margem mínima de 30,0% dá R$ 19,90 na régua.',
    justificativa: 'é sazonal, sai muito no inverno; não quero queimar o preço agora',
    quemDecidiu: 'Joana', decididaEm: '2026-09-19T17:10:00Z' },
];

window.fetch = async (entrada: RequestInfo | URL, init?: RequestInit) => {
  const url = String(entrada);
  let corpo: unknown;
  if (url.includes('/me/capabilities')) {
    corpo = { estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo',
      moeda: 'BRL' }, permissoes: ['produto:price:write', 'estoque:read'], grupos: [], menus: [] };
  } else if (url.includes('/decisoes-de-preco')) {
    corpo = { itens: DECISOES, proximoCursor: null, temMais: false };
  } else if (url.includes('/politica-de-preco')) {
    corpo = { margemMinima: 30 };
  } else if (url.includes('/aprovacao') || url.includes('/recusa')) {
    corpo = { ...PAINEL, itens: SUGESTOES.slice(1) };
  } else {
    corpo = PAINEL;
  }
  void init;
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)', padding: 'var(--e-6)' }}>
        <Precos />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
