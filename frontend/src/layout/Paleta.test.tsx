import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../teste/acessibilidade';
import { Paleta } from './Paleta';

const chamar = vi.fn();
const navegar = vi.fn();

vi.mock('../api/http', () => ({
  api: (caminho: string) => chamar(caminho),
  ErroDaApi: class extends Error {},
}));
vi.mock('react-router-dom', () => ({ useNavigate: () => navegar }));

const MENUS = [
  { id: 'agenda', rotulo: 'Agenda', rota: '/agenda', icone: 'calendario', grupo: 'operacao' },
  { id: 'clientes', rotulo: 'Clientes', rota: '/clientes', icone: 'pessoas', grupo: 'operacao' },
  { id: 'config', rotulo: 'Configurações', rota: '/config', icone: 'engrenagem',
    grupo: 'sistema' },
];

const MARILDA = {
  id: 'c1', nome: 'Marilda Souza', apelido: null,
  telefone: '5511987654321', email: null, versao: 0,
};

/** Como o servidor responde a busca de cliente — com a interpretação do termo junto. */
function servidor(resposta: unknown) {
  chamar.mockImplementation(async (caminho: string) => {
    if (caminho.startsWith('/v1/clientes?q=')) return resposta;
    throw new Error(`sem resposta para ${caminho}`);
  });
}

function montar(podeBuscarClientes = true) {
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={fila}>
      <Paleta menus={MENUS} podeBuscarClientes={podeBuscarClientes} aoFechar={() => {}} />
    </QueryClientProvider>,
  );
}

const digitar = (texto: string) => userEvent.type(screen.getByRole('combobox'), texto);

beforeEach(() => { chamar.mockReset(); navegar.mockReset(); });

describe('ir para', () => {
  it('sem termo, oferece as seções que o servidor mandou — e não pergunta nada', () => {
    servidor({ interpretacao: 'NOME', aviso: null, itens: [] });
    montar();

    expect(screen.getAllByRole('option')).toHaveLength(3);
    expect(chamar).not.toHaveBeenCalled();
  });

  it('filtra seção sem acento: com o telefone no ombro ninguém vai atrás do circunflexo',
    async () => {
      servidor({ interpretacao: 'INSUFICIENTE', aviso: 'Informe ao menos 2 letras', itens: [] });
      montar();

      await digitar('configuracoes');

      expect(screen.getByRole('option', { name: /Configurações/ })).toBeInTheDocument();
      expect(screen.getAllByRole('option')).toHaveLength(1);
    });
});

describe('achar uma cliente', () => {
  it('acha pelo nome e abre a ficha dela', async () => {
    // O telefone toca e quem atende está na tela de comandas. O caminho era ir ao menu,
    // abrir clientes, digitar; agora são duas teclas e o nome.
    servidor({ interpretacao: 'NOME', aviso: null, itens: [MARILDA] });
    montar();

    await digitar('marilda');
    await userEvent.click(await screen.findByRole('option', { name: /Marilda Souza/ }));

    expect(navegar).toHaveBeenCalledWith('/clientes?cliente=c1');
  });

  it('mostra o telefone formatado, que é como a recepção confere a pessoa certa', async () => {
    servidor({ interpretacao: 'NOME', aviso: null, itens: [MARILDA] });
    montar();

    await digitar('marilda');

    expect(await screen.findByRole('option', { name: /\(11\) 98765-4321/ })).toBeInTheDocument();
  });

  it('quem não tem a tela de clientes não procura cliente', async () => {
    // Quem decide é o servidor (inviolável nº 6): devolver fichas para quem não pode
    // abri-las seria a mesma porta com outro nome.
    servidor({ interpretacao: 'NOME', aviso: null, itens: [MARILDA] });
    montar(false);

    await digitar('marilda');
    // Passa da pausa de 220ms de propósito: sem esperar, o teste passaria só porque a
    // consulta ainda não teve tempo de sair — que é um jeito de provar coisa nenhuma.
    await new Promise((seguir) => setTimeout(seguir, 400));

    expect(chamar).not.toHaveBeenCalled();
    expect(screen.queryByRole('option', { name: /Marilda/ })).not.toBeInTheDocument();
  });

  it('sem seção que case, não sobra um "Seções" em cima da lista de clientes', async () => {
    // Título de grupo vazio promete uma lista que não existe.
    servidor({ interpretacao: 'NOME', aviso: null, itens: [MARILDA] });
    montar();

    await digitar('marilda');
    await screen.findByRole('option', { name: /Marilda Souza/ });

    expect(screen.queryByRole('group', { name: 'Seções' })).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Clientes' })).toBeInTheDocument();
  });

  it('a seta desce das seções para os clientes, e o Enter abre a marcada', async () => {
    // Um índice por grupo seria reinventar o que todo mundo já tem no dedo.
    servidor({ interpretacao: 'NOME', aviso: null, itens: [MARILDA] });
    montar();

    await digitar('c');
    await screen.findByRole('option', { name: /Marilda Souza/ });
    // "Clientes" e "Configurações" casam com o "c"; a terceira opção é a cliente.
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    expect(navegar).toHaveBeenCalledWith('/clientes?cliente=c1');
  });

  it('o servidor diz que leu o termo como telefone, e a lista repete isso', async () => {
    // Reimplementar "dígito significa telefone" (RN-CLI-009) aqui criaria uma segunda
    // cópia da regra, que um dia diria uma coisa enquanto a consulta fazia outra.
    servidor({ interpretacao: 'TELEFONE', aviso: null, itens: [MARILDA] });
    montar();

    await digitar('4321');

    expect(await screen.findByRole('group', { name: 'Clientes · pelo telefone' }))
      .toBeInTheDocument();
  });

  it('o aviso do servidor ocupa o lugar do vazio, com as palavras dele', async () => {
    servidor({ interpretacao: 'INSUFICIENTE', aviso: 'Informe ao menos 2 letras', itens: [] });
    montar();

    await digitar('zz');

    expect(await screen.findByText('Informe ao menos 2 letras')).toBeInTheDocument();
  });

  it('nada em lugar nenhum diz onde a busca ainda não vai', async () => {
    servidor({ interpretacao: 'NOME', aviso: null, itens: [] });
    montar();

    await digitar('zzz');

    expect(await screen.findByText(/Comanda ainda se procura dentro da própria tela/))
      .toBeInTheDocument();
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: as duas listas juntas', async () => {
    servidor({ interpretacao: 'NOME', aviso: null, itens: [MARILDA] });
    const { container } = montar();

    await digitar('c');
    await screen.findByRole('option', { name: /Marilda Souza/ });

    await deveSerAcessivel(container);
  });
});
