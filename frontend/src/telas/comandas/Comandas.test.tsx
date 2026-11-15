import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
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
      return [
        { id: 'p1', nome: 'Shampoo', precoVenda: 45,
          codigoBarras: '7891234567895', vendavel: true },
        { id: 'p2', nome: 'Máscara', precoVenda: 60, codigoBarras: null, vendavel: true },
      ];
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

  it('bipar o código escolhe o produto, e lançar continua sendo do humano', async () => {
    // RT-EST-009. O bipe seleciona; o botão lança. Lançar no bipe seria mais rápido e
    // erraria mais: o lançamento dá baixa FEFO no estoque, e desfazer custa um estorno.
    servidor();
    montar();

    const leitor = await screen.findByLabelText('Bipar produto');
    await userEvent.type(leitor, '7891234567895{Enter}');

    expect(screen.getByLabelText('Produto')).toHaveValue('p1');
    expect(leitor).toHaveValue('');
    expect(chamar).not.toHaveBeenCalledWith(
      expect.stringContaining('/itens/produto'), expect.anything(),
    );
  });

  it('código que não é de nenhum produto do balcão não seleciona nada e diz qual foi', async () => {
    // Sem o código no aviso, quem bipou não sabe se leu errado ou se o produto não tem
    // código cadastrado — e a saída de cada caso é diferente.
    servidor();
    montar();

    const leitor = await screen.findByLabelText('Bipar produto');
    // Com um produto JÁ escolhido por um bipe anterior: o segundo bipe, que falhou, tem de
    // desarmar o botão. Sem isso, "Lançar produto" lançaria o item do bipe de antes.
    await userEvent.type(leitor, '7891234567895{Enter}');
    expect(screen.getByLabelText('Produto')).toHaveValue('p1');

    await userEvent.type(leitor, '0000000000000{Enter}');

    expect(screen.getByRole('alert')).toHaveTextContent('0000000000000');
    expect(screen.getByLabelText('Produto')).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Lançar produto' })).toBeDisabled();
  });

  it('não deixa fechar enquanto falta receber', async () => {
    servidor();
    montar();

    const fechar = await screen.findByRole('button', { name: 'Fechar comanda' });
    expect(fechar).toBeDisabled();
  });

  it('o botão desarmado diz por quê, com o valor que veio do servidor', async () => {
    // RT-ATD-009: botão cinza sem motivo é a pergunta que a recepção faz ao gerente. O motivo
    // fica ligado ao botão, e é lido junto com ele por quem usa leitor de tela.
    servidor();
    montar();

    const fechar = await screen.findByRole('button', { name: 'Fechar comanda' });
    // `\s`: o Intl separa "R$" do número com espaço não separável, que impede a quebra de linha.
    expect(fechar).toHaveAccessibleDescription(/^Falta receber R\$\s90,00 para fechar\.$/);
  });

  it('a forma de pagamento é um toque: rádio, com Dinheiro como padrão', async () => {
    servidor();
    montar();
    await screen.findByRole('region', { name: 'Comanda 42' });

    expect(screen.getByRole('radio', { name: 'Dinheiro' })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: 'Pix' }));
    await userEvent.type(screen.getByLabelText('Valor'), '50');
    await userEvent.click(screen.getByRole('button', { name: 'Registrar' }));

    await waitFor(() => {
      expect(chamar).toHaveBeenCalledWith('/v1/comandas/k1/pagamentos', expect.objectContaining({
        body: JSON.stringify({ forma: 'PIX', valor: 50 }),
      }));
    });
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

