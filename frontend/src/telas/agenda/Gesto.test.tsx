import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { chaveDasCapacidades } from '../../api/capacidades';
import { ALTURA_DA_LINHA } from './grade';
import { Agenda } from './Agenda';

const SAO_PAULO = 'America/Sao_Paulo';
const as = (hora: number, minuto = 0) =>
  new Date(Date.UTC(2026, 8, 17, hora + 3, minuto)).toISOString();

const ANA = { id: 'ana', nome: 'Ana Ribeiro', apelido: 'Ana' };

const AGENDAMENTO = {
  id: 'ag1', status: 'SOLICITADO', inicio: as(14), fim: as(15), clienteId: 'c1',
  profissionalId: 'ana', servicoId: 'corte', origem: 'PAINEL', observacao: null,
  minutosDoProfissional: 60, versao: 3,
  blocos: [{ ordem: 0, tipo: 'ATIVO', janela: { inicio: as(14), fim: as(15) },
    profissionalId: 'ana', recursoId: null, unidade: null }],
};

function agendaDoDia(sobrescrever: Record<string, unknown> = {}) {
  return {
    data: '2026-09-17', fuso: SAO_PAULO, profissionais: [ANA],
    jornadas: { ana: [{ inicio: as(9), fim: as(18) }] },
    agendamentos: [], bloqueios: [], nomesDeClientes: { c1: 'Marilda Souza' },
    ...sobrescrever,
  };
}

const SERVICOS = [
  { id: 'corte', nome: 'Corte', descricao: null, ativo: true, versao: 1,
    duracaoTotal: 45, minutosDoProfissional: 45, precoVigente: 80, etapas: [] },
];

const CLIENTES = { interpretacao: 'NOME', aviso: null, itens: [{ id: 'c1', nome: 'Marilda Souza' }] };

/** Cada rota devolve a sua resposta, e as escritas ficam registradas para as asserções. */
function montar({ agenda = agendaDoDia(), permissoes = ['agenda:write:all'],
  aoEscrever = null as unknown, serie = null as unknown } = {}) {
  const enviados: Array<{ url: string; metodo: string; corpo: unknown }> = [];

  vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
    const metodo = (init?.method ?? 'GET').toUpperCase();
    if (metodo !== 'GET') {
      enviados.push({ url: String(url), metodo,
        corpo: init?.body ? JSON.parse(String(init.body)) : undefined });
      if (aoEscrever) {
        return { ok: false, status: 409,
          json: async () => aoEscrever,
          headers: new Headers({ 'content-type': 'application/json' }) };
      }
    }
    const texto = String(url);
    const corpo = texto.includes('/v1/recorrencias') ? serie
      : texto.includes('/v1/servicos') ? SERVICOS
        : texto.includes('/v1/clientes') ? CLIENTES
          : texto.includes('/agendamentos/dia') ? agenda
            : {};
    return { ok: true, status: 200, json: async () => corpo,
      headers: new Headers({ 'content-type': 'application/json' }) };
  }));

  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  fila.setQueryData(chaveDasCapacidades, {
    estabelecimento: { timezone: SAO_PAULO }, permissoes, menus: [], flags: {},
  });
  const tela = render(
    <MemoryRouter initialEntries={['/agenda?dia=2026-09-17']}>
      <QueryClientProvider client={fila}><Agenda /></QueryClientProvider>
    </MemoryRouter>,
  );
  return { ...tela, enviados, ultimo: (parte: string) =>
    enviados.filter((e) => e.url.includes(parte)).at(-1) };
}

/**
 * Arrasta na pista da coluna.
 *
 * O jsdom não tem layout: `getBoundingClientRect` devolve zeros, e sem ele o arrasto não teria
 * de onde tirar pixel nenhum. O `beforeAll` finge uma pista começando no topo da janela, e é só
 * isso que o teste precisa fingir — a conversão de pixel para instante é pura e tem teste
 * próprio em `grade.test.ts`.
 */
async function arrastarNaPista(deY: number, ateY: number) {
  const pista = document.querySelector('[data-pista="ana"]') as HTMLElement;
  fireEvent.pointerDown(pista, { button: 0, pointerId: 1, clientY: deY });
  fireEvent.pointerMove(pista, { pointerId: 1, clientY: ateY });
  fireEvent.pointerUp(pista, { pointerId: 1, clientY: ateY });
}

