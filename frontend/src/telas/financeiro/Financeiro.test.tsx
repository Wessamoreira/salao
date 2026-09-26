import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Financeiro } from './Financeiro';

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

const ANA = 'p1';
const JULIA = 'p2';

const PREVIA = {
  de: '2026-03-01', ate: '2026-03-31',
  totalARepassar: 520, quantosRecebem: 1, versao: 'hash-de-marco',
  linhas: [
    { profissionalId: ANA, profissionalNome: 'Ana Ribeiro', saldoAnterior: -120,
      movimentoDoPeriodo: 640, saldoFinal: 520, aRepassar: 520 },
    { profissionalId: JULIA, profissionalNome: 'Júlia Menezes', saldoAnterior: -80,
      movimentoDoPeriodo: 30, saldoFinal: -50, aRepassar: 0 },
  ],
};

const EXTRATO = {
  profissionalId: ANA, nome: 'Ana Ribeiro', de: '2026-03-01', ate: '2026-03-31',
  saldoAnterior: -120, aReceber: 640, saldoFinal: 520,
  totais: [
    { tipo: 'COMISSAO', valor: 900 },
    { tipo: 'VALE', valor: -260 },
  ],
  linhas: [
    { data: '2026-03-10', tipo: 'COMISSAO', descricao: 'Coloração 30%', valor: 270,
      origemTipo: 'COMANDA', origemId: 'c1', lancamentoId: 'l1' },
    { data: '2026-03-12', tipo: 'VALE', descricao: 'Vale em dinheiro', valor: -260,
      origemTipo: 'VALE', origemId: 'v1', lancamentoId: 'l2' },
  ],
};

const VALES = [
  { id: 'v1', profissionalId: ANA, profissionalNome: 'Ana Ribeiro', valor: 260,
    forma: 'DINHEIRO', dataCompetencia: '2026-03-12', motivo: null, cancelado: false },
];

const FECHADO = [
  { id: 'f1', profissionalId: ANA, profissionalNome: 'Ana Ribeiro',
    periodoInicio: '2026-03-01', periodoFim: '2026-03-31', saldoAnterior: -120,
    movimentoDoPeriodo: 640, valorRepassado: 520, saldoATransportar: 0, estornadoEm: null },
];

function montar({ previa = PREVIA as unknown, fechamentos = [] as unknown[],
  permissoes = ['financeiro:read:all', 'financeiro:advance', 'financeiro:close'],
  periodicidade = 'MENSAL', aoEscrever = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method && aoEscrever instanceof Error) throw aoEscrever;
    if (caminho === '/v1/me/capabilities') {
      return { usuarioId: 'u1', nome: 'Marilda', perfil: 'ADMIN',
        estabelecimento: { timezone: 'America/Sao_Paulo', periodicidadeDeFechamento: periodicidade },
        permissoes, menus: [] };
    }
    if (caminho.startsWith('/v1/fechamentos/previa')) return previa;
    if (caminho.startsWith('/v1/fechamentos')) return init?.method ? FECHADO : fechamentos;
    if (caminho.startsWith('/v1/extratos')) return EXTRATO;
    if (caminho.startsWith('/v1/vales')) return init?.method ? VALES[0] : VALES;
    if (caminho.startsWith('/v1/lancamentos')) return { anuladas: 1 };
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={fila}><Financeiro /></QueryClientProvider>
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

const ultimaLeitura = (prefixo: string) => String(chamar.mock.calls
  .filter(([caminho, init]) => String(caminho).startsWith(prefixo) && !init?.method)
  .at(-1)?.[0]);

const linhaDe = async (nome: string) =>
  (await screen.findByRole('heading', { name: nome })).closest('article') as HTMLElement;

// 5 de abril de 2026, meio-dia em São Paulo: o período padrão é março inteiro.
//
// Só `Date` é falsificado. Falsificar todos os relógios trava `findBy*` e o `userEvent`, que
// esperam por timers reais — e o teste inteiro morre em timeout dizendo "não achei o elemento",
// que é a pista mais enganosa possível.
beforeAll(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-04-05T15:00:00Z'));
});
afterAll(() => { vi.useRealTimers(); });
beforeEach(() => { chamar.mockReset(); });

