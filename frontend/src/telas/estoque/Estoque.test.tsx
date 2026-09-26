import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Estoque } from './Estoque';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
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

const SHAMPOO = {
  id: 'p1', nome: 'Shampoo hidratante', descricao: null, finalidade: 'VENDA',
  unidade: 'UN', precoVenda: 45, codigoBarras: '7891234567890', ativo: true,
  vendavel: true, versao: 1,
};

const TINTA = {
  id: 'p2', nome: 'Tinta 7.1', descricao: null, finalidade: 'CONSUMO_INTERNO',
  unidade: 'ML', precoVenda: null, codigoBarras: null, ativo: true, vendavel: false, versao: 3,
};

const SALDOS = [
  { produtoId: 'p1', nome: 'Shampoo hidratante', unidade: 'UN', quantidade: 12,
    custoMedio: 20.5, valorEmEstoque: 246, semEstoque: false },
  { produtoId: 'p2', nome: 'Tinta 7.1', unidade: 'ML', quantidade: 0,
    custoMedio: null, valorEmEstoque: 0, semEstoque: true },
];

const LOTES = [
  { id: 'l1', produtoId: 'p1', codigo: 'NF-4421', custoUnitario: 20,
    quantidade: 12, validade: '2027-03-01', recebidoEm: '2026-09-01' },
];

const SEM_ALERTA = { diasDeAviso: 30, custoEmRisco: 0, abaixoDoMinimo: [], vencendo: [] };

const COM_ALERTA = {
  diasDeAviso: 30,
  custoEmRisco: 180,
  abaixoDoMinimo: [{ produtoId: 'p2', nome: 'Tinta 7.1', unidade: 'ML',
    quantidade: 0, minimo: 500, faltando: 500, zerado: true }],
  vencendo: [{ loteId: 'l1', produtoId: 'p1', produto: 'Shampoo hidratante', unidade: 'UN', codigo: 'NF-4421',
    quantidade: 9, validade: '2026-10-10', diasRestantes: 14, custoEmRisco: 180,
    vencido: false }],
};

function montar({ alertas = SEM_ALERTA as unknown, contagem = undefined as unknown,
  permissoes = ['estoque:read', 'estoque:write', 'produto:price:write'],
  aoEscrever = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method && aoEscrever instanceof Error) throw aoEscrever;
    if (caminho === '/v1/me/capabilities') {
      return { usuarioId: 'u1', nome: 'Marilda', perfil: 'ADMIN',
        estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes, menus: [] };
    }
    if (caminho === '/v1/produtos/saldos') return SALDOS;
    if (caminho.startsWith('/v1/produtos/alertas')) return alertas;
    if (caminho.includes('/lotes')) return init?.method ? LOTES[0] : LOTES;
    if (caminho.includes('/perdas')) return { quantidade: 2, custoTotal: 41, lotesAtingidos: 1 };
    if (caminho === '/v1/inventarios/aberto') return contagem;
    if (caminho.startsWith('/v1/produtos')) return init?.method ? SHAMPOO : [SHAMPOO, TINTA];
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={fila}><Estoque /></QueryClientProvider>
    </MemoryRouter>,
  );
}

const ultimoEnvio = (sufixo: string) => {
  const envio = chamar.mock.calls
    .filter(([caminho, init]) => String(caminho).includes(sufixo) && init?.method)
    .at(-1);
  return envio && {
    caminho: String(envio[0]),
    metodo: (envio[1] as RequestInit).method,
    corpo: (envio[1] as RequestInit).body
      ? JSON.parse(String((envio[1] as RequestInit).body)) : undefined,
  };
};

const ultimaListagem = () => String(chamar.mock.calls
  .filter(([caminho, init]) => String(caminho).startsWith('/v1/produtos?') && !init?.method)
  .at(-1)?.[0]);

const linhaDe = async (nome: string) =>
  (await screen.findByRole('heading', { name: nome })).closest('article') as HTMLElement;

const abrir = async (nome: string) =>
  userEvent.click(within(await linhaDe(nome)).getByRole('button', { name: 'Abrir' }));

beforeEach(() => { chamar.mockReset(); });

