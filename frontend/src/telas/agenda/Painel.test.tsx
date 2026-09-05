import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Painel } from './Painel';

const AGORA = new Date('2026-09-17T13:24:00Z');
const SAO_PAULO = 'America/Sao_Paulo';

const as = (hora: number, minuto = 0) =>
  new Date(Date.UTC(2026, 8, 17, hora + 3, minuto)).toISOString();

function painel(sobrescrever: Record<string, unknown> = {}) {
  return {
    estabelecimento: 'Studio Marilda',
    fuso: SAO_PAULO,
    atualizadoEm: as(10, 24),
    restantes: 2,
    linhas: [
      { inicio: as(11), cliente: 'Marilda S.', profissional: 'Ana',
        situacao: 'EM_ATENDIMENTO' },
      { inicio: as(14), cliente: 'Josefa A.', profissional: 'Júlia', situacao: 'SOLICITADO' },
    ],
    ...sobrescrever,
  };
}

function montar(resposta: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true, status: 200,
    json: async () => resposta,
    body: null,
    headers: new Headers({ 'content-type': 'application/json' }),
  }));
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={cliente}><Painel /></QueryClientProvider>);
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(AGORA);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('painel do balcão', () => {
  it('mostra as quatro colunas que se leem de longe', async () => {
    montar(painel());

    expect(await screen.findByText('Studio Marilda')).toBeInTheDocument();
    expect(screen.getByText('11:00')).toBeInTheDocument();
    expect(screen.getByText('Marilda S.')).toBeInTheDocument();
    expect(screen.getByText('Ana')).toBeInTheDocument();
    expect(screen.getByText('em atendimento')).toBeInTheDocument();
  });

  it('a situação aparece por extenso, em minúsculas', async () => {
    // "SOLICITADO" em caixa alta é vocabulário de banco. Quem lê da cadeira precisa de
    // português.
    montar(painel());

    expect(await screen.findByText('a confirmar')).toBeInTheDocument();
  });

  it('diz quando foi atualizado e quantos faltam', async () => {
    // Um painel que trava sem avisar é pior que um painel apagado: a recepção confia
    // num dado velho. O carimbo é o que denuncia.
    montar(painel());

    expect(await screen.findByText('atualizado às 10:24')).toBeInTheDocument();
    expect(screen.getByText('2 atendimentos restantes hoje')).toBeInTheDocument();
  });

  it('concorda o singular quando falta um só', async () => {
    montar(painel({ restantes: 1, linhas: [painel().linhas[0]] }));

    expect(await screen.findByText('1 atendimento restante hoje')).toBeInTheDocument();
  });

  it('dia sem nada restante diz isso, em vez de tabela vazia', async () => {
    montar(painel({ linhas: [], restantes: 0 }));

    expect(await screen.findByText('Nenhum atendimento restante hoje.')).toBeInTheDocument();
  });

  it('sem conexão, avisa em vez de mostrar tela em branco', async () => {
    // A internet do salão cai (docs/10-usabilidade). Uma tela branca na parede parece
    // um painel desligado, e ninguém vai conferir.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('rede')));
    const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={cliente}><Painel /></QueryClientProvider>);

    expect(await screen.findByText('Sem conexão com o sistema.')).toBeInTheDocument();
  });
});
