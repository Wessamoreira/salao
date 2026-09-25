import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { deveSerAcessivel } from '../../teste/acessibilidade';
import { Equipe } from './Equipe';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  // O dublê precisa ter a MESMA assinatura do real: a tela mostra `message`, que é a frase do
  // servidor — "PARCEIRO exige CNPJ" diz o que fazer.
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

const ANA = {
  id: 'p1', nome: 'Ana Ribeiro', apelido: 'Ana', documento: '52998224725',
  vinculo: 'EMPREGADO', telefone: '5511987654321', email: 'ana@salao.test',
  comissaoPercentual: 40, contratoInicio: '2026-01-10', contratoFim: null,
  contratoAssinadoEm: '2026-01-08', ativo: true, versao: 2,
};

const JULIA = {
  id: 'p2', nome: 'Júlia Menezes', apelido: null, documento: '11222333000181',
  vinculo: 'PARCEIRO', telefone: null, email: null, comissaoPercentual: 55,
  contratoInicio: '2026-03-01', contratoFim: null, contratoAssinadoEm: null,
  ativo: true, versao: 1,
};

const JORNADA_DA_ANA = {
  '2': [{ inicio: '09:00', fim: '13:00' }, { inicio: '14:00', fim: '18:00' }],
  '3': [{ inicio: '09:00', fim: '13:00' }, { inicio: '14:00', fim: '18:00' }],
};

const SERVICOS = [
  { id: 's1', nome: 'Coloração', descricao: null, ativo: true, versao: 1,
    duracaoTotal: 100, minutosDoProfissional: 60, precoVigente: 180, etapas: [] },
];

function montar({ pessoas = [ANA, JULIA] as unknown[], jornada = JORNADA_DA_ANA as unknown,
  aoEscrever = null as unknown } = {}) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method && aoEscrever instanceof Error) throw aoEscrever;
    if (caminho === '/v1/me/capabilities') {
      return { estabelecimento: { timezone: 'America/Sao_Paulo' }, permissoes: [], menus: [] };
    }
    if (caminho.includes('/jornada') && !caminho.startsWith('/v1/bloqueios')) {
      return init?.method ? jornada : jornada;
    }
    if (caminho.includes('/habilidades')) {
      return init?.method ? { executaTodos: false, servicos: ['s1'], duracoes: {} }
        : { executaTodos: true, servicos: [], duracoes: {} };
    }
    if (caminho.startsWith('/v1/bloqueios-de-jornada')) return init?.method ? {} : [];
    if (caminho.startsWith('/v1/profissionais')) return init?.method ? ANA : pessoas;
    if (caminho.startsWith('/v1/servicos')) return SERVICOS;
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={fila}><Equipe /></QueryClientProvider>
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
  (await screen.findByRole('heading', { name: nome })).closest('article') as HTMLElement;

beforeEach(() => { chamar.mockReset(); });

describe('quem atende', () => {
  it('resume a jornada — e não inventa faixa quando os dias diferem', async () => {
    // "ter–sáb 09:00–18:00" com o sábado fechando às 16:00 é um resumo que mente.
    montar({ jornada: {
      '2': [{ inicio: '09:00', fim: '18:00' }],
      '3': [{ inicio: '09:00', fim: '18:00' }],
    } });

    const ana = await linhaDe('Ana');
    // `findBy`: o resumo depende da jornada, que chega numa segunda consulta.
    expect(await within(ana).findByText('ter–qua 09:00–18:00')).toBeInTheDocument();
  });

  it('com horários diferentes, o resumo não finge uma faixa só', async () => {
    montar({ jornada: {
      '2': [{ inicio: '09:00', fim: '18:00' }],
      '3': [{ inicio: '09:00', fim: '16:00' }],
    } });

    const ana = await linhaDe('Ana');
    expect(await within(ana).findByText('ter, qua · horários variam')).toBeInTheDocument();
  });

  it('marca contrato pendente de quem é parceira — é obrigação legal do salão', async () => {
    // RN-EQP-003: sem contrato assinado a parceria é registrada e não bloqueada, e some do
    // radar sem um lembrete.
    montar();

    const julia = await linhaDe('Júlia Menezes');
    expect(within(julia).getByText(/contrato pendente/)).toBeInTheDocument();
    expect(within(await linhaDe('Ana')).queryByText(/contrato pendente/)).not.toBeInTheDocument();
  });

  it('equipe vazia diz o que isso causa na agenda', async () => {
    montar({ pessoas: [] });

    expect(await screen.findByText(/faz a agenda sair do vazio/)).toBeInTheDocument();
  });
});

