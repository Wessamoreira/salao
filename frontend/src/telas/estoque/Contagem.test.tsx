import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Contagem } from './Contagem';

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

const LOTES = [
  { id: 'l1', produtoId: 'p1', codigo: 'NF-4421', custoUnitario: 20,
    quantidade: 12, validade: '2027-03-01', recebidoEm: '2026-09-01' },
  { id: 'l2', produtoId: 'p1', codigo: 'NF-5500', custoUnitario: 22,
    quantidade: 30, validade: null, recebidoEm: '2026-09-20' },
];

const ITEM = (extra: Record<string, unknown> = {}) => ({
  loteId: 'l1', produtoId: 'p1', produto: 'Shampoo hidratante', loteCodigo: 'NF-4421',
  esperada: 12, contada: 9, divergencia: -3, bateu: false, observacao: null,
  contadoEm: '2026-09-26T09:10:00Z', ...extra,
});

const ABERTA = (itens: unknown[] = []) => ({
  id: 'c1', status: 'ABERTO', observacao: 'contagem do mês',
  criadoEm: '2026-09-26T09:00:00Z', encerradoEm: null,
  quantasDivergem: itens.filter((i) => !(i as { bateu: boolean }).bateu).length,
  saldoDasDivergencias: itens.reduce(
    (soma: number, i) => soma + (i as { divergencia: number }).divergencia, 0),
  itens,
});

