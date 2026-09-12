/*
 * Prévia visual da tela de fechamento, sem backend.
 *
 * A tela de clientes ensinou que três violações do design system passaram por todos os
 * testes e só apareceram no navegador. Esta é a tela com mais dinheiro na cara: total,
 * desconto, restante e o alerta de comanda esquecida convivem no mesmo campo de visão,
 * e é exatamente onde hierarquia errada se esconde.
 *
 *   npm run previa:comandas   →   http://localhost:5174/preview/comandas.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Comandas } from '../src/telas/comandas/Comandas';
import '../src/estilo/global.css';

const dinheiro = (valor: number) => ({ valor });
const horasAtras = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString();

const MARIA = {
  id: 'k1', clienteId: 'c1', numero: 128, status: 'ABERTA', origem: 'AGENDAMENTO',
  abertaEm: horasAtras(4.5), abertaPor: null, fechadaEm: null,
  valorBruto: dinheiro(215), valorDesconto: dinheiro(21.5),
  valorLiquido: dinheiro(193.5), versao: 6,
};

/** Um dia de verdade: serviço com assistente, produto, desconto e split começado. */
const ITENS = [
  {
    id: 'i1', tipo: 'SERVICO', servicoId: 's1', produtoId: null, agendamentoId: 'a1',
    descricao: 'Coloração', quantidade: 1, precoUnitario: dinheiro(170),
    desconto: dinheiro(17), valorTotal: dinheiro(153),
    executantes: [
      { id: 'e1', profissionalId: 'pr1', papel: 'PRINCIPAL', percentualComissao: 40,
        origem: 'PROFISSIONAL', baseCalculo: dinheiro(170), valorComissao: dinheiro(68) },
      { id: 'e2', profissionalId: 'pr2', papel: 'ASSISTENTE', percentualComissao: 10,
        origem: 'INFORMADA_NO_ITEM', baseCalculo: dinheiro(170),
        valorComissao: dinheiro(17) },
    ],
  },
  {
    id: 'i2', tipo: 'PRODUTO', servicoId: null, produtoId: 'p1', agendamentoId: null,
    descricao: 'Shampoo pós-coloração', quantidade: 1, precoUnitario: dinheiro(45),
    desconto: dinheiro(4.5), valorTotal: dinheiro(40.5), executantes: [],
  },
];

const ABERTAS = [
  { comanda: MARIA, clienteNome: 'Marilda Souza' },
  {
    comanda: {
      ...MARIA, id: 'k2', numero: 129, abertaEm: horasAtras(0.4),
      valorBruto: dinheiro(80), valorDesconto: dinheiro(0), valorLiquido: dinheiro(80),
    },
    clienteNome: 'Josefa Antônia de Andrade',
  },
  {
    comanda: {
      ...MARIA, id: 'k3', numero: 130, abertaEm: horasAtras(0.1),
      valorBruto: dinheiro(0), valorDesconto: dinheiro(0), valorLiquido: dinheiro(0),
    },
    clienteNome: 'Cleide R.',
  },
];

const CONTA = {
  comanda: MARIA,
  pagamentos: [
    { id: 'g1', comandaId: 'k1', forma: 'CREDITO', valor: dinheiro(100), bandeira: 'VISA',
      parcelas: 2, nsu: '004512', autorizacao: 'A91X', taxaPrevista: null,
      previsaoRecebimento: null, statusConciliacao: 'PENDENTE', criadoEm: horasAtras(0.1) },
  ],
  totalPago: dinheiro(100),
  restante: dinheiro(93.5),
};

const RESPOSTAS: Record<string, unknown> = {
  '/api/v1/comandas/abertas': ABERTAS,
  '/api/v1/comandas/k1/conta': CONTA,
  '/api/v1/comandas/k1': { comanda: MARIA, itens: ITENS },
  '/api/v1/servicos': [
    { id: 's1', nome: 'Coloração', ativo: true },
    { id: 's2', nome: 'Escova', ativo: true },
  ],
  '/api/v1/profissionais': [
    { id: 'pr1', nome: 'Ana Ribeiro', apelido: 'Ana' },
    { id: 'pr2', nome: 'Berenice do Carmo', apelido: 'Nice' },
  ],
  '/api/v1/produtos?somenteVendaveis=true': [
    { id: 'p1', nome: 'Shampoo pós-coloração', precoVenda: 45,
      codigoBarras: '7891234567895', vendavel: true },
  ],
};

window.fetch = (async (url: string) => {
  const caminho = new URL(url, 'http://local').pathname
    + (new URL(url, 'http://local').search ?? '');
  const corpo = RESPOSTAS[caminho] ?? RESPOSTAS[caminho.split('?')[0]] ?? null;
  return {
    ok: corpo !== null, status: corpo !== null ? 200 : 404,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => corpo,
  };
}) as unknown as typeof fetch;

const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={cliente}>
      <div style={{ height: '100vh', padding: 'var(--e-4)', background: 'var(--papel-fundo)' }}>
        <Comandas />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
