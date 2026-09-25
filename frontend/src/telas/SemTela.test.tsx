import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../teste/acessibilidade';
import { SemTela } from './SemTela';

const chamar = vi.fn();
vi.mock('../api/http', () => ({
  api: (caminho: string) => chamar(caminho),
  ErroDaApi: class extends Error {},
}));

const MENUS = {
  recepcao: [
    { id: 'atendimento', rotulo: 'Atendimento', rota: '/comandas', icone: 'tesoura',
      grupo: 'operacao' },
    { id: 'estoque', rotulo: 'Estoque', rota: '/estoque', icone: 'caixa', grupo: 'gestao' },
  ],
  dono: [
    { id: 'atendimento', rotulo: 'Atendimento', rota: '/comandas', icone: 'tesoura',
      grupo: 'operacao' },
    { id: 'precos', rotulo: 'Preços', rota: '/precos', icone: 'etiqueta', grupo: 'gestao' },
    { id: 'conciliacao', rotulo: 'Conciliação', rota: '/conciliacao', icone: 'setas',
      grupo: 'gestao' },
  ],
};

function montar(area: 'estoque' | 'financeiro', menus = MENUS.dono) {
  chamar.mockImplementation(async () => ({
    estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus,
  }));
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={fila}><SemTela area={area} /></QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => { chamar.mockReset(); });

describe('a área que ainda não tem tela', () => {
  it('nomeia a rotina da TELA, não a que já foi entregue', async () => {
    // O texto anterior dizia "esta área chega com a rotina RT-EST-001" — e RT-EST-001 estava
    // implementada havia semanas. Quem lia concluía que o estoque inteiro estava por fazer, e
    // ia procurar no lugar errado.
    montar('estoque');

    expect(await screen.findByText(/RT-EST-013/)).toBeInTheDocument();
    expect(screen.queryByText(/RT-EST-001/)).not.toBeInTheDocument();
  });

  it('diz que o servidor já faz, em vez de deixar parecer que a área está por começar', () => {
    montar('financeiro');

    expect(screen.getByRole('heading', { name: /O financeiro funciona/ })).toBeInTheDocument();
    expect(screen.getByText(/entregues e testados no servidor/)).toBeInTheDocument();
  });

  it('oferece o caminho que resolve hoje — e ele leva a uma tela de verdade', async () => {
    // Quase sempre dá para fazer por outro lugar: produto se vende na comanda, e o estoque
    // baixa sozinho no fechamento.
    montar('estoque');

    const caminho = await screen.findByRole('link', { name: 'Vender produto na comanda' });
    expect(caminho).toHaveAttribute('href', '/comandas');
  });

  it('não oferece caminho que esta pessoa não pode abrir', async () => {
    // O menu vem do servidor (inviolável nº 6); um link para tela sem permissão seria um beco.
    montar('estoque', MENUS.recepcao);
    await screen.findByRole('link', { name: 'Vender produto na comanda' });

    expect(screen.queryByRole('link', { name: /sugestão de preço/ })).not.toBeInTheDocument();
  });

  it('sem caminho nenhum, some a coluna inteira em vez de sobrar um título vazio', async () => {
    montar('financeiro', [{ id: 'clientes', rotulo: 'Clientes', rota: '/clientes',
      icone: 'pessoas', grupo: 'operacao' }]);
    await screen.findByRole('heading', { name: /O financeiro funciona/ });

    expect(screen.queryByText('O que dá para fazer hoje')).not.toBeInTheDocument();
    expect(screen.getByText('O que esta tela vai trazer')).toBeInTheDocument();
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural', async () => {
    const { container } = montar('estoque');
    await screen.findByRole('link', { name: 'Vender produto na comanda' });

    await deveSerAcessivel(container);
  });
});
