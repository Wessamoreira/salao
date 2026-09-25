import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Catalogo } from './Catalogo';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  // O dublê precisa ter a MESMA assinatura do real: a tela mostra `message`, que é a frase do
  // servidor — "este equipamento é exigido por três serviços" diz o que fazer.
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

const LAVATORIO = { id: 'r1', nome: 'Lavatório', quantidade: 2, ativo: true, versao: 0 };

const COLORACAO = {
  id: 's1', nome: 'Coloração', descricao: 'raiz e comprimento', ativo: true, versao: 3,
  duracaoTotal: 100, minutosDoProfissional: 60, precoVigente: 180,
  etapas: [
    { tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: [] },
    { tipo: 'PAUSA', duracaoMinutos: 40, recursosExigidos: [] },
    { tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: ['r1'] },
  ],
};

const HIDRATACAO = {
  id: 's2', nome: 'Hidratação', descricao: null, ativo: true, versao: 0,
  duracaoTotal: 30, minutosDoProfissional: 30, precoVigente: null,
  etapas: [{ tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: [] }],
};

const APOSENTADO = {
  id: 's3', nome: 'Luzes com touca', descricao: null, ativo: false, versao: 7,
  duracaoTotal: 120, minutosDoProfissional: 80, precoVigente: 260,
  etapas: [{ tipo: 'ATIVO', duracaoMinutos: 120, recursosExigidos: [] }],
};

const PRECOS = [
  { id: 'p2', valor: 195, vigenteDe: '2026-12-01' },
  { id: 'p1', valor: 180, vigenteDe: '2026-06-01' },
];

function montar({ servicos = [COLORACAO, HIDRATACAO, APOSENTADO] as unknown[],
  recursos = [LAVATORIO] as unknown[], aoEscrever = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method && aoEscrever instanceof Error) throw aoEscrever;
    if (caminho === '/v1/me/capabilities') {
      return { estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho.includes('/precos')) return init?.method ? { id: 'novo' } : PRECOS;
    if (caminho.startsWith('/v1/recursos')) return init?.method ? LAVATORIO : recursos;
    if (caminho.startsWith('/v1/servicos')) return init?.method ? COLORACAO : servicos;
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={fila}><Catalogo /></QueryClientProvider>);
}

/** O corpo do último envio para o caminho — só as escritas, as leituras não têm corpo. */
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
  (await screen.findByRole('heading', { name: nome })).closest('article') as HTMLElement;

beforeEach(() => { chamar.mockReset(); });
afterEach(() => { vi.useRealTimers(); });

describe('o cardápio', () => {
  it('mostra o tempo de cadeira e o de trabalho — que só são iguais quando são', async () => {
    // Numa coloração de 1h40 a profissional trabalha 1h00, e é o segundo número que decide se
    // cabe um corte no meio. Repetir o mesmo número duas vezes ensinaria que são a mesma coisa.
    montar();

    const coloracao = await linhaDe('Coloração');
    expect(within(coloracao).getByText('1h40')).toBeInTheDocument();
    expect(within(coloracao).getByText('1h')).toBeInTheDocument();

    const hidratacao = await linhaDe('Hidratação');
    expect(within(hidratacao).queryByText('de trabalho')).not.toBeInTheDocument();
  });

  it('serviço sem preço é marcado, porque é o que a comanda vai recusar vender', async () => {
    montar();

    const hidratacao = await linhaDe('Hidratação');

    expect(within(hidratacao).getByText('sem preço')).toBeInTheDocument();
    expect(within(await linhaDe('Coloração')).getByText(/R\$\s?180,00/)).toBeInTheDocument();
  });

  it('o que saiu do cardápio continua à vista, marcado', async () => {
    // Esconder faria procurar por um nome que a tela não mostra mais — e ele existe no
    // histórico da agenda.
    montar();

    const aposentado = await linhaDe('Luzes com touca');

    expect(within(aposentado).getByText('fora do cardápio')).toBeInTheDocument();
    expect(within(aposentado).getByRole('button', { name: 'Voltar ao cardápio' }))
      .toBeInTheDocument();
  });

  it('cardápio vazio convida a cadastrar, e diz por que importa', async () => {
    montar({ servicos: [] });

    expect(await screen.findByText(/Sem serviço não há o que marcar/)).toBeInTheDocument();
  });
});

