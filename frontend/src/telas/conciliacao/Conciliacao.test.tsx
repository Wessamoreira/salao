import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ErroDaApi } from '../../api/http';
import { Conciliacao } from './Conciliacao';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  // O dublê precisa ter a MESMA assinatura do real: a tela lê `message` para mostrar a
  // recusa do servidor, e um Error(status) faria o teste passar mostrando o número.
  ErroDaApi: class extends Error {
    status: number;
    codigo: string | null;
    constructor(status: number, codigo: string | null, mensagem: string) {
      super(mensagem);
      this.status = status;
      this.codigo = codigo;
    }
  },
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
  recusas: [{ linha: 3, motivo: 'data não reconhecida: 31/02/2026' }], casadas: 1, divergentes: 0,
};

const arquivo = () => new File(['STONE ID;...'], 'vendas.csv', { type: 'text/csv' });

const TOTAIS = (quantidade: number, valor: number) => ({ quantidade, valor });
const RESUMO = {
  de: '2026-09-01', ate: '2026-09-19', conciliadas: TOTAIS(110, 8420.5), divergentes: TOTAIS(2, 180),
  soNaMaquininha: TOTAIS(3, 240), soNoSistema: TOTAIS(1, 60), semExtrato: TOTAIS(0, 0),
  resolvidas: TOTAIS(0, 0), taxaAcimaDoContrato: TOTAIS(0, 0),
};
const DIVERGENTE = {
  id: 'c1', instante: '2026-09-18T13:05:00Z', vendaId: 'v1', idNaAdquirente: 'ST9',
  capturadaEm: '2026-09-18T13:05:00Z', horaInformada: true, valorVenda: 80, produto: 'CREDITO',
  bandeira: 'VISA', parcelas: 1, pagamentoId: 'p1', registradoEm: '2026-09-18T13:02:00Z',
  valorPagamento: 80, forma: 'DEBITO', criterio: 'VALOR_E_HORA', divergencias: ['FORMA'],
  possivelRefeita: false,
};
const SO_NA_MAQUININHA = {
  ...DIVERGENTE, id: 'v2', vendaId: 'v2', idNaAdquirente: 'ST7', valorVenda: 100,
  pagamentoId: null, registradoEm: null, valorPagamento: null, forma: null, criterio: null,
  divergencias: [], possivelRefeita: false,
};
const SO_NO_SISTEMA = {
  ...DIVERGENTE, id: 'p2', vendaId: null, idNaAdquirente: null, capturadaEm: null,
  valorVenda: null, produto: null, pagamentoId: 'p2', valorPagamento: 60, forma: 'CREDITO',
  criterio: null, divergencias: [], possivelRefeita: false,
};
const TAXAS = [
  { faixa: 'DEBITO', rotulo: 'Débito', percentual: 1.99, diasParaReceber: 1 },
  { faixa: 'CREDITO_A_VISTA', rotulo: 'Crédito à vista', percentual: 3.49, diasParaReceber: 30 },
  { faixa: 'CREDITO_2_6', rotulo: 'Crédito 2 a 6x', percentual: 4.29, diasParaReceber: 30 },
  { faixa: 'CREDITO_7_12', rotulo: 'Crédito 7 a 12x', percentual: 5.19, diasParaReceber: 30 },
];
const RECEBIVEIS = {
  de: '2026-09-20', ate: '2026-12-19', bruto: 1430, taxa: 28.46, liquido: 1401.54, semPrevisao: 0,
  dias: [{ dia: '2026-09-21', parcelas: 7, bruto: 820, taxa: 16.32, liquido: 803.68 },
    { dia: '2026-10-19', parcelas: 5, bruto: 610, taxa: 12.14, liquido: 597.86 }],
};

