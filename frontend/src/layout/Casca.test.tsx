import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Casca } from './Casca';
import type { Capacidades } from '../api/capacidades';
import { api } from '../api/http';

vi.mock('../api/http', () => ({
  api: vi.fn(async () => capacidadesDeRecepcao),
  ErroDaApi: class extends Error {},
}));

const capacidadesDeRecepcao: Capacidades = {
  usuarioId: 'u1', nome: 'Joana', email: 'joana@salao.test', perfil: 'RECEPCAO',
  estabelecimento: { id: 'e1', nome: 'Studio Marilda', timezone: 'America/Sao_Paulo', moeda: 'BRL' },
  permissoes: ['agenda:read:all', 'comanda:open'],
  grupos: [
    { id: 'operacao', rotulo: 'Operação' },
    { id: 'gestao', rotulo: 'Gestão' },
    { id: 'sistema', rotulo: 'Sistema' },
  ],
  menus: [
    { id: 'agenda', rotulo: 'Agenda', rota: '/agenda', icone: 'calendario',
      grupo: 'operacao' },
    { id: 'clientes', rotulo: 'Clientes', rota: '/clientes', icone: 'pessoas',
      grupo: 'operacao' },
  ],
  flags: { podeVerValorDeOutros: false },
  limites: { descontoMaximoPercentual: 10 },
  mfaAtivo: false, mfaObrigatorio: false,
};

function montar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={cliente}>
      <MemoryRouter initialEntries={['/agenda']}><Casca /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('casca', () => {
  it('desenha só os menus que o servidor mandou', async () => {
    // Não existe lista de menus no front. Se o backend parar de devolver o financeiro,
    // ele some daqui sem ninguém tocar neste arquivo — é o que torna verdadeira a
    // promessa de que o front não decide nada (inviolável nº 6).
    montar();

    expect(await screen.findAllByText('Agenda')).toHaveLength(2);
    expect(screen.getByText('Clientes')).toBeInTheDocument();
    expect(screen.queryByText('Financeiro')).not.toBeInTheDocument();
    expect(screen.queryByText('Configurações')).not.toBeInTheDocument();
  });

  it('mostra o nome do estabelecimento vindo das capacidades', async () => {
    montar();

    expect(await screen.findByText('Studio Marilda')).toBeInTheDocument();
  });

  it('abre e fecha a navegação móvel sem perder os menus do servidor', async () => {
    montar();
    await screen.findByText('Studio Marilda');

    const abrir = screen.getByRole('button', { name: 'Abrir menu' });
    fireEvent.click(abrir);
    expect(abrir).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('complementary', { name: 'Navegação' })).toHaveClass('aberta');

    fireEvent.click(screen.getByRole('button', { name: 'Fechar menu' }));
    expect(abrir).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(abrir);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(abrir).toHaveAttribute('aria-expanded', 'false');
    expect(abrir).toHaveFocus();
  });

  it('desenha os grupos que o servidor mandou, e nenhum grupo vazio', async () => {
    // Agrupar no front recriaria no JavaScript a regra que /me/capabilities existe para
    // eliminar. E "Gestão" e "Sistema" chegaram na lista de grupos: não aparecem porque
    // esta recepção não tem menu nenhum dentro deles, não porque o front os conheça.
    montar();
    await screen.findByText('Studio Marilda');

    expect(screen.getByRole('heading', { name: 'Operação' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Gestão' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sistema' })).not.toBeInTheDocument();
  });

  it('a busca ⌘K navega e só oferece o que o servidor mandou', async () => {
    montar();
    await screen.findByText('Studio Marilda');

    fireEvent.keyDown(document, { key: 'k', metaKey: true });
    const caixa = screen.getByRole('combobox', { name: 'Ir para' });

    fireEvent.change(caixa, { target: { value: 'financ' } });
    expect(screen.queryByRole('option')).not.toBeInTheDocument();

    // Sem acento: quem digita com o telefone no ombro não vai atrás do circunflexo.
    fireEvent.change(caixa, { target: { value: 'operacao' } });
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
    fireEvent.change(caixa, { target: { value: 'clien' } });
    expect(screen.getByRole('option', { name: /Clientes/ })).toBeInTheDocument();

    fireEvent.keyDown(caixa, { key: 'Escape' });
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });

  it('lembra a lateral recolhida entre sessões', async () => {
    // Preferência de quem olha a tela, não permissão — é a única decisão de navegação
    // que pertence ao front.
    montar();
    await screen.findByText('Studio Marilda');

    fireEvent.click(screen.getByRole('button', { name: 'Recolher menu' }));

    expect(localStorage.getItem('salao:lateral-recolhida')).toBe('sim');
    expect(screen.getByRole('button', { name: 'Expandir menu' })).toBeInTheDocument();
  });

  it('desenha a lateral mesmo com um servidor que não conhece grupos', async () => {
    // Foi assim que a tela inteira ficou branca: o front leu `capacidades.grupos` de um
    // backend anterior ao campo existir, `undefined.map` estourou e o React desmontou a
    // árvore toda — agenda, comandas e financeiro derrubados por causa de um menu.
    //
    // Front e back saem de versão o tempo todo: durante um deploy, com uma aba velha
    // aberta, com o service worker servindo o bundle de ontem.
    const { grupos: _, ...semGrupos } = capacidadesDeRecepcao;
    vi.mocked(api).mockResolvedValueOnce(semGrupos as never);

    montar();

    expect(await screen.findByText('Studio Marilda')).toBeInTheDocument();
    expect(screen.getAllByText('Agenda').length).toBeGreaterThan(0);
    expect(screen.getByText('Clientes')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Operação' })).not.toBeInTheDocument();
  });

  it('um menu de grupo desconhecido aparece, em vez de sumir em silêncio', async () => {
    // Seção inteira do sistema desaparecendo sem erro nenhum é pior do que seção fora de
    // ordem — ninguém procura o que não sabe que existe.
    vi.mocked(api).mockResolvedValueOnce({
      ...capacidadesDeRecepcao,
      menus: [{ id: 'novo', rotulo: 'Novidade', rota: '/novo', icone: 'caixa',
        grupo: 'grupo-que-o-front-nao-conhece' }],
    } as never);

    montar();

    expect(await screen.findByText('Novidade')).toBeInTheDocument();
  });

  it('encerra a sessão pelo backend antes de voltar ao login', async () => {
    montar();
    await screen.findByText('Studio Marilda');

    fireEvent.click(screen.getByRole('button', { name: /Joana/ }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sair da conta' }));

    await waitFor(() => expect(vi.mocked(api)).toHaveBeenCalledWith(
      '/v1/auth/logout', { method: 'POST' },
    ));
  });
});
