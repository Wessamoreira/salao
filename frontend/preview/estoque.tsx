/*
 * Prévia visual do depósito, sem backend.
 *
 * Quatro produtos que cobrem o que a tela existe para mostrar: um com estoque e lote vencendo,
 * um zerado e abaixo do mínimo, um de uso interno sem preço, e um desativado.
 *
 *   npm run previa:estoque   →   http://localhost:5174/preview/estoque.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { Contagem } from '../src/telas/estoque/Contagem';
import { Estoque } from '../src/telas/estoque/Estoque';
import '../src/estilo/global.css';

const PRODUTOS = [
  { id: 'p1', nome: 'Shampoo hidratante 300ml', descricao: null, finalidade: 'VENDA',
    unidade: 'UN', precoVenda: 68, codigoBarras: '7891234567890', ativo: true,
    vendavel: true, versao: 2 },
  { id: 'p2', nome: 'Tinta 7.1 louro médio acinzentado', descricao: null,
    finalidade: 'CONSUMO_INTERNO', unidade: 'ML', precoVenda: null, codigoBarras: null,
    ativo: true, vendavel: false, versao: 5 },
  { id: 'p3', nome: 'Água oxigenada 20 vol', descricao: null, finalidade: 'CONSUMO_INTERNO',
    unidade: 'L', precoVenda: null, codigoBarras: null, ativo: true, vendavel: false, versao: 1 },
  { id: 'p4', nome: 'Máscara de argila (descontinuada)', descricao: null, finalidade: 'VENDA',
    unidade: 'UN', precoVenda: 39, codigoBarras: null, ativo: false, vendavel: false, versao: 7 },
];

const SALDOS = [
  { produtoId: 'p1', nome: 'Shampoo hidratante 300ml', unidade: 'UN', quantidade: 12,
    custoMedio: 31.4, valorEmEstoque: 376.8, semEstoque: false },
  { produtoId: 'p2', nome: 'Tinta 7.1 louro médio acinzentado', unidade: 'ML', quantidade: 0,
    custoMedio: null, valorEmEstoque: 0, semEstoque: true },
  { produtoId: 'p3', nome: 'Água oxigenada 20 vol', unidade: 'L', quantidade: 4.5,
    custoMedio: 12, valorEmEstoque: 54, semEstoque: false },
  { produtoId: 'p4', nome: 'Máscara de argila (descontinuada)', unidade: 'UN', quantidade: 2,
    custoMedio: 18, valorEmEstoque: 36, semEstoque: false },
];

const ALERTAS = {
  diasDeAviso: 30,
  custoEmRisco: 188.4,
  abaixoDoMinimo: [
    { produtoId: 'p2', nome: 'Tinta 7.1 louro médio acinzentado', unidade: 'ML',
      quantidade: 0, minimo: 500, faltando: 500, zerado: true },
    { produtoId: 'p3', nome: 'Água oxigenada 20 vol', unidade: 'L',
      quantidade: 4.5, minimo: 10, faltando: 5.5, zerado: false },
  ],
  vencendo: [
    { loteId: 'l1', produtoId: 'p1', produto: 'Shampoo hidratante 300ml', unidade: 'UN', codigo: 'NF-4421',
      quantidade: 6, validade: '2026-10-08', diasRestantes: 12, custoEmRisco: 188.4,
      vencido: false },
  ],
};

const LOTES = [
  { id: 'l1', produtoId: 'p1', codigo: 'NF-4421', custoUnitario: 31.4,
    quantidade: 6, validade: '2026-10-08', recebidoEm: '2026-04-08' },
  { id: 'l2', produtoId: 'p1', codigo: 'NF-5502', custoUnitario: 31.4,
    quantidade: 6, validade: '2027-06-30', recebidoEm: '2026-09-12' },
];

const CONTAGEM = {
  id: 'c1', status: 'ABERTO', observacao: 'contagem do mês — só o depósito',
  criadoEm: new Date(Date.now() - 40 * 60000).toISOString(), encerradoEm: null,
  quantasDivergem: 1, saldoDasDivergencias: -3,
  itens: [{ loteId: 'l1', produtoId: 'p1', produto: 'Shampoo hidratante 300ml',
    loteCodigo: 'NF-4421', esperada: 9, contada: 6, divergencia: -3, bateu: false,
    observacao: null, contadoEm: new Date().toISOString() }],
};

window.fetch = async (entrada: RequestInfo | URL) => {
  const url = String(entrada);
  await new Promise((seguir) => setTimeout(seguir, 300));
  let corpo: unknown = {};
  if (url.includes('/me/capabilities')) {
    corpo = { usuarioId: 'u1', nome: 'Marilda', perfil: 'ADMIN',
      estabelecimento: { id: 'e1', nome: 'Studio Marilda',
        timezone: 'America/Sao_Paulo', moeda: 'BRL' },
      permissoes: ['estoque:read', 'estoque:write', 'produto:price:write'],
      grupos: [], menus: [] };
  } else if (url.includes('/v1/produtos/saldos')) {
    corpo = SALDOS;
  } else if (url.includes('/v1/produtos/alertas')) {
    corpo = ALERTAS;
  } else if (url.includes('/lotes')) {
    corpo = LOTES;
  } else if (url.includes('/v1/inventarios')) {
    corpo = CONTAGEM;
  } else if (url.includes('/v1/produtos')) {
    corpo = PRODUTOS;
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <MemoryRouter initialEntries={['/estoque']}>
      <QueryClientProvider client={new QueryClient()}>
        {/* As duas rotas, para a prévia cobrir o caminho inteiro: depósito → contagem. */}
        <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)' }}>
          <Routes>
            <Route path="/estoque" element={<Estoque />} />
            <Route path="/estoque/contagem" element={<Contagem />} />
          </Routes>
        </div>
      </QueryClientProvider>
    </MemoryRouter>
  </StrictMode>,
);