const RESOLVIDA = {
  id: 'r1', resolvidaEm: '2026-09-19T14:02:00Z', quemResolveu: 'Wesley', acao: 'CORRIGIR',
  motivo: 'recepção registrou débito; foi crédito', alvo: 'PAR',
  instante: '2026-09-18T13:05:00Z', valor: 80, idNaAdquirente: 'ST9', divergencias: ['FORMA'],
  formaDe: 'DEBITO', formaPara: 'CREDITO', lancado: null,
};

function montar({ previa = PREVIA as unknown, historico = [] as unknown[],
  resumo = RESUMO as unknown, divergente = DIVERGENTE as unknown,
  naMaquininha = SO_NA_MAQUININHA as unknown, resolvidas = [] as unknown[],
  aoResolver = null as unknown, taxas = TAXAS as unknown,
  recebiveis = RECEBIVEIS as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (caminho === '/v1/me/capabilities') {
      return { estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho.startsWith('/v1/conciliacao/resumo')) return resumo;
    if (caminho === '/v1/adquirente/taxas') {
      if (init?.method === 'PUT') return taxas;
      return taxas;
    }
    if (caminho.startsWith('/v1/recebiveis')) return recebiveis;
    if (caminho === '/v1/conciliacao/resolucoes' && init?.method === 'POST') {
      if (aoResolver instanceof Error) throw aoResolver;
      return { id: 'r9', acao: 'CORRIGIR', formaDe: null, formaPara: null, lancado: null,
        lancamentoId: null };
    }
    if (caminho.startsWith('/v1/conciliacao/resolucoes/')) return undefined;
    if (caminho.startsWith('/v1/conciliacao/resolucoes')) {
      return { itens: resolvidas, proximoCursor: null, temMais: false };
    }
    if (caminho.startsWith('/v1/conciliacao/itens')) {
      const itens = caminho.includes('recorte=DIVERGENTES') ? [divergente]
        : caminho.includes('recorte=SO_NA_MAQUININHA') ? [naMaquininha]
          : caminho.includes('recorte=SO_NO_SISTEMA') ? [SO_NO_SISTEMA] : [];
      return { itens, proximoCursor: null, temMais: false };
    }
    if (caminho === '/v1/conciliacao/casamentos') return { casadas: 2, divergentes: 0, canceladasDepois: 0 };
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
    expect(screen.getByText(/1 venda casou com pagamentos do sistema/)).toBeInTheDocument();
  });

  it('avisa quando o mesmo arquivo já foi importado', async () => {
    montar({ previa: { ...PREVIA, jaImportadoEm: '2026-09-18T20:00:00Z' } });
    await userEvent.upload(screen.getByLabelText(/Escolher arquivo CSV/), arquivo());

    expect(await screen.findByText(/já foi importado em 18\/09\/2026/)).toBeInTheDocument();
  });

  it('planilha recusada: diz o que fazer e oferece escolher outro arquivo', async () => {
    montar({ previa: new ErroDaApi(422, 'ER-FIN-ARQUIVO_INVALIDO',
      'é uma planilha do Excel (.xlsx) — exporte em CSV') });
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

describe('conferência do mês', () => {
  it('as contas do mês, e os divergentes abertos primeiro', async () => {
    montar();

    const divergentes = await screen.findByRole('button', { name: /divergentes/ });
    expect(divergentes).toHaveAttribute('aria-pressed', 'true');
    expect(divergentes).toHaveTextContent('2');
    expect(screen.getByRole('button', { name: /conciliadas/ })).toHaveTextContent('110');
    expect(await screen.findByText('Débito no sistema · Crédito na Stone')).toBeInTheDocument();
    expect(screen.getByText('Forma', { selector: '.cnc-divergencia' })).toBeInTheDocument();
  });

  it('escolher outra conta pede a lista dela ao servidor, e diz o que ela significa', async () => {
    montar();

    await userEvent.click(await screen.findByRole('button', { name: /só na maquininha/ }));

    expect(chamar.mock.calls.some(([caminho]) =>
      String(caminho).includes('recorte=SO_NA_MAQUININHA'))).toBe(true);
    expect(await screen.findByText(/comanda esquecida/)).toBeInTheDocument();
    expect(await screen.findByText('ST7')).toBeInTheDocument();
  });

  it('o mês escolhido vai como de/ate do mês inteiro', async () => {
    montar();
    const mes = await screen.findByLabelText('Mês');
    const anterior = (mes as HTMLSelectElement).options[1].value;

    await userEvent.selectOptions(mes, anterior);

    const [ano, numero] = anterior.split('-').map(Number);
    const ultimo = new Date(Date.UTC(ano, numero, 0)).getUTCDate();
    expect(chamar.mock.calls.some(([caminho]) => String(caminho)
      === `/v1/conciliacao/resumo?de=${anterior}-01&ate=${anterior}-${ultimo}`)).toBe(true);
  });

  it('"Conciliar agora" chama o casamento e diz quantas casaram', async () => {
    montar();

    await userEvent.click(await screen.findByRole('button', { name: 'Conciliar agora' }));

    expect(chamar).toHaveBeenCalledWith('/v1/conciliacao/casamentos', { method: 'POST' });
    expect(await screen.findByText('2 vendas casaram agora.')).toBeInTheDocument();
  });

  it('pagamento em dia sem extrato é avisado à parte — é falta de arquivo', async () => {
    montar({ resumo: { ...RESUMO, semExtrato: TOTAIS(4, 320) } });

    expect(await screen.findByText(/em dias sem extrato importado/)).toHaveTextContent(/4 pagamentos/);
  });
});

// ------------------------------------------------------------------ RT-FIN-011

const corpoEnviado = (sufixo: string) => JSON.parse(String((chamar.mock.calls
  .filter(([caminho, init]) => String(caminho).endsWith(sufixo) && init?.method === 'POST')
  .map(([, init]) => (init as RequestInit).body).at(-1))));

describe('resolver a divergência', () => {
  it('sem motivo escrito o botão não confirma; com motivo, vai o que a tela prometeu', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Usar a versão da Stone' }));

    expect(await screen.findByText(/passam a ser as da Stone/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Por quê?'), 'foi crédito');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(corpoEnviado('/v1/conciliacao/resolucoes')).toEqual({
      alvo: 'PAR', id: 'c1', acao: 'CORRIGIR', motivo: 'foi crédito',
    });
  });

  it('par só com cancelamento não oferece corrigir: não há versão certa para copiar', async () => {
    montar({ divergente: { ...DIVERGENTE, divergencias: ['CANCELADA_NA_ADQUIRENTE'] } });

    expect(await screen.findByRole('button', { name: 'Aceitar como está' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Usar a versão da Stone' })).not.toBeInTheDocument();
  });

  it('venda que pode ser a refeita avisa que o valor já pode estar no caixa', async () => {
    montar({ naMaquininha: { ...SO_NA_MAQUININHA, possivelRefeita: true } });
    await userEvent.click(await screen.findByRole('button', { name: /só na maquininha/ }));

    await userEvent.click(await screen.findByRole('button', { name: 'Lançar no caixa' }));

    expect(await screen.findByText(/lançar de novo conta duas vezes/))
      .toHaveTextContent(/venda cancelada de R\$\s?100,00 neste dia/);
  });

  it('pagamento sem venda na maquininha manda a forma verdadeira', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: /só no sistema/ }));
    await userEvent.click(await screen.findByRole('button', { name: 'Corrigir a forma' }));

    await userEvent.selectOptions(screen.getByLabelText('Foi, na verdade'), 'PIX');
    await userEvent.type(screen.getByLabelText('Por quê?'), 'foi Pix');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(corpoEnviado('/v1/conciliacao/resolucoes')).toEqual({
      alvo: 'PAGAMENTO', id: 'p2', acao: 'CORRIGIR', motivo: 'foi Pix', forma: 'PIX',
    });
  });

  it('a recusa do servidor aparece no painel, e a decisão continua aberta', async () => {
    montar({ aoResolver: new ErroDaApi(409, 'ER-FIN-JA_RESOLVIDA', 'esta linha já foi resolvida') });
    await userEvent.click(await screen.findByRole('button', { name: 'Aceitar como está' }));
    await userEvent.type(screen.getByLabelText('Por quê?'), 'aceito assim');

    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('esta linha já foi resolvida');
    expect(screen.getByLabelText('Por quê?')).toHaveValue('aceito assim');
  });

  it('a trilha diz quem decidiu, o que mudou e o motivo — e oferece reabrir', async () => {
    montar({ resumo: { ...RESUMO, resolvidas: TOTAIS(1, 80) }, resolvidas: [RESOLVIDA] });

    await userEvent.click(await screen.findByRole('button', { name: /resolvidas à mão/ }));

    expect(await screen.findByText('recepção registrou débito; foi crédito')).toBeInTheDocument();
    expect(screen.getByText(/Wesley/)).toBeInTheDocument();
    expect(screen.getByText('Débito → Crédito')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Reabrir' }));
    await userEvent.type(screen.getByLabelText('Por quê?'), 'era débito mesmo');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(corpoEnviado('/v1/conciliacao/resolucoes/r1/reabertura'))
      .toEqual({ motivo: 'era débito mesmo' });
  });
});


// ------------------------------------------------------------------ RT-FIN-012

describe('taxa e recebíveis', () => {
  it('o total que importa é o que cai na conta, com bruto e taxa ao lado', async () => {
    montar();

    expect(await screen.findByText('cai na conta')).toBeInTheDocument();
    expect(screen.getByText(/1\.401,54/)).toBeInTheDocument();
    expect(screen.getByText('retido pela Stone')).toBeInTheDocument();
    expect(screen.getByText('seg, 21/09')).toBeInTheDocument();
  });

  it('taxa acima do contrato vira aviso na conferência, com a diferença', async () => {
    montar({ resumo: { ...RESUMO, taxaAcimaDoContrato: TOTAIS(4, 18.4) } });

    expect(await screen.findByText(/reteve mais do que o contratado/))
      .toHaveTextContent(/4 vendas deste mês — R\$\s?18,40 a mais/);
  });

  it('venda sem previsão é avisada, e o aviso diz o que fazer', async () => {
    montar({ recebiveis: { ...RECEBIVEIS, semPrevisao: 3 } });

    expect(await screen.findByText(/ficaram sem previsão/))
      .toHaveTextContent(/3 vendas recentes/);
  });

  it('as taxas aparecem com vírgula, e salvar manda as quatro faixas', async () => {
    montar();

    const debito = await screen.findByLabelText('Taxa de Débito');
    expect(debito).toHaveValue('1,99');

    await userEvent.clear(debito);
    await userEvent.type(debito, '1,49');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar taxas' }));

    const enviado = JSON.parse(String((chamar.mock.calls
      .filter(([caminho, init]) => caminho === '/v1/adquirente/taxas' && init?.method === 'PUT')
      .map(([, init]) => (init as RequestInit).body).at(-1))));
    expect(enviado.faixas).toHaveLength(4);
    expect(enviado.faixas[0]).toEqual({ faixa: 'DEBITO', percentual: 1.49, diasParaReceber: 1 });
  });

  it('faixa em branco não salva: o contrato é um documento só', async () => {
    montar({ taxas: [...TAXAS.slice(0, 3),
      { faixa: 'CREDITO_7_12', rotulo: 'Crédito 7 a 12x', percentual: null, diasParaReceber: null }] });

    expect(await screen.findByRole('button', { name: 'Salvar taxas' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Taxa de Crédito 7 a 12x'), '5,19');
    await userEvent.type(screen.getByLabelText('Prazo de Crédito 7 a 12x, em dias'), '30');

    expect(screen.getByRole('button', { name: 'Salvar taxas' })).toBeEnabled();
  });
});
