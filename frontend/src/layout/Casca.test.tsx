import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Casca } from './Casca';
import type { Capacidades } from '../api/capacidades';

vi.mock('../api/http', () => ({
  api: vi.fn(async () => capacidadesDeRecepcao),
  ErroDaApi: class extends Error {},
}));

const capacidadesDeRecepcao: Capacidades = {
  usuarioId: 'u1', nome: 'Joana', email: 'joana@salao.test', perfil: 'RECEPCAO',
  estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo', moeda: 'BRL' },
  permissoes: ['agenda:read:all', 'comanda:open'],
  menus: [
    { id: 'agenda', rotulo: 'Agenda', rota: '/agenda', icone: 'calendario' },
    { id: 'clientes', rotulo: 'Clientes', rota: '/clientes', icone: 'pessoas' },
  ],
  flags: { podeVerValorDeOutros: false },
  limites: { descontoMaximoPercentual: 10 },
  mfaAtivo: false, mfaObrigatorio: false,
};

function montar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={cliente}>
      <MemoryRouter><Casca /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('casca', () => {
  it('desenha só os menus que o servidor mandou', async () => {
    // Não existe lista de menus no front. Se o backend parar de devolver o financeiro,
    // ele some daqui sem ninguém tocar neste arquivo — é o que torna verdadeira a
    // promessa de que o front não decide nada (inviolável nº 6).
    montar();

    expect(await screen.findByText('Agenda')).toBeInTheDocument();
    expect(screen.getByText('Clientes')).toBeInTheDocument();
    expect(screen.queryByText('Financeiro')).not.toBeInTheDocument();
    expect(screen.queryByText('Configurações')).not.toBeInTheDocument();
  });

  it('mostra o nome do estabelecimento vindo das capacidades', async () => {
    montar();

    expect(await screen.findByText('Studio Marilda')).toBeInTheDocument();
  });
});