describe('a ficha', () => {
  it('cadastrar manda documento e vínculo; editar não manda nenhum dos dois', async () => {
    // RN-EQP-017: os dois são identidade jurídica da relação.
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Nova profissional' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Nice Fernandes');
    await userEvent.type(screen.getByLabelText('CNPJ'), '11222333000181');
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar' }));

    expect(ultimoEnvio('/v1/profissionais')).toMatchObject({
      metodo: 'POST',
      corpo: { nome: 'Nice Fernandes', documento: '11222333000181', vinculo: 'PARCEIRO' },
    });
  });

  it('editar leva a versão lida e não leva identidade', async () => {
    montar();
    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));

    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    const envio = ultimoEnvio('/v1/profissionais/p1');
    expect(envio).toMatchObject({ metodo: 'PUT', corpo: { nome: 'Ana Ribeiro', versao: 2 } });
    expect(envio?.corpo).not.toHaveProperty('documento');
    expect(envio?.corpo).not.toHaveProperty('vinculo');
  });

  it('o rótulo do documento acompanha o vínculo, em vez de deixar o servidor ensinar', async () => {
    // RN-EQP-002: parceira é pessoa jurídica e precisa de CNPJ; empregada, de CPF.
    montar();
    await userEvent.click(await screen.findByRole('button', { name: 'Nova profissional' }));
    expect(screen.getByLabelText('CNPJ')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Vínculo'), 'EMPREGADO');

    expect(screen.getByLabelText('CPF')).toBeInTheDocument();
    expect(screen.queryByLabelText('CNPJ')).not.toBeInTheDocument();
  });

  it('comissão em branco vira nula — é "usa a cascata", não zero', async () => {
    montar();
    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));

    await userEvent.clear(screen.getByLabelText('Percentual de comissão'));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar ficha' }));

    expect(ultimoEnvio('/v1/profissionais/p1')?.corpo.comissaoPercentual).toBeNull();
  });

  it('o telefone aparece como se lê, e não como o banco guarda', async () => {
    montar();

    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));

    expect(screen.getByLabelText('Telefone')).toHaveValue('(11) 98765-4321');
  });

  it('a recusa do servidor aparece com as palavras dele', async () => {
    const { ErroDaApi } = await import('../../api/http');
    montar({ aoEscrever: new ErroDaApi(422, 'ER-EQP-DOCUMENTO_NAO_COMBINA_COM_VINCULO',
      'PARCEIRO exige CNPJ') });
    await userEvent.click(await screen.findByRole('button', { name: 'Nova profissional' }));
    await userEvent.type(screen.getByLabelText('Nome'), 'Nice');
    await userEvent.type(screen.getByLabelText('CNPJ'), '52998224725');

    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('PARCEIRO exige CNPJ');
  });
});

describe('a jornada', () => {
  it('repetir o dia copia os intervalos para a semana, sem o domingo', async () => {
    // Jornada de salão é quase sempre a mesma de segunda a sábado; preencher seis dias iguais
    // na mão é o que faz alguém voltar para a planilha. Domingo é exceção e fica de fora.
    montar();
    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));
    await screen.findByLabelText('Início do 1º intervalo de terça-feira');

    await userEvent.click(screen.getByRole('button', { name: 'Repetir terça-feira nos outros dias' }));
    await userEvent.click(screen.getByRole('button', { name: 'Salvar jornada' }));

    const corpo = ultimoEnvio('/jornada')?.corpo;
    expect(corpo['1']).toEqual(corpo['2']);
    expect(corpo['6']).toEqual(corpo['2']);
    // Domingo não entra na cópia: salão que abre domingo é exceção, e quem abre marca o dia.
    expect(corpo['7']).toBeUndefined();
  });

  it('dia sem intervalo é folga, e a tela diz isso', async () => {
    montar();
    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));

    expect(await screen.findAllByText('folga')).not.toHaveLength(0);
  });
});

describe('habilidades', () => {
  it('a lista de serviços só aparece depois de escolher restringir', async () => {
    // RN-EQP-013: executar tudo é o regime normal. Quarenta caixas de seleção para quem executa
    // tudo é pedir uma decisão que a maioria dos salões não precisa tomar.
    montar();
    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));
    await screen.findByText('Executa todos os serviços do cardápio');
    expect(screen.queryByRole('checkbox', { name: /Coloração/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: 'Só alguns' }));

    expect(await screen.findByRole('checkbox', { name: /Coloração/ })).toBeInTheDocument();
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: a lista e o editor abertos', async () => {
    const { container } = montar();
    await userEvent.click(within(await linhaDe('Ana')).getByRole('button', { name: 'Abrir' }));
    await screen.findByText('Executa todos os serviços do cardápio');

    await deveSerAcessivel(container);
  });
});
