import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Acessos } from './Acessos';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  // Mesma assinatura do real: a tela mostra `message`, que é a frase do servidor —
  // "Este e-mail já está em uso." diz o que fazer.
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

const EU = 'u1';

const MARILDA = {
  id: EU, nome: 'Marilda Souza', email: 'marilda@salao.test', telefones: [],
  perfil: 'ADMIN', ativo: true, mfaAtivo: true, ultimoAcessoEm: new Date().toISOString(),
};

const ANA = {
  id: 'u2', nome: 'Ana Ribeiro', email: 'ana@salao.test',
  telefones: ['5511987654321', '5511933332222'],
  perfil: 'GERENTE', ativo: true, mfaAtivo: false,
  ultimoAcessoEm: new Date(Date.now() - 3 * 86_400_000).toISOString(),
};

const BIA = {
  id: 'u3', nome: 'Bia Nogueira', email: 'bia@salao.test', telefones: [],
  perfil: 'RECEPCAO', ativo: true, mfaAtivo: false, ultimoAcessoEm: null,
};

const PERFIS = [
  { perfil: 'ADMIN', rotulo: 'Administradora', exigeMfa: true,
    resumo: 'A dona do salão.', podeFazer: ['criar acesso e mudar perfil'] },
  { perfil: 'GERENTE', rotulo: 'Gerente', exigeMfa: true,
    resumo: 'Opera o dia e vê o dinheiro.', podeFazer: ['ver o dinheiro do salão inteiro'] },
  { perfil: 'RECEPCAO', rotulo: 'Recepção', exigeMfa: false,
    resumo: 'O balcão.', podeFazer: ['dar desconto de até 10%'] },
];

function montar({ pessoas = [MARILDA, ANA, BIA] as unknown[],
  aoEscrever = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method && aoEscrever instanceof Error) throw aoEscrever;
    if (caminho === '/v1/me/capabilities') {
      return { usuarioId: EU, nome: 'Marilda', email: 'marilda@salao.test', perfil: 'ADMIN',
        estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho === '/v1/perfis') return PERFIS;
    if (caminho.startsWith('/v1/usuarios')) return init?.method ? undefined : pessoas;
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={fila}><Acessos /></QueryClientProvider>
    </MemoryRouter>,
  );
}

const ultimoEnvio = (sufixo: string) => {
  const envio = chamar.mock.calls
    .filter(([caminho, init]) => String(caminho).includes(sufixo) && init?.method)
    .at(-1);
  return envio && {
    caminho: String(envio[0]),
    metodo: (envio[1] as RequestInit).method,
    corpo: (envio[1] as RequestInit).body
      ? JSON.parse(String((envio[1] as RequestInit).body)) : undefined,
  };
};

const linhaDe = async (nome: string) =>
  (await screen.findByRole('heading', { name: new RegExp(nome) }))
    .closest('article') as HTMLElement;

const abrir = async (nome: string) =>
  userEvent.click(within(await linhaDe(nome)).getByRole('button', { name: 'Abrir' }));

beforeEach(() => { chamar.mockReset(); });