beforeAll(() => {
  // A grade abre às 8h (jornada das 9h menos a folga de uma hora), e cada linha vale 30 min.
  Element.prototype.getBoundingClientRect = function retangulo(this: Element) {
    return { top: 0, left: 0, right: 200, bottom: 800, width: 200, height: 800, x: 0, y: 0,
      toJSON: () => ({}) } as DOMRect;
  };
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-09-17T13:24:00Z'));
});

beforeEach(() => { vi.unstubAllGlobals?.(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('o arrasto no vazio', () => {
  it('abre a escolha, com o período que o gesto desenhou', async () => {
    montar();
    await screen.findByText('Ana');

    // Da linha 12 (14h) à linha 14 (15h): 8h + 12 × 30min.
    await arrastarNaPista(12 * ALTURA_DA_LINHA, 14 * ALTURA_DA_LINHA);

    const painel = await screen.findByRole('complementary',
      { name: 'O que fazer com este horário' });
    expect(within(painel).getByText('14:00 — 15:00')).toBeInTheDocument();
    expect(within(painel).getByRole('button', { name: 'Marcar atendimento' }))
      .toBeInTheDocument();
    expect(within(painel).getByRole('button', { name: 'Bloquear o horário' }))
      .toBeInTheDocument();
  });

  it('quem não escreve na agenda não abre nada ao arrastar', async () => {
    // A tela esconde; o caso de uso é quem garante (inviolável nº 6).
    montar({ permissoes: ['agenda:read:all'] });
    await screen.findByText('Ana');

    await arrastarNaPista(12 * ALTURA_DA_LINHA, 14 * ALTURA_DA_LINHA);

    expect(screen.queryByRole('complementary', { name: 'O que fazer com este horário' }))
      .not.toBeInTheDocument();
  });
});

describe('marcar pela grade', () => {
  async function abrirMarcacao() {
    await screen.findByText('Ana');
    await arrastarNaPista(12 * ALTURA_DA_LINHA, 14 * ALTURA_DA_LINHA);
    await userEvent.click(await screen.findByRole('button', { name: 'Marcar atendimento' }));
    await userEvent.type(screen.getByLabelText('Cliente'), 'mari');
    await userEvent.click(await screen.findByRole('button', { name: 'Marilda Souza' }));
    await userEvent.selectOptions(screen.getByLabelText('Serviço'), 'corte');
  }

  it('manda origem PAINEL e o início que foi arrastado', async () => {
    // Marcar pela grade É marcar pelo balcão, e é a origem que decide o status inicial.
    const { ultimo } = montar();
    await abrirMarcacao();

    await userEvent.click(screen.getByRole('button', { name: 'Marcar' }));

    expect(ultimo('/v1/agendamentos')).toMatchObject({
      metodo: 'POST',
      corpo: { clienteId: 'c1', profissionalId: 'ana', servicoId: 'corte',
        inicio: as(14), origem: 'PAINEL' },
    });
  });

  it('a duração é a do serviço, e a tela diz isso', async () => {
    // Deixar o arrasto mandar faria um arrasto torto virar uma coloração de vinte minutos.
    montar();
    await abrirMarcacao();

    expect(screen.getByText(/a duração é a do serviço, não a do arrasto/)).toBeInTheDocument();
    expect(screen.getByText(/leva 45min/)).toBeInTheDocument();
  });

  it('repetir cria série, e os dias que não couberam aparecem', async () => {
    // Esconder as recusadas faria a recepção descobrir uma a uma quando as clientes ligassem.
    const { ultimo } = montar({ serie: {
      recorrencia: { id: 'r1' },
      agendamentos: ['a1', 'a2', 'a3'],
      naoCouberam: [as(14 + 24 * 7)],
    } });
    await abrirMarcacao();
    await userEvent.click(screen.getByLabelText('Ela volta sempre'));
    await userEvent.clear(screen.getByLabelText('Quantas vezes'));
    await userEvent.type(screen.getByLabelText('Quantas vezes'), '4');

    await userEvent.click(screen.getByRole('button', { name: 'Marcar' }));

    expect(ultimo('/v1/recorrencias')).toMatchObject({
      metodo: 'POST',
      corpo: { frequencia: 'SEMANAL', quantidade: 4, primeiroInicio: as(14) },
    });
    // O texto é quebrado por um <strong>, então a asserção olha o parágrafo inteiro.
    const resumo = await screen.findByText(/marcados/);
    expect(resumo).toHaveTextContent('3 marcados');
    expect(resumo).toHaveTextContent('1 não couberam');
  });

  it('o dia que não coube vai para a fila, um por vez', async () => {
    const { ultimo } = montar({ serie: {
      recorrencia: { id: 'r1' }, agendamentos: ['a1'], naoCouberam: [as(14 + 24 * 7)],
    } });
    await abrirMarcacao();
    await userEvent.click(screen.getByLabelText('Ela volta sempre'));
    await userEvent.click(screen.getByRole('button', { name: 'Marcar' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Pôr na fila' }));

    expect(ultimo('/fila-de-espera')?.url).toContain('/v1/recorrencias/r1/fila-de-espera');
    expect(await screen.findByText('na fila')).toBeInTheDocument();
  });
});

describe('bloquear pela grade', () => {
  it('manda o período inteiro do arrasto, porque bloqueio não tem duração própria', async () => {
    const { ultimo } = montar();
    await screen.findByText('Ana');
    await arrastarNaPista(8 * ALTURA_DA_LINHA, 10 * ALTURA_DA_LINHA);
    await userEvent.click(await screen.findByRole('button', { name: 'Bloquear o horário' }));

    await userEvent.selectOptions(screen.getByLabelText('Por quê'), 'ALMOCO');
    await userEvent.click(screen.getByRole('button', { name: 'Bloquear' }));

    expect(ultimo('/v1/bloqueios-de-agenda')).toMatchObject({
      metodo: 'POST',
      corpo: { profissionalId: 'ana', motivo: 'ALMOCO', inicio: as(12), fim: as(13) },
    });
  });

  it('bloquear sobre atendimento mostra a frase do servidor', async () => {
    // Quem quer o horário livre cancela primeiro, avisando a cliente, em vez de fazê-la
    // chegar e descobrir.
    montar({ aoEscrever: { codigo: 'ER-AGD-CONFLITO_HORARIO',
      detail: 'há atendimento marcado neste horário; cancele antes de bloquear' } });
    await screen.findByText('Ana');
    await arrastarNaPista(12 * ALTURA_DA_LINHA, 14 * ALTURA_DA_LINHA);
    await userEvent.click(await screen.findByRole('button', { name: 'Bloquear o horário' }));

    await userEvent.click(screen.getByRole('button', { name: 'Bloquear' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('cancele antes de bloquear');
  });

  it('o bloqueio na grade sai com um clique — não tem ninguém do outro lado', async () => {
    const { ultimo } = montar({ agenda: agendaDoDia({ bloqueios: [
      { id: 'b1', profissionalId: 'ana', recursoId: null, motivo: 'ALMOCO',
        descricao: null, janela: { inicio: as(12), fim: as(13) } },
    ] }) });

    await userEvent.click(await screen.findByRole('button',
      { name: 'Tirar o bloqueio de almoco' }));

    expect(ultimo('/v1/bloqueios-de-agenda')).toMatchObject({ metodo: 'DELETE' });
  });
});

describe('o detalhe que age', () => {
  async function abrirDetalhe() {
    await userEvent.click(await screen.findByRole('button', { name: /Marilda Souza/ }));
    return screen.findByRole('complementary', { name: 'Detalhe do atendimento' });
  }

  it('confirma levando a versão que a tela leu', async () => {
    const { ultimo } = montar({ agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }) });
    await abrirDetalhe();

    await userEvent.click(screen.getByRole('button', { name: 'Confirmar' }));

    expect(ultimo('/confirmacao')).toMatchObject({
      metodo: 'PATCH', corpo: { versao: 3, pelaCliente: false },
    });
  });

  it('cancelar exige motivo — é ele que sustenta a conversa sobre quem desmarca demais',
    async () => {
      const { ultimo } = montar({ agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }) });
      await abrirDetalhe();
      await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }));

      expect(screen.getByRole('button', { name: 'Cancelar o atendimento' })).toBeDisabled();
      await userEvent.type(screen.getByLabelText('Por que está sendo cancelado'),
        'remarcou para sexta');
      await userEvent.click(screen.getByRole('button', { name: 'Cancelar o atendimento' }));

      expect(ultimo('/cancelamento')).toMatchObject({
        corpo: { versao: 3, motivo: 'remarcou para sexta', pelaCliente: true },
      });
    });

  it('a falta não pede motivo: o motivo dela é a própria ausência', async () => {
    const { ultimo } = montar({ agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }) });
    await abrirDetalhe();

    await userEvent.click(screen.getByRole('button', { name: 'Não veio' }));

    expect(ultimo('/falta')).toMatchObject({ metodo: 'PATCH', corpo: { versao: 3 } });
  });

  it('não há o que fazer com um cancelado', async () => {
    montar({ agenda: agendaDoDia({
      agendamentos: [{ ...AGENDAMENTO, status: 'CANCELADO' }],
    }) });
    await abrirDetalhe();

    expect(screen.queryByRole('button', { name: 'Não veio' })).not.toBeInTheDocument();
  });

  it('quem não escreve vê o detalhe sem ação nenhuma', async () => {
    montar({ permissoes: ['agenda:read:all'],
      agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }) });
    await abrirDetalhe();

    expect(screen.queryByRole('button', { name: 'Confirmar' })).not.toBeInTheDocument();
  });
});

