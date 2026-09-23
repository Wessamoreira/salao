import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ErroDaApi } from '../../api/http';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Precos } from './Precos';

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

const SUGESTAO = {
  id: 's1', produtoId: 'p1', produto: 'Máscara 500g',
  motivo: 'MARGEM_ABAIXO_DO_MINIMO', rotulo: 'Margem abaixo do mínimo',
  precoAtual: 38, custoMedio: 31.2, precoSugerido: 49.9,
  margemAtual: 21.79, margemSugerida: 59.94,
  racional: 'Custo médio R$ 31,20; preço R$ 38,00 dá 21,8% de margem, abaixo dos 30% mínimos.',
  explicacao: null,
};

const PAINEL = { margemMinima: 30, geradaEm: '2026-09-20T14:02:00Z', itens: [SUGESTAO] };

const DECISAO = {
  id: 'd1', produtoId: 'p9', produto: 'Shampoo 1L', decisao: 'APLICADA',
  motivo: 'MARGEM_ABAIXO_DO_MINIMO', precoAnterior: 42, custoMedio: 35, precoSugerido: 59.9,
  precoAplicado: 49.9, racional: 'Custo médio R$ 35,00…', justificativa: null,
  quemDecidiu: 'Wesley', decididaEm: '2026-09-20T14:30:00Z',
};