describe('o período governa a tela', () => {
  it('abre no último período que terminou, na periodicidade do salão', async () => {
    // Fecha-se o mês depois que ele acaba. Abrir em "mês corrente" ofereceria fechar um período
    // pela metade.
    montar();

    expect(await screen.findByText('1 — 31 de março de 2026')).toBeInTheDocument();
    expect(ultimaLeitura('/v1/fechamentos/previa'))
      .toBe('/v1/fechamentos/previa?de=2026-03-01&ate=2026-03-31');
  });

  it('a seta volta um período inteiro, sem digitar data', async () => {
    montar();
    await screen.findByText('1 — 31 de março de 2026');

    await userEvent.click(screen.getByRole('button', { name: 'Período anterior' }));

    // Um mês inteiro anda um MÊS, não trinta e um dias.
    expect(ultimaLeitura('/v1/fechamentos/previa'))
      .toBe('/v1/fechamentos/previa?de=2026-02-01&ate=2026-02-28');
  });

  it('a periodicidade do salão manda: semanal abre numa semana', async () => {
    montar({ periodicidade: 'SEMANAL' });

    // 05/04/2026 é um domingo; a semana fechada é 23/03 a 29/03.
    expect(await screen.findByText(/23 — 29 de março de 2026/)).toBeInTheDocument();
  });
});

describe('quem tem a receber', () => {
  it('mostra quem deve ao salão, com repasse zero — some-la faria a conta não fechar', async () => {
    montar();

    const julia = await linhaDe('Júlia Menezes');
    expect(within(julia).getByText('-R$ 50,00')).toBeInTheDocument();
    expect(within(julia).getByText('R$ 0,00')).toBeInTheDocument();
  });

  it('o total e quantos recebem ficam na seção, que é a frase da confirmação', async () => {
    montar();

    expect(await screen.findByText('R$ 520,00 para 1 pessoa.')).toBeInTheDocument();
  });
});