describe('quem entra no sistema', () => {
  it('marca quem ainda vai ter de configurar o segundo fator', async () => {
    // RN-IAM-014: o servidor não deixa GERENTE passar da inscrição. Não é escolha do salão, e a
    // tela diz o que vai acontecer em vez de pintar um alerta sem explicação.
    montar();

    const ana = await linhaDe('Ana Ribeiro');
    expect(within(ana).getByText('configura o segundo fator ao entrar')).toBeInTheDocument();
  });

  it('não marca segundo fator em quem não precisa dele', async () => {
    // Recepção não exige segundo fator, e uma recepção que já entrou não tem pendência
    // nenhuma: a linha mostra o último acesso, e mais nada.
    montar({ pessoas: [{ ...BIA, ultimoAcessoEm: new Date().toISOString() }] });

    const bia = await linhaDe('Bia Nogueira');
    expect(within(bia).queryByText(/segundo fator/)).not.toBeInTheDocument();
    expect(within(bia).getByText(/entrou hoje/)).toBeInTheDocument();
  });

  it('conta criada e nunca usada aparece: quase sempre a senha não chegou', async () => {
    montar();

    expect(within(await linhaDe('Bia Nogueira')).getByText('nunca entrou')).toBeInTheDocument();
  });

  it('quem nunca entrou não é cobrada por segundo fator: a senha é que não chegou', async () => {
    // Uma gerente criada hoje tem as duas pendências ao mesmo tempo. A que resolve o caso dela
    // é a primeira — configurar o autenticador só acontece depois de a pessoa conseguir entrar.
    montar({ pessoas: [{ ...ANA, id: 'u9', nome: 'Lia Prado', ultimoAcessoEm: null }] });

    const lia = await linhaDe('Lia Prado');
    expect(within(lia).getByText('nunca entrou')).toBeInTheDocument();
    expect(within(lia).queryByText(/segundo fator/)).not.toBeInTheDocument();
  });

  it('o perfil aparece com o nome do servidor, não com a constante', async () => {
    montar();

    expect(within(await linhaDe('Ana Ribeiro')).getByText('Gerente')).toBeInTheDocument();
    expect(screen.queryByText('GERENTE')).not.toBeInTheDocument();
  });
});

describe('a própria conta', () => {
  it('vem marcada como sua', async () => {
    montar();

    expect(within(await linhaDe('Marilda')).getByText('você')).toBeInTheDocument();
  });

  it('explica por que não se muda o próprio perfil, em vez de só desabilitar', async () => {
    // RN-IAM-015: quem perceberia o engano é justamente quem acabou de perder o acesso. Botão
    // cinza sem motivo faria o dono achar que é defeito.
    montar();

    await abrir('Marilda');

    const perfil = screen.getByRole('region', { name: 'Perfil' });
    expect(within(perfil).getByText(/Peça a outro administrador/)).toBeInTheDocument();
    expect(within(perfil).queryByRole('radio')).not.toBeInTheDocument();
  });

  it('manda a própria senha para o caminho que exige a senha atual', async () => {
    montar();

    await abrir('Marilda');

    expect(screen.getByRole('link', { name: 'Trocar minha senha' }))
      .toHaveAttribute('href', '/seguranca/senha');
  });
});

