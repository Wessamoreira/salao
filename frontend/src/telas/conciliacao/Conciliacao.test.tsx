import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ErroDaApi } from '../../api/http';
import { Conciliacao } from './Conciliacao';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  ErroDaApi: class extends Error {},
}));

const CAMPOS = [
  { campo: 'ID_NA_ADQUIRENTE', rotulo: 'Stone ID', obrigatorio: true },
  { campo: 'DATA', rotulo: 'Data da venda', obrigatorio: true },
  { campo: 'VALOR_BRUTO', rotulo: 'Valor bruto', obrigatorio: true },
  { campo: 'BANDEIRA', rotulo: 'Bandeira', obrigatorio: false },
];

const LIDA = {
  linha: 2, estado: 'LIDA', motivo: null, idNaAdquirente: 'ST1',
  capturadaEm: '2026-09-18T14:32:00', horaInformada: true, produto: 'CREDITO',
  bandeira: 'VISA', parcelas: 3, valorBruto: 1234.56, valorLiquido: null, nsu: null,
  autorizacao: null, situacao: 'APROVADA',
};

const PREVIA = {
  campos: CAMPOS,
  colunas: ['STONE ID', 'DATA DA VENDA', 'VALOR BRUTO', 'BANDEIRA', 'TOTAL'],
  mapeamento: { ID_NA_ADQUIRENTE: 'STONE ID', DATA: 'DATA DA VENDA', VALOR_BRUTO: 'VALOR BRUTO',
    BANDEIRA: 'BANDEIRA' },
  formato: 'BRASILEIRO', faltando: [],
  amostra: [LIDA, { ...LIDA, linha: 3, estado: 'RECUSADA', motivo: 'data não reconhecida: 31/02/2026' }],
  linhas: 2, lidas: 1, ignoradas: 0, recusadas: 1, jaImportadoEm: null,
};

const RESULTADO = {
  id: 'i1', importadaEm: '2026-09-19T12:00:00Z', linhas: 2, novas: 1, canceladas: 0,
  repetidas: 0, ignoradas: 0, recusadas: 1,
  recusas: [{ linha: 3, motivo: 'data não reconhecida: 31/02/2026' }],
};

const arquivo = () => new File(['STONE ID;...'], 'vendas.csv', { type: 'text/csv' });

function montar({ previa = PREVIA as unknown, historico = [] as unknown[] } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (caminho === '/v1/me/capabilities') {
      return { estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho.endsWith('/previa')) {
      if (previa instanceof Error) throw previa;
      return previa;
    }
    if (caminho === '/v1/conciliacao/importacoes' && init?.method === 'POST') return RESULTADO;
    return { itens: historico, proximoCursor: null, temMais: false };
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={fila}><Conciliacao /></QueryClientProvider>);
}

const envios = (sufixo: string) => chamar.mock.calls
  .filter(([caminho, init]) => String(caminho).endsWith(sufixo) && init?.method === 'POST')
  .map(([, init]) => (init as RequestInit).body as FormData);

async function mapeamentoEnviado(corpo: FormData) {
  return JSON.parse(await (corpo.get('mapeamento') as Blob).text());
}

beforeEach(() => { chamar.mockReset(); });

describe('importar vendas da Stone', () => {
  it('diz de onde tirar o arquivo, e escolher só pede a prévia — sem mapeamento', async () => {
    montar();

    expect(screen.getByText(/Lista de vendas/)).toBeInTheDocument();
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    expect(await screen.findByText('vendas.csv')).toBeInTheDocument();
    const [corpo] = envios('/previa');
    expect(corpo.get('arquivo')).toBeInstanceOf(File);
    expect(corpo.get('mapeamento')).toBeNull();
  });

  it('mostra, ao lado de cada campo, o que foi lido da primeira venda', async () => {
    montar();
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    const campo = (await screen.findByLabelText(/Valor bruto/)).closest('li')!;
    expect(within(campo).getByText(/R\$\s1\.234,56/)).toBeInTheDocument();
    expect(within(screen.getByLabelText(/Data da venda/).closest('li')!)
      .getByText('18/09/2026 14:32')).toBeInTheDocument();
  });

  it('trocar a coluna ou o formato refaz a prévia com o mapeamento', async () => {
    montar();
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    await userEvent.selectOptions(await screen.findByLabelText(/Valor bruto/), 'TOTAL');
    await userEvent.click(screen.getByRole('button', { name: '1,234.56' }));

    const [, porColuna, porFormato] = envios('/previa');
    expect((await mapeamentoEnviado(porColuna)).colunas.VALOR_BRUTO).toBe('TOTAL');
    expect((await mapeamentoEnviado(porFormato)).formato).toBe('INTERNACIONAL');
  });

  it('obrigatório sem coluna: diz qual, e não deixa importar', async () => {
    montar({ previa: { ...PREVIA, mapeamento: { ID_NA_ADQUIRENTE: 'STONE ID' },
      faltando: ['DATA', 'VALOR_BRUTO'], amostra: [], lidas: 0, recusadas: 0 } });
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    expect(await screen.findAllByText('escolha a coluna')).toHaveLength(2);
    expect(screen.getByRole('button', { name: /Importar/ })).toBeDisabled();
  });

  it('a linha recusada aparece na amostra com o motivo', async () => {
    montar();
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    expect(await screen.findByText(/data não reconhecida: 31\/02\/2026/)).toBeInTheDocument();
  });

  it('importa com o arquivo e o mapeamento confirmado, e mostra as contas e as recusadas', async () => {
    montar();
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    await userEvent.click(await screen.findByRole('button', { name: 'Importar 1 venda' }));

    const [corpo] = envios('/importacoes');
    expect(corpo.get('arquivo')).toBeInstanceOf(File);
    expect((await mapeamentoEnviado(corpo)).colunas.ID_NA_ADQUIRENTE).toBe('STONE ID');
    expect(await screen.findByText('Importado')).toBeInTheDocument();
    expect(screen.getByText(/nova$/).closest('.cnc-total')).toHaveTextContent('1 nova');
    expect(screen.getByText('Linha 3')).toBeInTheDocument();
  });

  it('avisa quando o mesmo arquivo já foi importado', async () => {
    montar({ previa: { ...PREVIA, jaImportadoEm: '2026-09-18T20:00:00Z' } });
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    expect(await screen.findByText(/já foi importado em 18\/09\/2026/)).toBeInTheDocument();
  });

  it('planilha recusada: diz o que fazer e oferece escolher outro arquivo', async () => {
    montar({ previa: new ErroDaApi('é uma planilha do Excel (.xlsx) — exporte em CSV') });
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    expect(await screen.findByText(/exporte em CSV/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Escolher outro arquivo' }));
    expect(screen.getByLabelText(/Escolher arquivo CSV/)).toBeInTheDocument();
  });

  it('o histórico aparece quando já houve importação', async () => {
    montar({ historico: [{ id: 'i1', importadaEm: '2026-09-18T20:00:00Z',
      nomeDoArquivo: 'setembro.csv', quemImportou: 'Joana', linhas: 40, novas: 38,
      canceladas: 1, repetidas: 0, ignoradas: 1, recusadas: 0 }] });

    expect(await screen.findByText('setembro.csv')).toBeInTheDocument();
    expect(screen.getByText('Joana')).toBeInTheDocument();
  });
});