function montar({ painel = PAINEL as unknown, decisoes = [] as unknown[],
  aoDecidir = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (caminho === '/v1/me/capabilities') {
      return { estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho.includes('/aprovacao') || caminho.includes('/recusa')) {
      if (aoDecidir instanceof Error) throw aoDecidir;
      return { ...(painel as { itens: unknown[] }), itens: [] };
    }
    if (caminho === '/v1/politica-de-preco') return { margemMinima: 45 };
    if (caminho.startsWith('/v1/decisoes-de-preco')) {
      return { itens: decisoes, proximoCursor: null, temMais: false };
    }
    if (init?.method === 'POST') return painel;
    return painel;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={fila}><Precos /></QueryClientProvider>);
}

const corpoDe = (sufixo: string) => JSON.parse(String((chamar.mock.calls
  .filter(([caminho, init]) => String(caminho).endsWith(sufixo) && init?.method === 'POST')
  .map(([, init]) => (init as RequestInit).body).at(-1))));

beforeEach(() => { chamar.mockReset(); });

describe('sugestões de preço', () => {
  it('mostra os dois preços com as duas margens — é o que o dono precisa para discordar', async () => {
    montar();

    expect(await screen.findByText('Máscara 500g')).toBeInTheDocument();
    expect(screen.getByText('R$ 38,00')).toBeInTheDocument();
    expect(screen.getByText('21,8% de margem')).toBeInTheDocument();
    expect(screen.getByText('R$ 49,90')).toBeInTheDocument();
    expect(screen.getByText('59,9% de margem')).toBeInTheDocument();
    expect(screen.getByText(/abaixo dos 30% mínimos/)).toBeInTheDocument();
  });

  it('produto sem preço mostra "sem preço", e não zero', async () => {
    montar({ painel: { ...PAINEL,
      itens: [{ ...SUGESTAO, precoAtual: null, margemAtual: null }] } });

    expect(await screen.findByText('sem preço')).toBeInTheDocument();
    expect(screen.queryByText('R$ 0,00')).not.toBeInTheDocument();
  });

  it('sem margem mínima a busca fica travada, e a tela diz por quê', async () => {
    montar({ painel: { ...PAINEL, margemMinima: null, itens: [] } });

    // O aviso só aparece com os dados carregados: esperar por ele é esperar pelo painel.
    expect(await screen.findByText(/um piso escolhido por ele seria um número que ninguém decidiu/))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Procurar preços errados' })).toBeDisabled();
  });

  it('aprovar vem com o sugerido preenchido e aplica o que estiver no campo', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Aprovar' }));

    const campo = screen.getByRole('textbox', { name: 'Aplicar o preço de' });
    expect(campo).toHaveValue('49,90');

    await userEvent.clear(campo);
    await userEvent.type(campo, '44,90');
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar' }));

    expect(corpoDe('/v1/sugestoes-de-preco/s1/aprovacao')).toEqual({ preco: 44.9 });
  });

  it('recusar exige motivo, e avisa que o produto fica calado', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Recusar' }));

    expect(screen.getByText(/para de pedir a mesma coisa por 90 dias/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar recusa' })).toBeDisabled();

    await userEvent.type(screen.getByLabelText('Por quê?'), 'cliente fiel leva toda semana');
    await userEvent.click(screen.getByRole('button', { name: 'Confirmar recusa' }));

    expect(corpoDe('/v1/sugestoes-de-preco/s1/recusa'))
      .toEqual({ motivo: 'cliente fiel leva toda semana' });
  });

  it('a recusa do servidor aparece no cartão, e o painel continua aberto', async () => {
    montar({ aoDecidir: new ErroDaApi(422, 'ER-EST-PRECO_ABAIXO_DO_CHAO',
    'o preço não pode ficar abaixo de R$ 40,56') });
    await userEvent.click(await screen.findByRole('button', { name: 'Aprovar' }));

    await userEvent.click(screen.getByRole('button', { name: 'Aplicar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('R$ 40,56');
    expect(screen.getByRole('textbox', { name: 'Aplicar o preço de' })).toBeInTheDocument();
  });

  it('salvar a margem manda o número, e a vírgula vira ponto', async () => {
    montar();
    const campo = await screen.findByRole('textbox', { name: 'Margem mínima' });

    await userEvent.clear(campo);
    await userEvent.type(campo, '42,5');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar margem' }));

    const corpo = JSON.parse(String((chamar.mock.calls
      .filter(([caminho, init]) => caminho === '/v1/politica-de-preco' && init?.method === 'PUT')
      .map(([, init]) => (init as RequestInit).body).at(-1))));
    expect(corpo).toEqual({ margemMinima: 42.5 });
  });

  it('a trilha diz o que foi feito, e marca quando o preço aplicado difere do sugerido', async () => {
    montar({ decisoes: [DECISAO] });

    expect(await screen.findByText(/R\$\s?42,00 → R\$\s?49,90/))
      .toHaveTextContent(/sugerido R\$\s?59,90/);
    expect(screen.getByText(/Wesley/)).toBeInTheDocument();
  });

  it('recusa na trilha mostra o motivo escrito', async () => {
    montar({ decisoes: [{ ...DECISAO, decisao: 'RECUSADA', precoAplicado: null,
      justificativa: 'é sazonal, sai no inverno' }] });

    expect(await screen.findByText('é sazonal, sai no inverno')).toBeInTheDocument();
    expect(screen.getByText(/Recusou R\$\s?59,90/)).toBeInTheDocument();
  });
});

describe('queima de vencimento (RT-EST-012)', () => {
  it('a queima aparece com a etiqueta e a conta do degrau, como qualquer sugestão', async () => {
    montar({ painel: { ...PAINEL, itens: [{ ...SUGESTAO, id: 's0',
      motivo: 'QUEIMA_DE_VENCIMENTO', rotulo: 'Queima de vencimento',
      precoAtual: 139.9, custoMedio: 100, precoSugerido: 109.9, margemAtual: 39.9,
      margemSugerida: 9.9,
      racional: 'Lote vence em 5 dias, com 10 em estoque. Na última semana a queima vai até o custo médio de R$ 100,00; R$ 109,90 na régua.' }] } });

    expect(await screen.findByText('Queima de vencimento')).toBeInTheDocument();
    expect(screen.getByText(/vence em 5 dias/)).toBeInTheDocument();
    expect(screen.getByText('9,9% de margem')).toBeInTheDocument();
    // O cartão diz o motivo para a cor: vermelho, com a etiqueta ao lado.
    expect(screen.getByText('Queima de vencimento').closest('li'))
      .toHaveAttribute('data-motivo', 'QUEIMA_DE_VENCIMENTO');
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: as sugestões de preço', async () => {
    const { container } = montar();
    await screen.findByText('Máscara 500g');

    await deveSerAcessivel(container);
  });
});
