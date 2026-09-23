import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ErroDaApi } from '../../api/http';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Fila } from './Fila';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  ouvirEventos: () => () => {},
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

const candidata = (sobre: Record<string, unknown>) => ({
  esperaId: 'e1', posicao: 1, clienteId: 'c1', cliente: 'Marilda', telefone: '5511987654321',
  servicoId: 's1', servico: 'Corte', aceitaDe: '2026-09-22', aceitaAte: '2026-09-26',
  horaDe: '13:00', horaAte: '18:00', naFilaDesde: '2026-09-12T13:20:00Z',
  horarios: ['2026-09-24T17:00:00Z', '2026-09-24T17:15:00Z'], chamadas: [], exigeMotivo: false,
  ...sobre,
});

const VAGA = {
  id: 'v1', profissionalId: 'ana', profissional: 'Ana',
  inicio: '2026-09-24T17:00:00Z', fim: '2026-09-24T19:00:00Z', origem: 'CANCELAMENTO',
  candidatas: [
    candidata({ chamadas: [{ resultado: 'NAO_ATENDEU', registradaEm: '2026-09-22T13:42:00Z',
      quem: 'Júlia', motivoDoPulo: null }] }),
    candidata({ esperaId: 'e2', posicao: 2, cliente: 'Joana', telefone: null }),
    candidata({ esperaId: 'e3', posicao: 3, cliente: 'Bia', telefone: null, exigeMotivo: true }),
  ],
};

const ESPERA = {
  id: 'e1', clienteId: 'c1', cliente: 'Marilda', servicoId: 's1', servico: 'Corte',
  profissionalId: null, profissional: null, aceitaDe: '2026-09-22', aceitaAte: '2026-09-26',
  horaDe: '13:00', horaAte: '18:00', observacao: null, naFilaDesde: '2026-09-12T13:20:00Z',
  recorrenciaId: null,
};