describe('arrastar a tarja', () => {
  it('reagenda para onde soltou, com a versão lida', async () => {
    const { ultimo } = montar({ agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }) });
    const tarja = await screen.findByRole('button', { name: /Marilda Souza/ });

    fireEvent.pointerDown(tarja, { button: 0, pointerId: 1, clientY: 12 * ALTURA_DA_LINHA });
    fireEvent.pointerMove(tarja, { pointerId: 1, clientY: 14 * ALTURA_DA_LINHA });
    fireEvent.pointerUp(tarja, { pointerId: 1, clientY: 14 * ALTURA_DA_LINHA });

    // `fireEvent` é síncrono e a escrita sai num microtask: sem esperar, a asserção chega
    // antes do `fetch`.
    await waitFor(() => expect(ultimo('/horario')).toMatchObject({
      metodo: 'PUT', corpo: { inicio: as(15), versao: 3 },
    }));
  });

  it('soltar no mesmo lugar não mexe em nada', async () => {
    // Um clique é um clique: ele abre o detalhe, e não vale uma escrita.
    const { enviados } = montar({ agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }) });
    const tarja = await screen.findByRole('button', { name: /Marilda Souza/ });

    fireEvent.pointerDown(tarja, { button: 0, pointerId: 1, clientY: 12 * ALTURA_DA_LINHA });
    fireEvent.pointerUp(tarja, { pointerId: 1, clientY: 12 * ALTURA_DA_LINHA });

    // Deixa o microtask da mutação correr. Sem esta espera, o teste passaria mesmo se a
    // escrita tivesse sido disparada — que é exatamente o que ele existe para recusar.
    await new Promise((seguir) => { setTimeout(seguir, 0); });
    expect(enviados).toHaveLength(0);
  });

  it('a recusa aparece com a frase do servidor', async () => {
    montar({ agenda: agendaDoDia({ agendamentos: [AGENDAMENTO] }),
      aoEscrever: { codigo: 'ER-AGD-CONFLITO_HORARIO',
        detail: 'este horário acabou de ser tomado' } });
    const tarja = await screen.findByRole('button', { name: /Marilda Souza/ });

    fireEvent.pointerDown(tarja, { button: 0, pointerId: 1, clientY: 12 * ALTURA_DA_LINHA });
    fireEvent.pointerUp(tarja, { pointerId: 1, clientY: 14 * ALTURA_DA_LINHA });

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('este horário acabou de ser tomado');
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural com o painel do gesto aberto', async () => {
    const { container } = montar();
    await screen.findByText('Ana');
    await arrastarNaPista(12 * ALTURA_DA_LINHA, 14 * ALTURA_DA_LINHA);
    await screen.findByRole('button', { name: 'Marcar atendimento' });

    await deveSerAcessivel(container);
  });
});
