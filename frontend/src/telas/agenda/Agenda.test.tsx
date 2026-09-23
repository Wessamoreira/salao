import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { chaveDasCapacidades } from '../../api/capacidades';
import { Agenda } from './Agenda';

/** 2026-09-17 às 10h24 em São Paulo — o instante do canvas de design. */
const AGORA = new Date('2026-09-17T13:24:00Z');
const SAO_PAULO = 'America/Sao_Paulo';

const as = (hora: number, minuto = 0) =>
  new Date(Date.UTC(2026, 8, 17, hora + 3, minuto)).toISOString();

const ANA = { id: 'ana', nome: 'Ana Ribeiro', apelido: 'Ana' };
const JULIA = { id: 'julia', nome: 'Júlia Menezes', apelido: null };

function agendaDoDia(sobrescrever: Record<string, unknown> = {}) {
  return {
    data: '2026-09-17',
    fuso: SAO_PAULO,
    profissionais: [ANA, JULIA],
    jornadas: {
      ana: [{ inicio: as(9), fim: as(18) }],
      julia: [{ inicio: as(9), fim: as(18) }],
    },
    agendamentos: [],
    bloqueios: [],
    nomesDeClientes: {},
    ...sobrescrever,
  };
}

/** Coloração: aplica 30, química age 40, lava e finaliza 30. */
function coloracao(id: string, profissionalId: string, hora: number) {
  return {
    id,
    status: 'CONFIRMADO',
    inicio: as(hora),
    fim: as(hora + 1, 40),
    clienteId: 'marilda',
    profissionalId,
    servicoId: 'mechas',
    origem: 'PAINEL',
    observacao: null,
    minutosDoProfissional: 60,
    versao: 0,
    blocos: [
      { ordem: 0, tipo: 'ATIVO', janela: { inicio: as(hora), fim: as(hora, 30) },
        profissionalId, recursoId: null, unidade: null },
      { ordem: 1, tipo: 'PAUSA', janela: { inicio: as(hora, 30), fim: as(hora + 1, 10) },
        profissionalId: null, recursoId: null, unidade: null },
      { ordem: 2, tipo: 'ATIVO', janela: { inicio: as(hora + 1, 10), fim: as(hora + 1, 40) },
        profissionalId, recursoId: null, unidade: null },
    ],
  };
}

function montar(resposta: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true, status: 200,
    json: async () => resposta,
    headers: new Headers({ 'content-type': 'application/json' }),
  }));
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={cliente}><Agenda /></QueryClientProvider>
    </MemoryRouter>,
  );
}

/** O endereço de verdade, para provar que o dia está nele e não num estado escondido. */
function Endereco() {
  const local = useLocation();
  return <output aria-label="endereço">{local.pathname + local.search}</output>;
}

/**
 * A agenda como ela abre DENTRO da casca: as capacidades já estão no cache, porque a casca
 * não desenha tela nenhuma antes de o contrato chegar. É o que faz o primeiro pedido já sair
 * com o dia certo, em vez de sair com o dia da máquina e se corrigir depois.
 */
function montarNoSalao({ fuso, endereco = '/agenda' }: { fuso: string; endereco?: string }) {
  const pedidos: string[] = [];
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
    pedidos.push(String(url));
    return { ok: true, status: 200, json: async () => agendaDoDia(),
      headers: new Headers({ 'content-type': 'application/json' }) };
  }));
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  cliente.setQueryData(chaveDasCapacidades, {
    estabelecimento: { timezone: fuso }, permissoes: [], menus: [], flags: {},
  });
  const tela = render(
    <MemoryRouter initialEntries={[endereco]}>
      <QueryClientProvider client={cliente}><Agenda /><Endereco /></QueryClientProvider>
    </MemoryRouter>,
  );
  return { ...tela, diasPedidos: () => pedidos.filter((url) => url.includes('/agendamentos/dia')) };
}

/**
 * Uma resposta por rota — a `montar` comum devolve a mesma para tudo, o que basta para a grade e
 * esconde o aviso de vaga (sem `flags`, ele não aparece).
 */