describe('cadastrar e corrigir', () => {
  it('cadastrar manda a receita inteira, com o recurso na etapa que o usa', async () => {
    montar({ servicos: [] });
    await screen.findByText(/O cardápio está vazio/);

    await userEvent.click(screen.getByRole('button', { name: 'Novo serviço' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Escova');
    await userEvent.clear(screen.getByLabelText('Minutos da etapa 1'));
    await userEvent.type(screen.getByLabelText('Minutos da etapa 1'), '45');
    await userEvent.click(screen.getByRole('checkbox', { name: 'Lavatório' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar serviço' }));

    expect(ultimoEnvio('/v1/servicos')).toMatchObject({
      metodo: 'POST',
      corpo: {
        nome: 'Escova', descricao: null,
        etapas: [{ tipo: 'ATIVO', duracaoMinutos: 45, recursosExigidos: ['r1'] }],
      },
    });
  });

  it('editar leva a versão que a tela leu — quem chegar depois é recusado', async () => {
    // Sem isso, duas pessoas editando ao mesmo tempo fariam a correção da primeira sumir sem
    // aviso, numa receita que decide se cabe um encaixe (RN-CAT-015).
    montar();
    const coloracao = await linhaDe('Coloração');

    await userEvent.click(within(coloracao).getByRole('button', { name: 'Editar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }));

    expect(ultimoEnvio('/v1/servicos/s1')).toMatchObject({
      metodo: 'PUT', corpo: { nome: 'Coloração', versao: 3 },
    });
  });

  it('editar avisa que o que já está marcado não muda', async () => {
    montar();
    const coloracao = await linhaDe('Coloração');

    await userEvent.click(within(coloracao).getByRole('button', { name: 'Editar' }));

    expect(screen.getByText(/O que já está marcado não muda/)).toBeInTheDocument();
  });

  it('adicionar etapa oferece o tipo que costuma vir depois — e é só oferta', async () => {
    // Depois de trabalho vem espera. É ajuda de digitação: quem recusa sequência impossível é o
    // servidor (RN-CAT-002, RN-CAT-003), e o seletor continua livre.
    montar({ servicos: [] });
    await userEvent.click(await screen.findByRole('button', { name: 'Novo serviço' }));

    await userEvent.click(screen.getByRole('button', { name: 'Adicionar etapa' }));

    expect(screen.getByLabelText('Tipo da etapa 2')).toHaveValue('PAUSA');
  });

  it('a tela não julga a receita: quem recusa é o servidor, com as palavras dele', async () => {
    const { ErroDaApi } = await import('../../api/http');
    montar({
      servicos: [],
      aoEscrever: new ErroDaApi(422, 'ER-CAT-SEQUENCIA_INVALIDA',
        'o serviço precisa terminar com um bloco de trabalho'),
    });
    await userEvent.click(await screen.findByRole('button', { name: 'Novo serviço' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Hidratação');

    // Uma receita que termina em espera — o erro mais fácil de cometer montando na tela.
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar etapa' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar serviço' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('o serviço precisa terminar com um bloco de trabalho');
  });
});

describe('preço', () => {
  it('o histórico separa o que já vale do que foi anunciado', async () => {
    // Preço futuro pode ser cancelado; o vigente não se apaga, porque comandas foram fechadas
    // com ele (RN-CAT-008).
    vi.setSystemTime(new Date('2026-09-23T12:00:00Z'));
    montar();
    await userEvent.click(within(await linhaDe('Coloração'))
      .getByRole('button', { name: 'Editar' }));

    const preco = await screen.findByRole('region', { name: 'Preço de Coloração' });

    const futuro = within(preco).getByText(/R\$\s?195,00/).closest('li') as HTMLElement;
    expect(within(futuro).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    const vigente = within(preco).getByText(/R\$\s?180,00/).closest('li') as HTMLElement;
    expect(within(vigente).queryByRole('button')).not.toBeInTheDocument();
  });

  it('data no passado avisa que o preço vale para trás, e que comanda fechada não muda',
    async () => {
      vi.setSystemTime(new Date('2026-09-23T12:00:00Z'));
      montar();
      await userEvent.click(within(await linhaDe('Coloração'))
        .getByRole('button', { name: 'Editar' }));

      await userEvent.type(screen.getByLabelText('Valor'), '200');
      await userEvent.type(screen.getByLabelText('A partir de'), '2026-01-10');

      expect(await screen.findByText(/passa a valer para trás/)).toBeInTheDocument();
    });

  it('sem data, o preço vale a partir de hoje — e quem sabe que dia é hoje é o servidor',
    async () => {
      montar();
      await userEvent.click(within(await linhaDe('Coloração'))
        .getByRole('button', { name: 'Editar' }));

      await userEvent.type(screen.getByLabelText('Valor'), '200');
      await userEvent.click(screen.getByRole('button', { name: 'Definir preço' }));

      expect(ultimoEnvio('/precos')).toMatchObject({
        metodo: 'POST', corpo: { valor: 200, vigenteDe: null },
      });
    });
});

describe('equipamentos', () => {
  it('a recusa de aposentar vem do servidor e diz o que conserta', async () => {
    const { ErroDaApi } = await import('../../api/http');
    montar({
      aoEscrever: new ErroDaApi(422, 'ER-CAT-RECURSO_EM_USO',
        'Lavatório é exigido por: Coloração, Hidratação'),
    });
    const lavatorio = (await screen.findByText('Lavatório')).closest('div') as HTMLElement;

    await userEvent.click(within(lavatorio).getByRole('button', { name: 'Aposentar' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('Lavatório é exigido por: Coloração, Hidratação');
  });

  it('cadastrar equipamento manda nome e quantidade', async () => {
    montar({ recursos: [] });
    await userEvent.click(await screen.findByRole('button', { name: 'Novo equipamento' }));

    await userEvent.type(screen.getByLabelText('Nome'), 'Cadeira');
    await userEvent.clear(screen.getByLabelText('Quantas'));
    await userEvent.type(screen.getByLabelText('Quantas'), '4');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(ultimoEnvio('/v1/recursos')).toMatchObject({
      metodo: 'POST', corpo: { nome: 'Cadeira', quantidade: 4 },
    });
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: a lista e o editor abertos', async () => {
    const { container } = montar();
    await userEvent.click(within(await linhaDe('Coloração'))
      .getByRole('button', { name: 'Editar' }));
    await screen.findByRole('region', { name: 'Preço de Coloração' });

    await deveSerAcessivel(container);
  });
});