describe('criar acesso', () => {
  it('manda nome, e-mail, perfil e senha — e mostra a senha para copiar depois', async () => {
    // Depois de criada a senha não se lê de novo: o banco guarda o hash. Deixar a pessoa
    // descobrir isso recarregando a página seria esconder o único momento em que ela existe.
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Novo acesso' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Nice Fernandes');
    await userEvent.type(screen.getByLabelText('E-mail de entrada'), 'nice@salao.test');

    await userEvent.click(screen.getByRole('button', { name: 'Criar acesso' }));

    const envio = ultimoEnvio('/v1/usuarios');
    expect(envio).toMatchObject({
      metodo: 'POST',
      corpo: { nome: 'Nice Fernandes', email: 'nice@salao.test', perfil: 'RECEPCAO' },
    });
    expect(envio?.corpo.senha.length).toBeGreaterThanOrEqual(12);
    expect(await screen.findByText('Acesso criado')).toBeInTheDocument();
    expect(screen.getByText(envio!.corpo.senha)).toBeInTheDocument();
  });

  it('a senha vem sorteada, e gerar outra troca a que está lá', async () => {
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Novo acesso' }));
    const campo = screen.getByLabelText('Senha inicial') as HTMLInputElement;
    const primeira = campo.value;

    await userEvent.click(screen.getByRole('button', { name: 'Gerar outra' }));

    expect(campo.value).not.toBe(primeira);
    expect(primeira.length).toBeGreaterThanOrEqual(12);
  });

  it('o perfil escolhido mostra o que ele concede, com as palavras do servidor', async () => {
    // O dicionário de permissões vive no servidor (RT-IAM-009): uma cópia aqui divergiria na
    // primeira permissão nova, em silêncio.
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Novo acesso' }));
    // Só a do perfil escolhido: cinco listas abertas ao mesmo tempo afogariam a única
    // decisão que a pessoa veio tomar.
    expect(screen.getByText('dar desconto de até 10%')).toBeInTheDocument();
    expect(screen.queryByText('ver o dinheiro do salão inteiro')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: /Gerente/ }));

    expect(screen.getByText('ver o dinheiro do salão inteiro')).toBeInTheDocument();
    expect(screen.queryByText('dar desconto de até 10%')).not.toBeInTheDocument();
    expect(screen.getAllByText('exige segundo fator').length).toBeGreaterThan(0);
  });

  it('a recusa do servidor aparece com as palavras dele', async () => {
    const { ErroDaApi } = await import('../../api/http');
    montar({ aoEscrever: new ErroDaApi(409, 'ER-IAM-EMAIL_JA_CADASTRADO',
      'Este e-mail já está em uso.') });
    await userEvent.click(await screen.findByRole('button', { name: 'Novo acesso' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Nice');
    await userEvent.type(screen.getByLabelText('E-mail de entrada'), 'ana@salao.test');

    await userEvent.click(screen.getByRole('button', { name: 'Criar acesso' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Este e-mail já está em uso.');
  });
});

describe('corrigir e promover', () => {
  it('a ficha manda só nome e e-mail: perfil e senha têm rota própria', async () => {
    montar();
    await abrir('Ana Ribeiro');

    await userEvent.clear(screen.getByLabelText('Nome'));
    await userEvent.type(screen.getByLabelText('Nome'), 'Ana Ribeiro Lopes');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    const envio = ultimoEnvio('/v1/usuarios/u2');
    expect(envio).toMatchObject({ metodo: 'PUT', corpo: { nome: 'Ana Ribeiro Lopes' } });
    expect(envio?.corpo).not.toHaveProperty('perfil');
    expect(envio?.corpo).not.toHaveProperty('senha');
  });

  it('trocar o e-mail avisa que a pessoa vai ser desconectada', async () => {
    montar();
    await abrir('Ana Ribeiro');

    await userEvent.clear(screen.getByLabelText('E-mail de entrada'));
    await userEvent.type(screen.getByLabelText('E-mail de entrada'), 'ana.lopes@salao.test');

    expect(screen.getByText(/as sessões desta pessoa são encerradas/)).toBeInTheDocument();
  });

  it('corrigir só a caixa do e-mail não promete desconexão que não vai acontecer', async () => {
    // O servidor compara o e-mail normalizado: "Ana@Salao.test" é a mesma credencial. Avisar
    // que as sessões vão cair seria um aviso falso — e aviso falso ensina a ignorar avisos.
    montar();
    await abrir('Ana Ribeiro');

    await userEvent.clear(screen.getByLabelText('E-mail de entrada'));
    await userEvent.type(screen.getByLabelText('E-mail de entrada'), 'Ana@Salao.test');

    expect(screen.queryByText(/as sessões desta pessoa são encerradas/)).not.toBeInTheDocument();
  });

  it('mudar o perfil é um ato próprio, e diz o que causa antes', async () => {
    montar();
    await abrir('Ana Ribeiro');
    expect(screen.getByRole('button', { name: 'Mudar perfil' })).toBeDisabled();

    await userEvent.click(screen.getByRole('radio', { name: /Recepção/ }));
    expect(screen.getByText(/encerra as sessões desta pessoa/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Mudar perfil' }));

    expect(ultimoEnvio('/v1/usuarios/u2/perfil'))
      .toMatchObject({ metodo: 'PATCH', corpo: { perfil: 'RECEPCAO' } });
  });

  it('definir senha nova sorteia uma e avisa que a pessoa sai de todos os aparelhos', async () => {
    montar();
    await abrir('Bia Nogueira');

    await userEvent.click(screen.getByRole('button', { name: 'Definir senha nova' }));
    const campo = screen.getByLabelText(/Senha nova de Bia/) as HTMLInputElement;
    expect(campo.value.length).toBeGreaterThanOrEqual(12);
    await userEvent.click(screen.getByRole('button', { name: 'Definir senha' }));

    expect(ultimoEnvio('/v1/usuarios/u3/senha'))
      .toMatchObject({ metodo: 'POST', corpo: { senha: campo.value } });
  });

  it('remover o segundo fator só aparece para quem o tem', async () => {
    montar();

    await abrir('Bia Nogueira');
    expect(screen.queryByRole('button', { name: 'Remover segundo fator' }))
      .not.toBeInTheDocument();
    expect(screen.getByText('Sem segundo fator configurado.')).toBeInTheDocument();
  });

  it('os telefones do agente aparecem como se leem, e são tratados como permissão', async () => {
    // RN-IAM-022: quem escreve de qualquer um deles age com as permissões da conta.
    montar();
    await abrir('Ana Ribeiro');

    expect(screen.getByLabelText('Telefone')).toHaveValue('(11) 98765-4321');
    expect(screen.getByLabelText('Outro telefone')).toHaveValue('(11) 93333-2222');
    expect(screen.getByText(/age com as permissões desta conta/)).toBeInTheDocument();
    // As duas vagas ocupadas: não há o que acrescentar.
    expect(screen.queryByRole('button', { name: 'Acrescentar outro número' }))
      .not.toBeInTheDocument();
  });

  it('o segundo campo só aparece quando se pede: campo vazio permanente convida a preencher',
    async () => {
      // RN-IAM-026: são duas vagas, não uma hierarquia — e o que se põe aqui dá acesso à agenda.
      montar();
      await abrir('Bia Nogueira');

      expect(screen.getByLabelText('Telefone')).toHaveValue('');
      expect(screen.queryByLabelText('Outro telefone')).not.toBeInTheDocument();

      await userEvent.click(screen.getByRole('button', { name: 'Acrescentar outro número' }));

      expect(screen.getByLabelText('Outro telefone')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Acrescentar outro número' }))
        .not.toBeInTheDocument();
    });

  it('salva a lista inteira, sem os campos vazios', async () => {
    montar();
    await abrir('Bia Nogueira');

    await userEvent.type(screen.getByLabelText('Telefone'), '(11) 91111-2222');
    await userEvent.click(screen.getByRole('button', { name: 'Acrescentar outro número' }));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar telefones' }));

    expect(ultimoEnvio('/telefones')).toEqual({
      caminho: '/v1/usuarios/u3/telefones',
      metodo: 'PUT',
      corpo: { telefones: ['11911112222'] },
    });
  });
});

describe('copiar a credencial', () => {
  it('não diz "copiado" quando a área de transferência não existe', async () => {
    // Sem HTTPS, ou com a permissão negada, `writeText` falha. Dizer "copiado" faria alguém
    // colar nada numa mensagem e mandar para a pessoa que está esperando a senha.
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Novo acesso' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Nice');
    await userEvent.type(screen.getByLabelText('E-mail de entrada'), 'nice@salao.test');
    await userEvent.click(screen.getByRole('button', { name: 'Criar acesso' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Copiar e-mail e senha' }));

    expect(await screen.findByRole('button', { name: 'Copie na mão' })).toBeInTheDocument();
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: a lista e o editor abertos', async () => {
    const { container } = montar();
    await abrir('Ana Ribeiro');
    await screen.findByRole('region', { name: 'Perfil' });

    await deveSerAcessivel(container);
  });
});