function montar({ contagem = null as unknown, aoEscrever = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method && aoEscrever instanceof Error) throw aoEscrever;
    if (caminho === '/v1/me/capabilities') {
      return { usuarioId: 'u1', estabelecimento: { timezone: 'America/Sao_Paulo' },
        permissoes: ['estoque:read', 'estoque:write'], menus: [] };
    }
    if (caminho === '/v1/inventarios/aberto') return contagem ?? undefined;
    if (caminho.includes('/aplicar')) return { linhasAjustadas: 1 };
    if (caminho.startsWith('/v1/inventarios')) return contagem ?? ABERTA();
    if (caminho.includes('/lotes')) return LOTES;
    if (caminho.startsWith('/v1/produtos')) return [SHAMPOO];
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={fila}><Contagem /></QueryClientProvider>
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

beforeEach(() => { chamar.mockReset(); });

describe('começar', () => {
  it('sem contagem aberta, o convite é começar — e diz que a contagem será cega', async () => {
    montar();

    expect(await screen.findByRole('button', { name: 'Começar contagem' })).toBeInTheDocument();
    expect(screen.getByText(/não mostra o que o sistema espera/)).toBeInTheDocument();
  });

  it('abrir manda a observação, que é o que a folha vai dizer depois', async () => {
    montar();
    await userEvent.type(await screen.findByLabelText('Do que é esta contagem'),
      'só o depósito');

    await userEvent.click(screen.getByRole('button', { name: 'Começar contagem' }));

    expect(ultimoEnvio('/v1/inventarios'))
      .toMatchObject({ metodo: 'POST', corpo: { observacao: 'só o depósito' } });
  });
});

describe('contando às cegas', () => {
  it('o esperado NÃO aparece enquanto se conta', async () => {
    // Quem vê "10" conta até dez e para. É o viés que a contagem existe para descobrir.
    montar({ contagem: ABERTA() });
    await userEvent.type(await screen.findByLabelText('Procurar produto para contar'), 'sham');
    await userEvent.click(await screen.findByRole('button', { name: 'Shampoo hidratante' }));

    await screen.findByLabelText('Quantas contou de NF-4421');

    // 12 e 30 são os saldos dos dois lotes; nenhum dos dois pode estar na tela.
    expect(screen.queryByText(/\b12\b/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\b30\b/)).not.toBeInTheDocument();
  });

  it('mostra o que a própria pessoa anotou, que é o que ela precisa para corrigir', async () => {
    montar({ contagem: ABERTA([ITEM()]) });
    await userEvent.type(await screen.findByLabelText('Procurar produto para contar'), 'sham');
    await userEvent.click(await screen.findByRole('button', { name: 'Shampoo hidratante' }));

    expect(await screen.findByText('anotado: 9 un')).toBeInTheDocument();
  });

  it('anotar manda PUT no lote: recontar substitui, não soma', async () => {
    montar({ contagem: ABERTA() });
    await userEvent.type(await screen.findByLabelText('Procurar produto para contar'), 'sham');
    await userEvent.click(await screen.findByRole('button', { name: 'Shampoo hidratante' }));

    await userEvent.type(await screen.findByLabelText('Quantas contou de NF-4421'), '9');
    await userEvent.click(screen.getAllByRole('button', { name: 'Anotar' })[0]);

    expect(ultimoEnvio('/v1/inventarios/c1/lotes/l1'))
      .toMatchObject({ metodo: 'PUT', corpo: { contada: 9 } });
  });

  it('bipar o código abre o produto sem passar pelo nome', async () => {
    montar({ contagem: ABERTA() });
    const leitor = await screen.findByLabelText('Bipar o produto');

    await userEvent.type(leitor, '7891234567890{Enter}');

    expect(await screen.findByLabelText('Quantas contou de NF-4421')).toBeInTheDocument();
  });

  it('código desconhecido diz o que fazer, em vez de não fazer nada', async () => {
    montar({ contagem: ABERTA() });
    const leitor = await screen.findByLabelText('Bipar o produto');

    await userEvent.type(leitor, '0000000000000{Enter}');

    expect(await screen.findByRole('alert')).toHaveTextContent('0000000000000');
  });
});

describe('a folha de conferência', () => {
  it('mostra só o que diverge, e conta em número o que bateu', async () => {
    // Vinte linhas que bateram não são notícia; as três que não bateram são a decisão.
    montar({ contagem: ABERTA([
      ITEM(),
      ITEM({ loteId: 'l2', loteCodigo: 'NF-5500', esperada: 30, contada: 30,
        divergencia: 0, bateu: true }),
    ]) });
    await userEvent.click(await screen.findByRole('button', { name: 'Conferir e aplicar' }));

    expect(await screen.findByText(/1 lote bateu\. 1 não bateu/)).toBeInTheDocument();
    expect(screen.getByText('NF-4421')).toBeInTheDocument();
    expect(screen.queryByText('NF-5500')).not.toBeInTheDocument();
  });

  it('a confirmação diz o efeito em número, e não "tem certeza?"', async () => {
    montar({ contagem: ABERTA([ITEM()]) });
    await userEvent.click(await screen.findByRole('button', { name: 'Conferir e aplicar' }));

    const frase = await screen.findByText(/Aplicar escreve/);
    expect(frase).toHaveTextContent('1 ajuste');
    expect(frase).toHaveTextContent('saldo líquido de -3');
    expect(frase).toHaveTextContent('Não volta atrás');
  });

  it('aplicar chama a rota e diz quantas linhas viraram ajuste', async () => {
    montar({ contagem: ABERTA([ITEM()]) });
    await userEvent.click(await screen.findByRole('button', { name: 'Conferir e aplicar' }));

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar contagem' }));

    expect(ultimoEnvio('/aplicar')?.caminho).toBe('/v1/inventarios/c1/aplicar');
    expect(await screen.findByText(/1 linha virou ajuste/)).toBeInTheDocument();
  });

  it('descartar exige motivo — contagem abandonada sem explicação é buraco na trilha', async () => {
    montar({ contagem: ABERTA([ITEM()]) });
    await userEvent.click(await screen.findByRole('button', { name: 'Conferir e aplicar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Descartar a contagem' }));

    expect(screen.getByRole('button', { name: 'Descartar' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Por que descartar'), 'recontar amanhã');
    await userEvent.click(screen.getByRole('button', { name: 'Descartar' }));

    expect(ultimoEnvio('/cancelar')).toMatchObject({
      caminho: '/v1/inventarios/c1/cancelar', corpo: { motivo: 'recontar amanhã' },
    });
  });

  it('a recusa de aplicar aparece com as palavras do servidor, que nomeiam o lote', async () => {
    const { ErroDaApi } = await import('../../api/http');
    montar({ contagem: ABERTA([ITEM()]),
      aoEscrever: new ErroDaApi(422, 'ER-EST-SALDO_INSUFICIENTE',
        'Shampoo hidratante, lote NF-4421: o ajuste não cabe') });
    await userEvent.click(await screen.findByRole('button', { name: 'Conferir e aplicar' }));

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar contagem' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('lote NF-4421');
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: contando e conferindo', async () => {
    const { container } = montar({ contagem: ABERTA([ITEM()]) });
    await screen.findByLabelText('Procurar produto para contar');

    await deveSerAcessivel(container);
  });
});