describe('o que chega, e o que ainda não chegou', () => {
  it('o item lançado aparece com a resposta da ação, sem reler a comanda', async () => {
    // Até 22/09/2026 cada clique custava duas idas ao servidor: a da ação e a da releitura.
    // Com a cliente na frente, é isso que se sente como travado.
    servidor({
      '/v1/comandas/k1/itens': {
        comanda: { ...COMANDA, valorBruto: dinheiro(150), valorLiquido: dinheiro(150) },
        // Nome diferente do serviço do cardápio: "Escova" apareceria também no select, e
        // a busca por texto casaria com os dois.
        itens: [ITEM, { ...ITEM, id: 'i2', tipo: 'SERVICO', descricao: 'Escova modelada',
          quantidade: 1, precoUnitario: dinheiro(60), valorTotal: dinheiro(60) }],
      },
    });
    montar();
    await screen.findByText('Shampoo');
    // O cardápio chega depois da comanda; sem esperar por ele o select ainda está vazio.
    await screen.findByRole('option', { name: 'Escova' });
    const leiturasAntes = chamar.mock.calls.filter(([c]) => c === '/v1/comandas/k1').length;

    await userEvent.selectOptions(screen.getByLabelText('Serviço'), 's1');
    await userEvent.selectOptions(screen.getByLabelText('Quem executou'), 'pr1');
    await userEvent.click(screen.getByRole('button', { name: 'Lançar serviço' }));

    expect(await screen.findByText('Escova modelada')).toBeInTheDocument();
    // Bruto e total mostram o mesmo valor (sem desconto): dois elementos, um número.
    expect(screen.getAllByText('R$ 150,00').length).toBeGreaterThan(0);
    expect(chamar.mock.calls.filter(([c]) => c === '/v1/comandas/k1').length)
      .toBe(leiturasAntes);
  });

  it('dois cliques em Registrar cobram uma vez só', async () => {
    // O campo de valor se limpa no envio, mas o envio cai no `restante` quando ele está
    // vazio — e o restante ainda é o antigo, porque a conta só é relida depois. Sem
    // desarmar o botão, o segundo clique registrava o MESMO pagamento de novo: dinheiro
    // cobrado em dobro, com a cliente na frente.
    let liberar = () => {};
    servidor();
    const respostaDoServidor = chamar.getMockImplementation()!;
    chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
      if (caminho === '/v1/comandas/k1/pagamentos') {
        await new Promise<void>((resolve) => { liberar = resolve; });
        return { comanda: COMANDA, pagamentos: [], totalPago: dinheiro(90),
          restante: dinheiro(0) };
      }
      return respostaDoServidor(caminho, init);
    });
    montar();
    await screen.findByText('Falta receber');

    const botao = screen.getByRole('button', { name: 'Registrar' });
    await userEvent.click(botao);
    await waitFor(() => expect(botao).toBeDisabled());
    await userEvent.click(botao);

    expect(chamar.mock.calls.filter(([c]) => c === '/v1/comandas/k1/pagamentos'))
      .toHaveLength(1);
    liberar();
    // O "falta receber" vem da resposta do próprio pagamento — sem reler a conta. Note que
    // o servidor falso continua devolvendo 90 na leitura: se a tela relesse, voltaria a 90.
    await waitFor(() => expect(document.querySelector('.cmd-restante-valor'))
      .toHaveTextContent('R$ 0,00'));
    expect(chamar.mock.calls.filter(([c]) => c === '/v1/comandas/k1/pagamentos'))
      .toHaveLength(1);
  });

  it('a metade que ainda não chegou fica marcada, em vez de mostrar número velho calado',
    async () => {
      // A conta é relida depois do lançamento. Enquanto não chega, ela continua legível —
      // esmaecida e sem clique, como o dia anterior na agenda. Aqui ela fica presa no ar de
      // propósito, para o teste ver o estado que o balcão vê.
      let liberarConta = () => {};
      let segurar = false;
      servidor({ '/v1/comandas/k1/itens': { comanda: COMANDA, itens: [ITEM] } });
      const respostaDoServidor = chamar.getMockImplementation()!;
      chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
        if (caminho === '/v1/comandas/k1/conta' && segurar) {
          await new Promise<void>((resolve) => { liberarConta = resolve; });
        }
        return respostaDoServidor(caminho, init);
      });
      montar();
      await screen.findByText('Shampoo');
      await screen.findByRole('option', { name: 'Escova' });
      const contaDe = () => document.querySelectorAll('.cmd-metade')[1];
      expect(contaDe()).toHaveAttribute('aria-busy', 'false');

      segurar = true;
      await userEvent.selectOptions(screen.getByLabelText('Serviço'), 's1');
      await userEvent.selectOptions(screen.getByLabelText('Quem executou'), 'pr1');
      await userEvent.click(screen.getByRole('button', { name: 'Lançar serviço' }));

      await waitFor(() => expect(contaDe()).toHaveAttribute('aria-busy', 'true'));
      expect(screen.getByText('Falta receber')).toBeInTheDocument();

      segurar = false;
      liberarConta();
      await waitFor(() => expect(contaDe()).toHaveAttribute('aria-busy', 'false'));
    });
});

describe('no toque, lançar fica atrás de um botão (RT-ATD-010)', () => {
  it('com itens, começa recolhido, diz o estado e alterna', async () => {
    // No celular o formulário ocupava a tela antes dos itens. Recolhido, a conta vem primeiro.
    servidor();
    montar();

    const botao = await screen.findByRole('button', { name: 'Adicionar à comanda' });
    expect(botao).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(botao);
    expect(botao).toHaveAttribute('aria-expanded', 'true');
    // Recolher é só CSS: o formulário continua montado e não perde o que se escolheu.
    expect(screen.getByRole('button', { name: 'Lançar serviço' })).toBeInTheDocument();
  });

  it('comanda vazia já abre com o formulário à vista', async () => {
    // Sem conta para mostrar, o próximo passo é lançar — recolher seria um toque a mais.
    servidor({
      '/v1/comandas/k1/conta': {
        comanda: COMANDA, pagamentos: [], totalPago: dinheiro(0), restante: dinheiro(0),
      },
      '/v1/comandas/k1': { comanda: COMANDA, itens: [] },
    });
    montar();

    const botao = await screen.findByRole('button', { name: 'Adicionar à comanda' });
    expect(botao).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: a comanda aberta, com itens e pagamento', async () => {
    servidor();
    const { container } = montar();
    await screen.findByText('Shampoo');

    await deveSerAcessivel(container);
  });
});