function montarComVagas(vagas: unknown[], flags: Record<string, boolean>) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
    const caminho = String(url);
    const corpo = caminho.includes('/me/capabilities')
      ? { estabelecimento: { timezone: SAO_PAULO }, permissoes: [], menus: [], flags }
      : caminho.includes('/vagas-abertas') ? vagas : agendaDoDia();
    return { ok: true, status: 200, json: async () => corpo,
      headers: new Headers({ 'content-type': 'application/json' }) };
  }));
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={cliente}><Agenda /></QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(AGORA);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('grade da agenda', () => {
  it('mostra uma coluna por profissional, com o apelido quando existe', async () => {
    // "Nice" cabe na coluna da grade; o nome completo, não (RT-EQP-001).
    montar(agendaDoDia());

    expect(await screen.findByText('Ana')).toBeInTheDocument();
    expect(screen.getByText('Júlia Menezes')).toBeInTheDocument();
  });

  it('a PAUSA não vira tarja com nome — é a peça que distingue esta grade', async () => {
    // A cliente está no salão e a profissional está livre. Desenhar a pausa como bloco
    // cheio faria a grade mentir sobre a ocupação dela, e o encaixe de 40 minutos
    // sumiria da tela.
    montar(agendaDoDia({
      agendamentos: [coloracao('a1', 'ana', 13)],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));

    const tarjas = await screen.findAllByRole('button', { name: /Marilda/ });

    expect(tarjas).toHaveLength(2);
    expect(tarjas[0]).toHaveTextContent('13:00');
    expect(tarjas[1]).toHaveTextContent('14:10');
  });

  it('a carga conta os minutos da profissional, não a duração', async () => {
    // Coloração de 100 minutos com 40 de pausa: 1h00 de agenda vendida, não 1h40. É o
    // número que decide se dá para encaixar mais um.
    montar(agendaDoDia({
      agendamentos: [coloracao('a1', 'ana', 13)],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));

    expect(await screen.findByText('1h00')).toBeInTheDocument();
  });

  it('desenha a linha do agora no dia de hoje', async () => {
    // O achado mais sério da crítica de design: sem ela, um calendário é uma tabela.
    montar(agendaDoDia());

    expect(await screen.findAllByRole('separator', { name: 'Agora' })).toHaveLength(2);
  });

  it('não desenha a linha do agora em outro dia', async () => {
    montar(agendaDoDia({ data: '2026-09-18' }));

    await screen.findByText('Ana');
    expect(screen.queryByRole('separator', { name: 'Agora' })).not.toBeInTheDocument();
  });

  it('o cancelado continua na grade, riscado', async () => {
    // O buraco precisa ser visível na agenda de ontem: sumir esconderia o que aconteceu.
    const cancelado = { ...coloracao('a1', 'ana', 13), status: 'CANCELADO' };
    montar(agendaDoDia({
      agendamentos: [cancelado],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));

    const tarjas = await screen.findAllByRole('button', { name: /Marilda/ });

    expect(tarjas[0].className).toContain('cancelado');
  });

  it('o cancelado não conta na carga do dia', async () => {
    const cancelado = { ...coloracao('a1', 'ana', 13), status: 'CANCELADO' };
    montar(agendaDoDia({
      agendamentos: [cancelado],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));

    await screen.findAllByRole('button', { name: /Marilda/ });
    const colunaDaAna = screen.getByText('Ana').closest('header')!;
    expect(within(colunaDaAna).getByText('0h00')).toBeInTheDocument();
  });

  it('o bloqueio aparece com o motivo, sem cliente', async () => {
    montar(agendaDoDia({
      bloqueios: [{
        id: 'b1', profissionalId: 'ana', recursoId: null, motivo: 'CURSO',
        descricao: 'curso de coloração', janela: { inicio: as(14), fim: as(16) },
      }],
    }));

    expect(await screen.findByText('curso')).toBeInTheDocument();
  });

  it('salão sem profissional explica em vez de mostrar grade vazia', async () => {
    montar(agendaDoDia({ profissionais: [], jornadas: {} }));

    expect(await screen.findByText(/Ninguém para atender ainda/)).toBeInTheDocument();
  });

  it('clicar na tarja mostra o que não cabe nela', async () => {
    // Um botão que não faz nada é pior que um bloco que não é botão. E o painel mostra
    // a distinção que decide se cabe um encaixe: 1h40 de relógio, 1h00 de agenda.
    montar(agendaDoDia({
      agendamentos: [coloracao('a1', 'ana', 13)],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));
    const tarjas = await screen.findAllByRole('button', { name: /Marilda/ });

    await userEvent.click(tarjas[0]);

    const painel = screen.getByRole('complementary', { name: 'Detalhe do atendimento' });
    expect(within(painel).getByText('13:00 — 14:40')).toBeInTheDocument();
    expect(within(painel).getByText('1h40')).toBeInTheDocument();
    expect(within(painel).getByText('1h00')).toBeInTheDocument();
  });

  it('o status aparece por extenso no painel, não como código', async () => {
    // A tarja mostra cor e forma; a palavra precisa existir em algum lugar, e
    // "SOLICITADO" em caixa alta é vocabulário de banco, não de balcão.
    const solicitado = { ...coloracao('a1', 'ana', 13), status: 'SOLICITADO' };
    montar(agendaDoDia({
      agendamentos: [solicitado],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));
    const tarjas = await screen.findAllByRole('button', { name: /Marilda/ });

    await userEvent.click(tarjas[0]);

    expect(screen.getByText('Aguardando confirmação')).toBeInTheDocument();
  });

  it('erro na carga não deixa a tela em branco', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('rede')));
    const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <MemoryRouter>
        <QueryClientProvider client={cliente}><Agenda /></QueryClientProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText(/Não foi possível carregar/)).toBeInTheDocument();
  });
});

describe('o dia da agenda', () => {
  it('abre o dia do SALÃO, não o da máquina', async () => {
    // 00h30 de São Paulo, onde roda o teste, ainda é 23h30 do dia anterior em Manaus. A
    // recepção de Manaus abria a agenda de amanhã e não tinha como perceber: a tela mostrava
    // o dia da máquina (inviolável nº 3, ADR-0009).
    vi.setSystemTime(new Date('2026-09-18T03:30:00Z'));
    const { diasPedidos } = montarNoSalao({ fuso: 'America/Manaus' });

    await screen.findByText('Ana');

    expect(diasPedidos()).toEqual(['/api/v1/agendamentos/dia?data=2026-09-17']);
  });

  it('a seta escreve o dia no endereço — recarregar volta no mesmo dia', async () => {
    montarNoSalao({ fuso: SAO_PAULO });
    await screen.findByText('Ana');

    await userEvent.click(screen.getByRole('button', { name: 'Próximo dia' }));

    expect(screen.getByRole('status', { name: 'endereço' })).toHaveTextContent('/agenda?dia=2026-09-18');
  });

  it('hoje é o endereço sem parâmetro, e o botão diz que já se está nele', async () => {
    montarNoSalao({ fuso: SAO_PAULO, endereco: '/agenda?dia=2026-09-20' });
    await screen.findByText('Ana');
    const hoje = screen.getByRole('button', { name: 'hoje' });
    expect(hoje).not.toHaveAttribute('aria-current');

    await userEvent.click(hoje);

    expect(screen.getByRole('status', { name: 'endereço' })).toHaveTextContent('/agenda');
    expect(screen.getByRole('status', { name: 'endereço' })).not.toHaveTextContent('dia=');
    expect(hoje).toHaveAttribute('aria-current', 'date');
  });

  it('um dia impossível no endereço não derruba a agenda', async () => {
    // `2026-02-31` passa em qualquer regex de formato e não existe. `Intl` LANÇA para data
    // inválida — a tela inteira sumiria por causa de um link colado torto.
    const { diasPedidos } = montarNoSalao({ fuso: SAO_PAULO, endereco: '/agenda?dia=2026-02-31' });

    await screen.findByText('Ana');

    expect(diasPedidos()).toEqual(['/api/v1/agendamentos/dia?data=2026-09-17']);
    expect(screen.getByText('17 de setembro')).toBeInTheDocument();
  });
});

describe('aviso de vaga aberta', () => {
  it('quem chama a fila vê que abriu vaga, e o caminho para ela', async () => {
    // RT-AGD-010: a vaga nasce no cancelamento, que acontece nesta tela. Sem o aviso, ela só
    // existe para quem lembra de abrir a outra.
    montarComVagas([{ id: 'v1' }, { id: 'v2' }], { podeAgendarParaOutros: true });

    expect(await screen.findByText(/Abriram 2 vagas/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver a fila de espera' }))
      .toHaveAttribute('href', '/fila');
  });

  it('sem vaga com gente esperando, não há aviso nenhum', async () => {
    montarComVagas([], { podeAgendarParaOutros: true });

    await screen.findByText('Ana');
    expect(screen.queryByRole('link', { name: 'Ver a fila de espera' })).not.toBeInTheDocument();
  });

  it('quem não marca para os outros não recebe o aviso', async () => {
    // O link leva ao telefone das clientes que esperam: é a mesma permissão da tela da fila.
    montarComVagas([{ id: 'v1' }], { podeAgendarParaOutros: false });

    await screen.findByText('Ana');
    expect(screen.queryByText(/Abriu uma vaga/)).not.toBeInTheDocument();
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: a grade do dia', async () => {
    const { container } = montar(agendaDoDia({
      agendamentos: [coloracao('a1', 'ana', 13)],
      nomesDeClientes: { marilda: 'Marilda S.' },
    }));
    await screen.findByText('Ana');

    await deveSerAcessivel(container);
  });
});