describe('fechar o período', () => {
  it('a confirmação diz o efeito em número, e leva a versão que está na tela', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Fechar o período' }));
    expect(screen.getByText(/Não volta atrás sem estorno/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Confirmar o fechamento' }));

    expect(ultimoEnvio('/v1/fechamentos')).toMatchObject({
      metodo: 'POST',
      corpo: { de: '2026-03-01', ate: '2026-03-31', versaoDaPrevia: 'hash-de-marco' },
    });
  });

  it('prévia desatualizada: diz que o número mudou, em vez de repetir o erro do servidor',
    async () => {
      const { ErroDaApi } = await import('../../api/http');
      montar({ aoEscrever: new ErroDaApi(409, 'ER-FIN-PREVIA_DESATUALIZADA',
        'a prévia não corresponde mais às posições') });
      await userEvent.click(await screen.findByRole('button', { name: 'Fechar o período' }));

      await userEvent.click(screen.getByRole('button', { name: 'Confirmar o fechamento' }));

      expect(await screen.findByRole('alert'))
        .toHaveTextContent('O número mudou enquanto você olhava');
    });

  it('período já fechado não oferece fechar de novo', async () => {
    montar({ fechamentos: FECHADO });
    await screen.findByText(/Este período já foi fechado/);

    expect(screen.queryByRole('button', { name: 'Fechar o período' })).not.toBeInTheDocument();
    expect(screen.getByText('Repasses do período')).toBeInTheDocument();
  });

  it('ninguém a receber: fechar fica desabilitado', async () => {
    montar({ previa: { ...PREVIA, quantosRecebem: 0, totalARepassar: 0 } });

    expect(await screen.findByRole('button', { name: 'Fechar o período' })).toBeDisabled();
  });
});

describe('o extrato', () => {
  it('abre pela linha, com os totais por tipo', async () => {
    // Até o vale entrar na conta, "a receber" era "quanto ganhou"; agora é "quanto ganhou menos
    // o que já levou". Sem a abertura, quem recebeu 900 e adiantou 260 lê 640 e não entende.
    montar();
    await userEvent.click(within(await linhaDe('Ana Ribeiro'))
      .getByRole('button', { name: 'Ver extrato' }));

    expect(await screen.findByText('Coloração 30%')).toBeInTheDocument();
    expect(screen.getByText('R$ 900,00')).toBeInTheDocument();
    // Duas vezes: no total do tipo e na linha do vale.
    expect(screen.getAllByText('-R$ 260,00')).toHaveLength(2);
  });

  it('anular usa o id da linha do razão, não o da origem', async () => {
    montar();
    await userEvent.click(within(await linhaDe('Ana Ribeiro'))
      .getByRole('button', { name: 'Ver extrato' }));
    await screen.findByText('Coloração 30%');

    await userEvent.click(screen.getAllByRole('button', { name: 'Anular' })[0]);
    await userEvent.type(screen.getByLabelText('Por que anular'), 'lançada na pessoa errada');
    await userEvent.click(screen.getByRole('button', { name: /^Anular R\$/ }));

    expect(ultimoEnvio('/estorno')).toMatchObject({
      caminho: '/v1/lancamentos/l1/estorno',
      corpo: { motivo: 'lançada na pessoa errada' },
    });
  });

  it('o ajuste vai no sinal de quem recebe, e a tela diz isso', async () => {
    // No razão a comissão é negativa; pedir esse sinal a quem corrige um erro de digitação
    // seria pedir que ela conhecesse a contabilidade do livro.
    montar();
    await userEvent.click(within(await linhaDe('Ana Ribeiro'))
      .getByRole('button', { name: 'Ver extrato' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Corrigir um valor' }));

    await userEvent.type(screen.getByLabelText('Valor do ajuste'), '45');
    await userEvent.type(screen.getByLabelText('Por quê'), 'comissão apurada a menos');
    await userEvent.click(screen.getByRole('button', { name: 'Lançar o ajuste' }));

    expect(ultimoEnvio('/v1/lancamentos')).toMatchObject({
      metodo: 'POST',
      corpo: { profissionalId: ANA, valor: 45, motivo: 'comissão apurada a menos' },
    });
    expect(screen.queryByText(/Positivo aumenta/)).not.toBeInTheDocument();
  });

  it('estorno não se estorna: a linha de estorno não oferece anular', async () => {
    // RN-FIN-023 — o caminho para reaplicar o valor é o ajuste, não anular a anulação.
    montar();
    chamar.mockImplementation(async (caminho: string) => {
      if (caminho === '/v1/me/capabilities') {
        return { usuarioId: 'u1', perfil: 'ADMIN',
          estabelecimento: { timezone: 'America/Sao_Paulo', periodicidadeDeFechamento: 'MENSAL' },
          permissoes: ['financeiro:read:all'], menus: [] };
      }
      if (caminho.startsWith('/v1/fechamentos/previa')) return PREVIA;
      if (caminho.startsWith('/v1/extratos')) {
        return { ...EXTRATO, linhas: [{ ...EXTRATO.linhas[0], tipo: 'ESTORNO',
          descricao: 'Estorno — lançada na pessoa errada' }] };
      }
      return [];
    });
    await userEvent.click(within(await linhaDe('Ana Ribeiro'))
      .getByRole('button', { name: 'Ver extrato' }));
    await screen.findByText(/Estorno — lançada/);

    expect(screen.queryByRole('button', { name: 'Anular' })).not.toBeInTheDocument();
  });
});

describe('vales', () => {
  it('registra com quem, quanto e por onde saiu', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Entregar vale' }));

    await userEvent.selectOptions(screen.getByLabelText('Para quem'), ANA);
    await userEvent.type(screen.getByLabelText('Valor do vale'), '200');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar o vale' }));

    expect(ultimoEnvio('/v1/vales')).toMatchObject({
      metodo: 'POST',
      corpo: { profissionalId: ANA, valor: 200, forma: 'DINHEIRO' },
    });
  });

  it('avisa que o vale entra com a data de hoje quando se olha outro período', async () => {
    // RN-FIN-014: a competência não se escolhe. Sem o aviso, a pessoa procuraria na lista o
    // lançamento que nunca vai aparecer ali.
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Entregar vale' }));

    expect(screen.getByText(/não vai aparecer nesta lista/)).toBeInTheDocument();
  });
});

describe('quem só vê o próprio', () => {
  it('explica o que falta, em vez de tentar carregar o que o servidor recusa', async () => {
    montar({ permissoes: ['financeiro:read:own'] });

    expect(await screen.findByRole('heading', { name: /Seu extrato ainda não abre/ }))
      .toBeInTheDocument();
    expect(chamar.mock.calls.filter(([c]) => String(c).startsWith('/v1/fechamentos')))
      .toHaveLength(0);
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: prévia, extrato e vales', async () => {
    const { container } = montar({ fechamentos: FECHADO });
    await userEvent.click(within(await linhaDe('Ana Ribeiro'))
      .getByRole('button', { name: 'Ver extrato' }));
    await screen.findByText('Coloração 30%');

    await deveSerAcessivel(container);
  });
});
