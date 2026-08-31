import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Ficha } from './Ficha';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  ErroDaApi: class extends Error {},
}));

const MARIA = {
  id: 'c1', nome: 'Maria Silva', apelido: null, telefone: '5511987654321', email: null,
};

const SEM_REGISTRO = [
  {
    finalidade: 'LEMBRETE_DE_AGENDAMENTO', baseLegal: 'EXECUCAO_DE_CONTRATO',
    decisao: 'SEM_REGISTRO', podeReceber: true, desde: null, canal: null,
  },
  {
    finalidade: 'MARKETING', baseLegal: 'CONSENTIMENTO',
    decisao: 'SEM_REGISTRO', podeReceber: false, desde: null, canal: null,
  },
];

function servidor(opcoes: { consentimentos?: unknown; duplicados?: unknown; permissoes?: string[] }) {
  chamar.mockImplementation(async (caminho: string) => {
    if (caminho.includes('/consentimentos')) return opcoes.consentimentos ?? [];
    if (caminho.includes('possiveis-duplicados')) return opcoes.duplicados ?? [];
    if (caminho.startsWith('/v1/me/capabilities')) {
      return {
        usuarioId: 'u1', nome: 'Joana', email: 'j@s.test', perfil: 'RECEPCAO',
        estabelecimento: { id: 'e1', nome: 'Studio', timezone: 'America/Sao_Paulo', moeda: 'BRL' },
        permissoes: opcoes.permissoes ?? ['cliente:read', 'cliente:write'],
        menus: [], flags: {}, limites: {}, mfaAtivo: false, mfaObrigatorio: false,
      };
    }
    return undefined;
  });
}

function montar() {
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={fila}>
      <Ficha cliente={MARIA} aoSair={() => {}} />
    </QueryClientProvider>,
  );
}

beforeEach(() => { chamar.mockReset(); });

describe('ficha do cliente', () => {
  it('separa o EFEITO do REGISTRO no consentimento', async () => {
    // RN-CLI-018: "sem registro" não é "não pode". Para o lembrete de horário, sem
    // registro significa PODE, porque a base é execução de contrato. Um interruptor
    // teria dois estados e mentiria sobre o terceiro — mostraria "desligado" para
    // uma cliente que o salão pode e deve avisar.
    servidor({ consentimentos: SEM_REGISTRO });
    montar();

    expect(await screen.findByText('Lembrete de horário')).toBeInTheDocument();
    expect(screen.getByText('pode receber')).toBeInTheDocument();
    expect(screen.getByText(/faz parte do serviço contratado/)).toBeInTheDocument();

    expect(screen.getByText('não recebe')).toBeInTheDocument();
    expect(screen.getByText(/precisa do aceite dela/)).toBeInTheDocument();
  });

  it('nenhum botão fica marcado enquanto não há registro', async () => {
    servidor({ consentimentos: SEM_REGISTRO });
    montar();

    const aceitou = await screen.findAllByRole('button', { name: 'Aceitou' });
    const recusou = screen.getAllByRole('button', { name: 'Recusou' });
    expect(aceitou.every((b) => b.getAttribute('aria-pressed') === 'false')).toBe(true);
    expect(recusou.every((b) => b.getAttribute('aria-pressed') === 'false')).toBe(true);
  });

  it('recusar tem o mesmo peso visual que aceitar', async () => {
    // A LGPD exige que revogar seja tão fácil quanto consentir (art. 8 §5). Um botão
    // menor ou escondido seria a letra cumprida e o espírito não.
    servidor({ consentimentos: SEM_REGISTRO });
    const { container } = montar();

    await screen.findByText('Lembrete de horário');
    const botoes = container.querySelectorAll('.cli-escolha button');
    const classes = new Set([...botoes].map((b) => b.className));
    expect(classes.size).toBe(1);
  });

  it('registra a decisão com o canal escolhido', async () => {
    servidor({ consentimentos: SEM_REGISTRO });
    montar();

    await screen.findByText('Promoções e aniversário');
    await userEvent.selectOptions(screen.getByLabelText('Canal do registro'), 'WHATSAPP');
    await userEvent.click(screen.getAllByRole('button', { name: 'Aceitou' })[1]);

    expect(chamar).toHaveBeenCalledWith(
      '/v1/clientes/c1/consentimentos/MARKETING',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ concedido: true, canal: 'WHATSAPP' }),
      }),
    );
  });

  it('mesmo telefone com nome diferente NÃO é apresentado como duplicidade', async () => {
    // Mãe e filha dividem o número o tempo todo (RN-CLI-005). Tratar família como erro
    // treina a recepção a ignorar o aviso — e aí ele para de servir para o caso em que
    // está certo.
    servidor({
      duplicados: [{
        grau: 'MESMO_TELEFONE',
        cliente: { id: 'c2', nome: 'Júlia Silva', apelido: null,
          telefone: '5511987654321', email: null },
      }],
    });
    const { container } = montar();

    expect(await screen.findByText('Este telefone já está com:')).toBeInTheDocument();
    expect(container.querySelector('.cli-aviso-forte')).toBeNull();

    // E não oferece juntar: um botão ali convidaria a fundir duas pessoas
    // diferentes — o estrago que o domínio inteiro foi desenhado para evitar.
    expect(screen.queryByRole('button', { name: 'Júlia Silva' })).not.toBeInTheDocument();
    expect(screen.queryByText(/juntar as duas fichas/)).not.toBeInTheDocument();
  });

  it('duplicado provável recebe destaque, e juntar exige confirmação', async () => {
    // Juntar não tem desfazer (pendência da RT-CLI-003). A regra do projeto é executar
    // e oferecer desfazer; onde não há desfazer, perguntar antes é o que sobra.
    servidor({
      duplicados: [{
        grau: 'PROVAVEL',
        cliente: { id: 'c2', nome: 'Maria da Silva', apelido: null,
          telefone: '5511987654321', email: null },
      }],
    });
    const { container } = montar();

    expect(await screen.findByText(/parece ser a mesma pessoa/)).toBeInTheDocument();
    expect(container.querySelector('.cli-aviso-forte')).not.toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Maria da Silva' }));
    expect(screen.getByText(/não tem como desfazer/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Juntar fichas' }));
    expect(chamar).toHaveBeenCalledWith('/v1/clientes/c1/mesclar',
      expect.objectContaining({ method: 'POST' }));
  });

  it('sem cliente:anonymize, apagar dados não aparece', async () => {
    // Esconder é organização de tela, não segurança: o servidor devolve 403 de
    // qualquer forma (inviolável nº 6).
    servidor({ consentimentos: SEM_REGISTRO, permissoes: ['cliente:read', 'cliente:write'] });
    montar();

    await screen.findByText('Lembrete de horário');
    expect(screen.queryByText(/Apagar os dados/)).not.toBeInTheDocument();
  });

  it('com a permissão, apagar dados exige confirmação e explica o que sobra', async () => {
    servidor({ consentimentos: SEM_REGISTRO, permissoes: ['cliente:anonymize'] });
    montar();

    await userEvent.click(await screen.findByRole('button',
      { name: 'Apagar os dados a pedido dela' }));

    expect(screen.getByText(/somem para sempre/)).toBeInTheDocument();
    expect(screen.getByText(/histórico financeiro do salão depende dela/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Apagar os dados' }));
    expect(chamar).toHaveBeenCalledWith('/v1/clientes/c1',
      expect.objectContaining({ method: 'DELETE' }));
  });
});
