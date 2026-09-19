/*
 * Prévia visual da importação do extrato Stone, sem backend.
 *
 * Os componentes e o CSS são os reais; só a rede é falsa. Escolha qualquer arquivo: a resposta é a de
 * uma Lista de vendas fictícia, com uma venda em Pix (ignorada) e uma data impossível (recusada). Trocar
 * a coluna do valor bruto para "VALOR LÍQUIDO" muda o que aparece ao lado — como no servidor.
 *
 *   npm run previa:conciliacao   →   http://localhost:5174/preview/conciliacao.html
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Conciliacao } from '../src/telas/conciliacao/Conciliacao';
import '../src/estilo/global.css';

const CAMPOS = [
  ['ID_NA_ADQUIRENTE', 'Stone ID', true], ['DATA', 'Data da venda', true],
  ['VALOR_BRUTO', 'Valor bruto', true], ['VALOR_LIQUIDO', 'Valor líquido', false],
  ['PRODUTO', 'Crédito ou débito', false], ['BANDEIRA', 'Bandeira', false],
  ['PARCELAS', 'Parcelas', false], ['NSU', 'NSU', false],
  ['AUTORIZACAO', 'Código de autorização', false], ['SITUACAO', 'Situação', false],
].map(([campo, rotulo, obrigatorio]) => ({ campo, rotulo, obrigatorio }));

const COLUNAS = ['STONE ID', 'DATA DA VENDA', 'VALOR BRUTO', 'VALOR LÍQUIDO', 'PRODUTO',
  'BANDEIRA', 'Nº DE PARCELAS', 'NSU', 'CÓDIGO DE AUTORIZAÇÃO', 'STATUS', 'CARTÃO'];

const venda = (linha: number, id: string, hora: string, bruto: number, liquido: number,
  produto: string, bandeira: string, parcelas: number) => ({
  linha, estado: 'LIDA', motivo: null, idNaAdquirente: id, capturadaEm: `2026-09-18T${hora}:00`,
  horaInformada: true, produto, bandeira, parcelas, valorBruto: bruto, valorLiquido: liquido,
  nsu: String(400000 + linha), autorizacao: `A${linha}B${linha}C`, situacao: 'APROVADA' });

function previa(corpo: FormData | null) {
  const pedido = corpo?.get('mapeamento');
  return (async () => {
    const mapeamento = pedido ? JSON.parse(await (pedido as Blob).text()) : null;
    const colunas = mapeamento?.colunas ?? {
      ID_NA_ADQUIRENTE: 'STONE ID', DATA: 'DATA DA VENDA', VALOR_BRUTO: 'VALOR BRUTO',
      VALOR_LIQUIDO: 'VALOR LÍQUIDO', PRODUTO: 'PRODUTO', BANDEIRA: 'BANDEIRA',
      PARCELAS: 'Nº DE PARCELAS', NSU: 'NSU', AUTORIZACAO: 'CÓDIGO DE AUTORIZAÇÃO', SITUACAO: 'STATUS' };
    const faltando = ['ID_NA_ADQUIRENTE', 'DATA', 'VALOR_BRUTO'].filter((c) => !colunas[c]);
    const liquidoNoBruto = colunas.VALOR_BRUTO === 'VALOR LÍQUIDO';
    const amostra = faltando.length ? [] : [
      venda(2, 'ST8F3A21', '09:14', 180, 175.14, 'CREDITO', 'VISA', 1),
      venda(3, 'ST8F3A77', '10:02', 1234.56, 1181.22, 'CREDITO', 'MASTERCARD', 3),
      { linha: 4, estado: 'IGNORADA', motivo: 'Pix não passa pela conciliação' },
      venda(5, 'ST8F3B02', '11:40', 95, 93.48, 'DEBITO', 'ELO', 1),
      { linha: 6, estado: 'RECUSADA', motivo: 'data não reconhecida: 31/02/2026 12:10' },
      venda(7, 'ST8F3B51', '14:25', 320, 306.4, 'CREDITO', 'VISA', 2),
    ].map((l) => liquidoNoBruto && 'valorLiquido' in l ? { ...l, valorBruto: l.valorLiquido } : l);
    return {
      campos: CAMPOS, colunas: COLUNAS, mapeamento: colunas,
      formato: mapeamento?.formato ?? 'BRASILEIRO', faltando, amostra,
      linhas: 128, lidas: faltando.length ? 0 : 121, ignoradas: faltando.length ? 0 : 5,
      recusadas: faltando.length ? 0 : 2, jaImportadoEm: null,
    };
  })();
}

const HISTORICO = [
  { id: 'i2', importadaEm: '2026-09-01T13:05:00Z', nomeDoArquivo: 'stone-agosto.csv',
    quemImportou: 'Joana', linhas: 412, novas: 398, canceladas: 3, repetidas: 0, ignoradas: 9,
    recusadas: 2 },
  { id: 'i1', importadaEm: '2026-08-02T12:40:00Z', nomeDoArquivo: 'stone-julho.csv',
    quemImportou: 'Joana', linhas: 377, novas: 370, canceladas: 1, repetidas: 0, ignoradas: 6,
    recusadas: 0 },
];

window.fetch = async (entrada: RequestInfo | URL, init?: RequestInit) => {
  const url = String(entrada);
  let corpo: unknown;
  if (url.includes('/me/capabilities')) {
    corpo = { estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo',
      moeda: 'BRL' }, permissoes: ['financeiro:reconcile'], grupos: [], menus: [] };
  } else if (url.endsWith('/previa')) {
    corpo = await previa(init?.body as FormData);
  } else if (init?.method === 'POST') {
    corpo = { id: 'i3', importadaEm: new Date().toISOString(), linhas: 128, novas: 118,
      canceladas: 2, repetidas: 1, ignoradas: 5, recusadas: 2,
      recusas: [{ linha: 6, motivo: 'data não reconhecida: 31/02/2026 12:10' },
        { linha: 91, motivo: 'Stone ID ST8F4410 já importado com valor bruto R$ 80,00; o arquivo diz R$ 85,00' }] };
  } else {
    corpo = { itens: HISTORICO, proximoCursor: null, temMais: false };
  }
  return new Response(JSON.stringify(corpo), {
    status: 200, headers: { 'Content-Type': 'application/json' } });
};

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <div style={{ minHeight: '100dvh', background: 'var(--papel-fundo)', padding: 'var(--e-6)' }}>
        <Conciliacao />
      </div>
    </QueryClientProvider>
  </StrictMode>,
);
