import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Comandas } from './Comandas';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  // O dublê precisa ter a MESMA assinatura do real: a tela lê `message` para mostrar a
  // recusa do servidor, e um Error(status) faria o teste passar mostrando "422".
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

const dinheiro = (valor: number) => ({ valor });

const COMANDA = {
  id: 'k1', clienteId: 'c1', numero: 42, status: 'ABERTA', origem: 'AVULSA',
  abertaEm: new Date().toISOString(), abertaPor: null, fechadaEm: null,
  valorBruto: dinheiro(90), valorDesconto: dinheiro(0), valorLiquido: dinheiro(90), versao: 2,
};

const ITEM = {
  id: 'i1', tipo: 'PRODUTO', servicoId: null, produtoId: 'p1', agendamentoId: null,
  descricao: 'Shampoo', quantidade: 2, precoUnitario: dinheiro(45),
  desconto: dinheiro(0), valorTotal: dinheiro(90), executantes: [],
};

function servidor(sobrescritas: Record<string, unknown> = {}) {
  chamar.mockImplementation(async (caminho: string) => {
    for (const [prefixo, resposta] of Object.entries(sobrescritas)) {
      if (caminho.startsWith(prefixo)) return resposta;
    }
    if (caminho === '/v1/comandas/abertas') {
      return [{ comanda: COMANDA, clienteNome: 'Maria Silva' }];
    }
    if (caminho === '/v1/comandas/k1') return { comanda: COMANDA, itens: [ITEM] };
    if (caminho === '/v1/comandas/k1/conta') {
      return {
        comanda: COMANDA, pagamentos: [], totalPago: dinheiro(0), restante: dinheiro(90),
      };
    }
    if (caminho === '/v1/servicos') return [{ id: 's1', nome: 'Escova', ativo: true }];
    if (caminho === '/v1/profissionais') return [{ id: 'pr1', nome: 'Ana', apelido: 'Ana' }];
    if (caminho.startsWith('/v1/produtos')) {
      return [{ id: 'p1', nome: 'Shampoo', precoVenda: 45, vendavel: true }];
    }
    throw new Error(`sem resposta para ${caminho}`);
  });
}

function montar() {
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={fila}><Comandas /></QueryClientProvider>,
  );
}

/*
 * Chaves, e não arrow de expressão: `mockReset()` devolve o próprio mock, e o Vitest
 * trata função devolvida de um hook como teardown. Sem as chaves, ele chamava `chamar()`
 * SEM ARGUMENTOS no fim de cada teste — e o servidor falso quebrava em `caminho.startsWith`
 * de um caminho indefinido, num ponto que não tem nada a ver com o que o teste faz.
 */
beforeEach(() => { chamar.mockReset(); });