describe('o que precisa de atenção', () => {
  it('diz quanto comprar, que é o número do pedido — e não só "está baixo"', async () => {
    montar({ alertas: COM_ALERTA });

    expect(await screen.findByText(/comprar 500 ml para voltar ao mínimo/))
      .toBeInTheDocument();
    expect(screen.getByText('acabou')).toBeInTheDocument();
  });

  it('sem alerta, a banda não existe — não uma caixa dizendo "0 alertas"', async () => {
    montar();
    await screen.findByRole('heading', { name: 'Shampoo hidratante' });

    expect(screen.queryByRole('heading', { name: 'Precisa de atenção' })).not.toBeInTheDocument();
  });

  it('marca na linha o produto que tem lote vencendo', async () => {
    montar({ alertas: COM_ALERTA });

    const shampoo = await linhaDe('Shampoo hidratante');
    expect(within(shampoo).getByText('tem lote vencendo')).toBeInTheDocument();
  });
});

describe('o depósito', () => {
  it('custo médio sem estoque é travessão, nunca R$ 0,00', async () => {
    // Zero diria "tenho unidades que não custaram nada", que é o caso da amostra do fornecedor.
    // O custo médio mora na ficha; na linha fica só o valor parado.
    montar();
    await abrir('Tinta 7.1');

    expect(await screen.findByText(/custo médio —/)).toBeInTheDocument();
  });

  it('a linha tem uma coluna de dinheiro só: o valor parado', async () => {
    // Custo médio e valor lado a lado, sem rótulo, são dois números que se confundem.
    montar();

    const shampoo = await linhaDe('Shampoo hidratante');
    expect(within(shampoo).getByText('R$ 246,00')).toBeInTheDocument();
    expect(within(shampoo).queryByText('R$ 20,50')).not.toBeInTheDocument();
  });

  it('o valor parado é da seção, e não repetido em cada linha', async () => {
    montar();

    expect(await screen.findByText(/R\$ 246,00 parados na prateleira/)).toBeInTheDocument();
  });

  it('quem saiu do catálogo só aparece quando se pede — senão "trazer de volta" some junto',
    async () => {
      // O servidor lista só os ativos por padrão. Sem o filtro, desativar um produto o apagaria
      // da tela junto com o único botão que o traz de volta.
      montar();
      await screen.findByRole('heading', { name: 'Shampoo hidratante' });
      expect(ultimaListagem()).toContain('somenteAtivos=true');

      await userEvent.click(screen.getByLabelText('Mostrar os que saíram do catálogo'));

      expect(ultimaListagem()).toContain('somenteAtivos=false');
    });

  it('procurar filtra pelo nome', async () => {
    montar();
    await screen.findByRole('heading', { name: 'Tinta 7.1' });

    await userEvent.type(screen.getByLabelText('Procurar produto'), 'tinta');

    expect(screen.queryByRole('heading', { name: 'Shampoo hidratante' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Tinta 7.1' })).toBeInTheDocument();
  });
});

describe('a ficha', () => {
  it('cadastrar manda nome, finalidade, medida e preço', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Novo produto' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Condicionador');
    await userEvent.type(screen.getByLabelText('Preço de venda'), '39,90');

    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar' }));

    expect(ultimoEnvio('/v1/produtos')).toMatchObject({
      metodo: 'POST',
      corpo: { nome: 'Condicionador', finalidade: 'VENDA', unidade: 'UN', precoVenda: 39.9 },
    });
  });

  it('quem não precifica não vê o campo de preço, e a tela diz por quê', async () => {
    // Pergunta por permissão, nunca por perfil: precificar é outro ato que cadastrar.
    montar({ permissoes: ['estoque:read', 'estoque:write'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Novo produto' }));

    expect(screen.queryByLabelText('Preço de venda')).not.toBeInTheDocument();
    expect(screen.getByText(/Preço de venda é de quem precifica/)).toBeInTheDocument();
  });

  it('a medida não se troca depois: mudá-la reescreveria a história dos movimentos', async () => {
    montar();
    await abrir('Shampoo hidratante');

    expect(screen.getByLabelText('Medida')).toBeDisabled();
  });

  it('editar leva a versão lida na query, como o servidor a pede', async () => {
    montar();
    await abrir('Tinta 7.1');

    await userEvent.type(screen.getByLabelText('Nome'), ' escura');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(ultimoEnvio('/v1/produtos/p2')?.caminho).toContain('versao=3');
  });
});

describe('o que entra e o que sai', () => {
  it('dar entrada manda quantidade, custo e validade', async () => {
    montar();
    await abrir('Shampoo hidratante');
    await userEvent.click(await screen.findByRole('button', { name: 'Dar entrada' }));

    await userEvent.type(screen.getByLabelText('Quantas chegaram'), '24');
    await userEvent.type(screen.getByLabelText('Custo de cada'), '19,90');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar entrada' }));

    expect(ultimoEnvio('/v1/produtos/p1/lotes')).toMatchObject({
      metodo: 'POST', corpo: { quantidade: 24, custoUnitario: 19.9 },
    });
  });

  it('perda é do produto: não pede lote, porque quem viu o vazamento não sabe o lote', async () => {
    montar();
    await abrir('Shampoo hidratante');

    await userEvent.type(screen.getByLabelText('Quanto se perdeu'), '2');
    await userEvent.type(screen.getByLabelText('O que aconteceu'), 'caixa molhada na chuva');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar perda' }));

    expect(ultimoEnvio('/perdas')).toMatchObject({
      caminho: '/v1/produtos/p1/perdas',
      metodo: 'POST',
      corpo: { quantidade: 2, motivo: 'caixa molhada na chuva' },
    });
  });

  it('ajuste é do lote, e vai com sinal', async () => {
    montar();
    await abrir('Shampoo hidratante');
    await userEvent.click(await screen.findByRole('button', { name: 'Ajustar' }));

    await userEvent.type(screen.getByLabelText('Diferença'), '-3');
    await userEvent.type(screen.getByLabelText('Motivo'), 'três quebrados na estante');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar' }));

    expect(ultimoEnvio('/ajustes')).toMatchObject({
      caminho: '/v1/lotes/l1/ajustes',
      corpo: { quantidade: -3, motivo: 'três quebrados na estante' },
    });
  });

  it('a recusa do servidor aparece com as palavras dele', async () => {
    const { ErroDaApi } = await import('../../api/http');
    montar({ aoEscrever: new ErroDaApi(422, 'ER-EST-MOTIVO_OBRIGATORIO',
      'descreva o motivo em pelo menos 3 caracteres') });
    await abrir('Shampoo hidratante');

    await userEvent.type(screen.getByLabelText('Quanto se perdeu'), '2');
    await userEvent.type(screen.getByLabelText('O que aconteceu'), 'ab');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar perda' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('descreva o motivo em pelo menos 3 caracteres');
  });
});

describe('a contagem', () => {
  it('sem contagem aberta, o convite é começar uma', async () => {
    montar();

    expect(await screen.findByRole('link', { name: 'Começar contagem' }))
      .toHaveAttribute('href', '/estoque/contagem');
  });

  it('com contagem aberta, o convite é continuar — e ela diz quantos lotes já foram', async () => {
    // RN-EST-040: sem isto, fechar a aba deixava a contagem viva e inalcançável.
    montar({ contagem: {
      id: 'c1', status: 'ABERTO', observacao: 'contagem do mês',
      criadoEm: '2026-09-26T09:00:00Z', encerradoEm: null,
      quantasDivergem: 1, saldoDasDivergencias: -3,
      itens: [{ loteId: 'l1', produtoId: 'p1', produto: 'Shampoo hidratante',
        loteCodigo: 'NF-4421', esperada: 12, contada: 9, divergencia: -3, bateu: false,
        observacao: null, contadoEm: '2026-09-26T09:10:00Z' }],
    } });

    expect(await screen.findByText('Contagem em andamento')).toBeInTheDocument();
    expect(screen.getByText(/1 lote contado/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Continuar a contagem' })).toBeInTheDocument();
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: alertas, lista e um produto aberto', async () => {
    const { container } = montar({ alertas: COM_ALERTA });
    await abrir('Shampoo hidratante');
    await screen.findByRole('heading', { name: 'Caixas em estoque' });

    await deveSerAcessivel(container);
  });
});