function montar({ vagas = [VAGA] as unknown[], esperas = [ESPERA] as unknown[],
  aoChamar = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (caminho === '/v1/me/capabilities') {
      return { estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho.endsWith('/chamadas')) {
      if (aoChamar instanceof Error) throw aoChamar;
      return { id: 'ch1' };
    }
    if (caminho === '/v1/vagas-abertas') return vagas;
    if (caminho.startsWith('/v1/fila-de-espera')) return init?.method ? undefined : esperas;
    if (caminho === '/v1/servicos') return [{ id: 's1', nome: 'Corte', ativo: true },
      { id: 's9', nome: 'Antigo', ativo: false }];
    if (caminho === '/v1/profissionais') return [{ id: 'ana', nome: 'Ana Souza', apelido: 'Ana' }];
    if (caminho.startsWith('/v1/clientes')) {
      return { interpretacao: 'NOME', aviso: null, itens: [{ id: 'c9', nome: 'Renata Alves',
        apelido: null, telefone: '5511944441111', email: null, versao: 0 }] };
    }
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={fila}><Fila /></QueryClientProvider>);
}

/** O corpo do último envio para o caminho — só as escritas, as leituras não têm corpo. */
const ultimoEnvio = (sufixo: string) => {
  const envio = chamar.mock.calls
    .filter(([caminho, init]) => String(caminho).endsWith(sufixo) && init?.method)
    .at(-1);
  return envio && { metodo: (envio[1] as RequestInit).method,
    corpo: (envio[1] as RequestInit).body
      ? JSON.parse(String((envio[1] as RequestInit).body)) : undefined };
};

const linhaDe = async (nome: string) =>
  (await screen.findByText(nome, { selector: '.fil-nome' })).closest('li') as HTMLElement;

beforeEach(() => { chamar.mockReset(); });

describe('vagas abertas', () => {
  it('mostra a vaga com quem ligar, numerado na ordem, com telefone e o que já foi tentado',
    async () => {
      montar();

      const marilda = await linhaDe('Marilda');
      expect(within(marilda).getByLabelText('1ª na ordem')).toHaveTextContent('1');
      expect(within(marilda).getByRole('link', { name: '(11) 98765-4321' }))
        .toHaveAttribute('href', 'tel:+5511987654321');
      expect(within(marilda).getByText(/aceita 22\/09 a 26\/09, 13:00–18:00/)).toBeInTheDocument();
      expect(within(marilda).getByText(/Não atendeu · 22\/09, 10:42 · Júlia/)).toBeInTheDocument();
      expect(screen.getByText(/qui\.?, 24\/09 · 14:00–16:00/)).toBeInTheDocument();
      expect(screen.getByText('Cancelamento')).toBeInTheDocument();
    });

  it('recusou, sem pulo, sai num clique', async () => {
    montar();
    const marilda = await linhaDe('Marilda');

    await userEvent.click(within(marilda).getByRole('button', { name: 'Recusou' }));

    expect(ultimoEnvio('/v1/vagas-abertas/v1/chamadas')).toEqual({ metodo: 'POST',
      corpo: { esperaId: 'e1', resultado: 'RECUSOU' } });
  });

  it('aceitou pede o horário, e marca no escolhido', async () => {
    montar();
    const marilda = await linhaDe('Marilda');

    await userEvent.click(within(marilda).getByRole('button', { name: 'Aceitou' }));
    const marcar = within(marilda).getByRole('button', { name: 'Escolha o horário' });
    expect(marcar).toBeDisabled();
    await userEvent.click(within(marilda).getByLabelText('14:15'));
    await userEvent.click(within(marilda).getByRole('button', { name: 'Marcar às 14:15' }));

    expect(ultimoEnvio('/chamadas')?.corpo).toEqual({ esperaId: 'e1', resultado: 'ACEITOU',
      inicio: '2026-09-24T17:15:00Z', motivoDoPulo: null });
  });

  it('quem exige motivo abre o campo antes de registrar — o servidor é quem diz que exige',
    async () => {
      montar();
      const bia = await linhaDe('Bia');

      await userEvent.click(within(bia).getByRole('button', { name: 'Não atendeu' }));
      expect(chamar.mock.calls.some(([caminho]) => String(caminho).endsWith('/chamadas')))
        .toBe(false);
      const registrar = within(bia).getByRole('button', { name: 'Registrar: não atendeu' });
      expect(registrar).toBeDisabled();
      await userEvent.type(within(bia).getByLabelText('Por que chamar fora da ordem?'),
        '  a segunda só pode de manhã ');
      await userEvent.click(registrar);

      expect(ultimoEnvio('/chamadas')?.corpo).toEqual({ esperaId: 'e3',
        resultado: 'NAO_ATENDEU', inicio: null, motivoDoPulo: 'a segunda só pode de manhã' });
    });

  it('a recusa do servidor aparece na linha de quem foi chamada', async () => {
    montar({ aoChamar: new ErroDaApi(409, 'ER-AGD-ESPERA_NAO_CABE',
      'Esta espera não cabe mais nesta vaga') });
    const joana = await linhaDe('Joana');

    await userEvent.click(within(joana).getByRole('button', { name: 'Recusou' }));

    expect(await within(joana).findByRole('alert'))
      .toHaveTextContent('Esta espera não cabe mais nesta vaga');
  });

  it('sem vaga, diz o que vai aparecer ali', async () => {
    montar({ vagas: [] });

    expect(await screen.findByText(/Nenhuma vaga com alguém da fila que caiba/))
      .toBeInTheDocument();
  });
});

describe('na fila', () => {
  it('lista quem espera, com "qualquer uma" e a contagem', async () => {
    montar();

    expect(await screen.findByText('1 pessoa esperando.')).toBeInTheDocument();
    const linha = screen.getByRole('row', { name: /Marilda/ });
    expect(within(linha).getByText('Qualquer uma')).toBeInTheDocument();
    expect(within(linha).getByText('22/09 a 26/09')).toBeInTheDocument();
  });

  it('desistiu pede confirmação e só então sai da fila', async () => {
    montar();
    const linha = await screen.findByRole('row', { name: /Marilda/ });

    await userEvent.click(within(linha).getByRole('button', { name: 'Desistiu' }));
    expect(ultimoEnvio('/v1/fila-de-espera/e1')).toBeUndefined();
    await userEvent.click(within(linha).getByRole('button', { name: 'Confirmar desistência' }));

    expect(ultimoEnvio('/v1/fila-de-espera/e1')?.metodo).toBe('DELETE');
  });

  it('pôr na fila: escolhe a cliente pela busca e envia a janela aceita', async () => {
    montar({ esperas: [] });

    await userEvent.click(await screen.findByRole('button', { name: 'Pôr na fila' }));
    await userEvent.type(screen.getByPlaceholderText('Nome ou telefone'), 'Ren');
    await userEvent.click(await screen.findByRole('button', { name: /Renata Alves/ }));
    expect(screen.queryByRole('option', { name: 'Antigo' })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Serviço'), 's1');
    await userEvent.type(screen.getByLabelText('Aceita de'), '2026-09-24');
    await userEvent.type(screen.getByLabelText('Das'), '13:00');
    await userEvent.type(screen.getByLabelText('Às'), '18:00');
    await userEvent.click(screen.getByRole('button', { name: 'Pôr na fila' }));

    expect(ultimoEnvio('/v1/fila-de-espera')?.corpo).toEqual({
      clienteId: 'c9', servicoId: 's1', profissionalId: null,
      aceitaDe: '2026-09-24', aceitaAte: '2026-09-24',
      horaDe: '13:00', horaAte: '18:00', observacao: null,
    });
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural com vagas e fila na tela', async () => {
    const { container } = montar();
    await screen.findByText('Marilda');

    await deveSerAcessivel(container);
  });
});
