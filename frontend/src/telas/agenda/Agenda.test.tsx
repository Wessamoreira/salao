import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
    <QueryClientProvider client={cliente}><Agenda /></QueryClientProvider>,
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
    render(<QueryClientProvider client={cliente}><Agenda /></QueryClientProvider>);

    expect(await screen.findByText(/Não foi possível carregar/)).toBeInTheDocument();
  });
});