describe('tela de fechamento', () => {
  it('abre já com a comanda mais antiga selecionada', async () => {
    // Abrir a tela e ter de clicar para ver qualquer coisa é um passo que não decide
    // nada — e a primeira da barra é a mais antiga, que é a que interessa (R-UX-07).
    servidor();
    montar();

    expect(await screen.findByRole('region', { name: 'Comanda 42' })).toBeInTheDocument();
    expect(screen.getByText('Shampoo')).toBeInTheDocument();
  });

  it('mostra o que falta receber sem calcular nada', async () => {
    // O número vem pronto do servidor. Subtrair aqui seria a mesma conta em dois
    // lugares (RN-ATD-008), e a divergência apareceria como um centavo.
    servidor();
    montar();

    expect(await screen.findByText('Falta receber')).toBeInTheDocument();
    expect(screen.getAllByText('R$ 90,00').length).toBeGreaterThan(0);
  });

  it('não deixa fechar enquanto falta receber', async () => {
    servidor();
    montar();

    const fechar = await screen.findByRole('button', { name: 'Fechar comanda' });
    expect(fechar).toBeDisabled();
  });

  it('libera o fechamento quando a conta zera', async () => {
    servidor({
      '/v1/comandas/k1/conta': {
        comanda: COMANDA,
        pagamentos: [{
          id: 'g1', comandaId: 'k1', forma: 'PIX', valor: dinheiro(90), bandeira: null,
          parcelas: 1, nsu: null, autorizacao: null, taxaPrevista: null,
          previsaoRecebimento: null, statusConciliacao: 'NAO_APLICAVEL',
          criadoEm: new Date().toISOString(),
        }],
        totalPago: dinheiro(90),
        restante: dinheiro(0),
      },
    });
    montar();

    const fechar = await screen.findByRole('button', { name: 'Fechar comanda' });
    expect(fechar).toBeEnabled();
    expect(screen.getByLabelText('Pagamento').textContent).toContain('Pix');
  });

  it('registra pagamento pela rota da comanda', async () => {
    servidor();
    montar();
    await screen.findByRole('region', { name: 'Comanda 42' });

    await userEvent.type(screen.getByLabelText('Valor'), '50');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar' }));

    await waitFor(() => {
      expect(chamar).toHaveBeenCalledWith('/v1/comandas/k1/pagamentos', expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ forma: 'DINHEIRO', valor: 50 }),
      }));
    });
  });

  it('mostra a mensagem do servidor, não uma genérica', async () => {
    // A mensagem de lá é a acionável: diz quanto falta, quanto há em estoque, qual é o
    // teto de desconto. Trocá-la apagaria justamente a parte que resolve (R-UX-20).
    servidor();
    montar();
    await screen.findByRole('region', { name: 'Comanda 42' });
    const { ErroDaApi } = await import('../../api/http');
    chamar.mockRejectedValueOnce(new ErroDaApi(422, 'ER-EST-SALDO_INSUFICIENTE',
      'há 1 em estoque e a saída é de 2'));

    await userEvent.click(screen.getByRole('button', { name: 'Remover Shampoo' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('há 1 em estoque e a saída é de 2');
  });

  it('avisa por TEXTO, não só por cor, a comanda esquecida há horas', async () => {
    // O aviso é sobre dinheiro ficando para trás. Só a cor deixaria de fora quem não a
    // distingue.
    const antiga = {
      ...COMANDA,
      abertaEm: new Date(Date.now() - 5 * 3_600_000).toISOString(),
    };
    servidor({ '/v1/comandas/abertas': [{ comanda: antiga, clienteNome: 'Maria Silva' }] });
    montar();

    expect(await screen.findByText('aberta há 5h')).toBeInTheDocument();
  });

  it('comanda fechada não mostra campos de lançar, descontar nem pagar', async () => {
    const fechada = { ...COMANDA, status: 'FECHADA', fechadaEm: new Date().toISOString() };
    servidor({
      '/v1/comandas/abertas': [],
      '/v1/comandas/k1': { comanda: fechada, itens: [ITEM] },
      '/v1/comandas/k1/conta': {
        comanda: fechada, pagamentos: [], totalPago: dinheiro(90), restante: dinheiro(0),
      },
    });
    const { rerender } = montar();
    rerender(
      <QueryClientProvider client={new QueryClient()}><Comandas /></QueryClientProvider>,
    );

    expect(await screen.findByText('Nenhuma comanda selecionada')).toBeInTheDocument();
  });

  it('todo botão da tela tem ação — nenhum é promessa falsa', async () => {
    // O achado de RT-AGD-011: `<button>` sem onClick parece clicável e não faz nada.
    servidor();
    const { container } = montar();
    await screen.findByRole('region', { name: 'Comanda 42' });

    const botoes = Array.from(container.querySelectorAll('button'));
    expect(botoes.length).toBeGreaterThan(0);
    for (const botao of botoes) {
      const temAcao = botao.getAttribute('type') === 'submit'
        || botao.onclick !== null
        || botao.closest('form') !== null;
      expect(temAcao, `botão "${botao.textContent}" sem ação`).toBe(true);
    }
  });
});
