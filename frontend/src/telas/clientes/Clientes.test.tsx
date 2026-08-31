import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Clientes } from './Clientes';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  ErroDaApi: class extends Error {},
}));

const MARIA = {
  id: 'c1', nome: 'Maria Silva', apelido: null,
  telefone: '5511987654321', email: null,
};
const JULIA = {
  id: 'c2', nome: 'Júlia Silva', apelido: 'Ju',
  telefone: '5511987654321', email: null,
};

const CAPACIDADES = {
  usuarioId: 'u1', nome: 'Joana', email: 'j@s.test', perfil: 'RECEPCAO',
  estabelecimento: { id: 'e1', nome: 'Studio', timezone: 'America/Sao_Paulo', moeda: 'BRL' },
  permissoes: ['cliente:read', 'cliente:write'],
  menus: [], flags: {}, limites: {}, mfaAtivo: false, mfaObrigatorio: false,
};

/** Responde como o servidor responderia, incluindo a interpretação do termo. */
function servidor(respostas: Record<string, unknown>) {
  chamar.mockImplementation(async (caminho: string) => {
    for (const [prefixo, resposta] of Object.entries(respostas)) {
      if (caminho.startsWith(prefixo)) return resposta;
    }
    if (caminho.startsWith('/v1/me/capabilities')) return CAPACIDADES;
    if (caminho.includes('/consentimentos')) return [];
    if (caminho.includes('possiveis-duplicados')) return [];
    throw new Error(`sem resposta para ${caminho}`);
  });
}

function montar() {
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={fila}><Clientes /></QueryClientProvider>);
}

beforeEach(() => { chamar.mockReset(); });

describe('tela de clientes', () => {
  it('abre ensinando como buscar, e sem pedir nada ao servidor', async () => {
    // Não existe endpoint que liste todos os clientes (RT-CLI-002), então o estado
    // vazio é o PADRÃO desta tela, não uma exceção. Ele precisa convidar à ação.
    servidor({});
    montar();

    expect(await screen.findByText(/Procure quem está ligando/)).toBeInTheDocument();
    expect(chamar).not.toHaveBeenCalledWith(
      expect.stringContaining('/v1/clientes?q='), expect.anything(),
    );
  });

  it('mostra o que o SERVIDOR entendeu do termo, não um palpite do front', async () => {
    // A regra "dígito significa telefone" (RN-CLI-009) vive no domínio. Se o front a
    // reimplementasse, a cópia divergiria e a tela diria uma coisa enquanto a consulta
    // fazia outra. Aqui a resposta manda interpretação TELEFONE mesmo para um termo
    // de letras — e a tela obedece, provando que não decide sozinha.
    servidor({ '/v1/clientes?q=': { interpretacao: 'TELEFONE', aviso: null, itens: [MARIA] } });
    montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), 'maria');

    expect(await screen.findByText(/últimos dígitos do telefone/)).toBeInTheDocument();
  });

  it('conta as fichas encontradas', async () => {
    servidor({ '/v1/clientes?q=': { interpretacao: 'NOME', aviso: null, itens: [MARIA, JULIA] } });
    montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), 'silva');

    expect(await screen.findByText('2 fichas')).toBeInTheDocument();
  });

  it('usa o texto do servidor para dizer o que falta no termo', async () => {
    // "informe ao menos 2 letras" é texto do domínio. Reescrevê-lo aqui criaria uma
    // segunda versão da mesma regra.
    servidor({
      '/v1/clientes?q=': {
        interpretacao: 'INSUFICIENTE', aviso: 'informe ao menos 2 letras', itens: [],
      },
    });
    montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), 'j');

    expect(await screen.findAllByText('informe ao menos 2 letras')).not.toHaveLength(0);
  });

  it('destaca os dígitos que casaram, que é onde o olho confere', async () => {
    // Quem busca "4321" está olhando para o identificador de chamadas enquanto lê a
    // tela. Destacar o trecho poupa a releitura do número inteiro.
    servidor({ '/v1/clientes?q=': { interpretacao: 'TELEFONE', aviso: null, itens: [MARIA] } });
    const { container } = montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), '4321');
    await screen.findByText('Maria Silva');

    expect(container.querySelector('.cli-item-fone b')?.textContent).toBe('4321');
  });

  it('mostra o apelido como nome principal, e o de documento só quando difere', async () => {
    // "Dona Zilda" é o que a recepção procura; o nome do documento raramente é.
    servidor({ '/v1/clientes?q=': { interpretacao: 'NOME', aviso: null, itens: [JULIA] } });
    montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), 'ju');

    expect(await screen.findByText('Ju')).toBeInTheDocument();
    expect(screen.getByText('Júlia Silva')).toBeInTheDocument();
  });

  it('oferece cadastro já preenchido quando não acha ninguém', async () => {
    // Ela procurou "Marcia", não achou, e vai cadastrar Marcia. Pedir que digite de
    // novo é a fricção que faz o sistema virar caderno (R-UX-04).
    servidor({ '/v1/clientes?q=': { interpretacao: 'NOME', aviso: null, itens: [] } });
    montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), 'Marcia');

    expect(await screen.findByRole('button', { name: 'Cadastrar' })).toBeInTheDocument();
    expect(screen.getByLabelText(/^Nome$/)).toHaveValue('Marcia');
  });

  it('quando o termo era telefone, o cadastro preenche o campo de telefone', async () => {
    // Qual campo recebe o termo também vem da interpretação do servidor.
    servidor({ '/v1/clientes?q=': { interpretacao: 'TELEFONE', aviso: null, itens: [] } });
    montar();

    await userEvent.type(screen.getByLabelText('Buscar cliente'), '11987654321');

    expect(await screen.findByLabelText(/Telefone/)).toHaveValue('11987654321');
    expect(screen.getByLabelText(/^Nome$/)).toHaveValue('');
  });

  it('Esc limpa a busca sem tirar a mão do teclado', async () => {
    servidor({ '/v1/clientes?q=': { interpretacao: 'NOME', aviso: null, itens: [MARIA] } });
    montar();

    const campo = screen.getByLabelText('Buscar cliente');
    await userEvent.type(campo, 'maria');
    await userEvent.keyboard('{Escape}');

    expect(campo).toHaveValue('');
    expect(await screen.findByText(/Procure quem está ligando/)).toBeInTheDocument();
  });
});
