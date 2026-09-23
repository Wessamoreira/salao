import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ErroDaApi } from '../api/http';
import { deveSerAcessivel } from '../teste/acessibilidade';
import { Login } from './Login';

const chamar = vi.fn();
const navegar = vi.fn();

vi.mock('../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  // O dublê precisa ter a MESMA assinatura do real: a tela decide a mensagem pelo `codigo`.
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
vi.mock('react-router-dom', () => ({ useNavigate: () => navegar }));
vi.mock('../sessao/sessao', () => ({ definirTokenAtual: vi.fn() }));

async function entrar(senha = 'senha-errada-mesmo') {
  render(<Login />);
  await userEvent.type(screen.getByLabelText('E-mail'), 'joana@salao.test');
  await userEvent.type(screen.getByLabelText('Senha'), senha);
  await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));
}

beforeEach(() => { chamar.mockReset(); navegar.mockReset(); });

describe('entrar', () => {
  it('senha errada: o aviso aparece, os dois campos são marcados e o foco vai para a senha',
    async () => {
      // O servidor não diz qual dos dois está errado, de propósito — então nenhum dos dois
      // pode ser apontado sozinho. O foco vai para a senha porque é quase sempre ela: o
      // e-mail a recepção digita certo, ou o gerenciador preenche.
      chamar.mockRejectedValue(new ErroDaApi(401, 'ER-IAM-CREDENCIAIS_INVALIDAS', 'não'));
      await entrar();

      const aviso = await screen.findByRole('alert');
      expect(aviso).toHaveTextContent('E-mail ou senha incorretos');
      const senha = screen.getByLabelText('Senha');
      expect(senha).toHaveAttribute('aria-invalid', 'true');
      expect(screen.getByLabelText('E-mail')).toHaveAttribute('aria-invalid', 'true');
      // O campo aponta para o aviso: quem usa leitor de tela ouve o motivo ao chegar nele.
      expect(senha.getAttribute('aria-describedby')).toContain(aviso.id);
      await waitFor(() => expect(senha).toHaveFocus());
    });

  it('tentativas demais não marca campo nenhum', async () => {
    // Não é o e-mail nem a senha que estão errados: apontar um campo mandaria corrigir o
    // que está certo.
    chamar.mockRejectedValue(new ErroDaApi(429, 'ER-IAM-ACESSO_BLOQUEADO', 'espere'));
    await entrar();

    expect(await screen.findByRole('alert')).toHaveTextContent('Tentativas demais');
    expect(screen.getByLabelText('Senha')).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText('E-mail')).not.toHaveAttribute('aria-invalid');
  });

  it('erro sem código conhecido ainda diz o que fazer', async () => {
    chamar.mockRejectedValue(new Error('caiu a rede'));
    await entrar();

    expect(await screen.findByRole('alert')).toHaveTextContent('Tente de novo');
  });

  it('acertando, entra sem passar pelo segundo fator quando ele não é exigido', async () => {
    chamar.mockResolvedValue({ segundoFatorNecessario: false, tokenDeAcesso: 'tk' });
    await entrar('senha-certa');

    await waitFor(() => expect(navegar).toHaveBeenCalledWith('/'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('com segundo fator, leva o desafio para a verificação — e nunca o token', async () => {
    // O desafio sozinho não abre nada: ele só atesta que a senha foi conferida.
    chamar.mockResolvedValue({ segundoFatorNecessario: true, desafio: 'dsf' });
    await entrar('senha-certa');

    await waitFor(() => expect(navegar)
      .toHaveBeenCalledWith('/entrar/verificacao', { state: { desafio: 'dsf' } }));
  });
});

describe('acessibilidade', () => {
  it('não tem violação estrutural: a tela de entrar', async () => {
    chamar.mockRejectedValue(new ErroDaApi(401, 'ER-IAM-CREDENCIAIS_INVALIDAS', 'não'));
    const { container } = render(<Login />);
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));
    await screen.findByRole('alert');

    // Com o erro na tela: é o estado que ninguém testa, e o que mais depende de `aria`.
    await deveSerAcessivel(container);
  });
});
