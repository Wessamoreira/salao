import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Conectividade } from './Conectividade';
import { registrarEstadoDaApi } from './conectado';

/** jsdom não tem rede: `onLine` é o que a tela lê, e o evento é o que a acorda. */
function rede(temInternet: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: temInternet });
  act(() => { window.dispatchEvent(new Event(temInternet ? 'online' : 'offline')); });
}

/** O servidor caiu com a internet de pé — é o que `http.ts` registra a cada falha. */
function servidorForaDoAr() {
  act(() => { registrarEstadoDaApi(false); });
}

const tentar = vi.fn();

function montar(variante?: 'barra' | 'flutuante', aoTentar?: () => void) {
  return render(<Conectividade variante={variante} aoTentar={aoTentar} />);
}

beforeEach(() => {
  rede(true);
  registrarEstadoDaApi(true);
  tentar.mockReset();
});
afterEach(() => { vi.useRealTimers(); });

describe('estado da conexão', () => {
  it('com tudo funcionando, não ocupa espaço nenhum', () => {
    const { container } = montar('barra');

    expect(container).toBeEmptyDOMElement();
  });

  it('sem internet, diz o que deixou de ser possível', () => {
    montar('barra');

    rede(false);

    expect(screen.getByRole('status')).toHaveTextContent('Sem internet');
    expect(screen.getByRole('status')).toHaveTextContent('Dá para consultar; salvar, não.');
  });

  it('servidor fora do ar não acusa a internet de quem está com ela boa', () => {
    // Dizer "sem conexão" para quem tem o WhatsApp aberto é a forma mais rápida de ensinar
    // a recepção a ignorar o aviso.
    montar('barra');

    servidorForaDoAr();

    expect(screen.getByRole('status')).toHaveTextContent('Sistema fora do ar');
    expect(screen.getByRole('status')).toHaveTextContent('Dá para consultar; salvar, não.');
  });

  it('com a rede caída, quem manda no diagnóstico é a rede', () => {
    // É o caso mais comum, e o que mais erra se a ordem for outra: a internet cai, a
    // requisição em voo falha, e a falha marca a API como fora do ar. Os dois sinais ficam
    // acesos ao mesmo tempo — e o certo é o que está perto da pessoa e ela pode resolver.
    montar('barra');

    rede(false);
    servidorForaDoAr();

    expect(screen.getByRole('status')).toHaveTextContent('Sem internet');
  });

  it('só o servidor fora oferece tentar de novo — e tentar refaz as consultas', async () => {
    // Internet voltando, o navegador avisa sozinho. Servidor voltando, ninguém avisa: o
    // estado só muda quando alguma requisição der certo, e sem isto a saída era F5.
    montar('barra', tentar);
    servidorForaDoAr();

    await userEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }));

    expect(tentar).toHaveBeenCalled();
  });

  it('sem internet não oferece botão: não há o que tentar', () => {
    montar('barra', tentar);

    rede(false);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('na porta do sistema, o aviso flutua e não tem botão', () => {
    // Ali não existe consulta nenhuma para refazer — quem monta o aviso não passa `aoTentar`,
    // e o botão seria enfeite.
    montar('flutuante');

    servidorForaDoAr();

    expect(screen.getByRole('status')).toHaveClass('flutuante');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('a volta', () => {
  it('é avisada para quem viu a queda, e sai sozinha da tela', () => {
    vi.useFakeTimers();
    montar('barra');
    rede(false);

    rede(true);

    expect(screen.getByRole('status')).toHaveTextContent('Conexão restabelecida');
    act(() => { vi.advanceTimersByTime(5_000); });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('quem abriu o sistema com tudo funcionando não é avisado de nada', () => {
    montar('barra');

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('a internet voltando não deixa "sistema fora do ar" na tela', () => {
    // A requisição que falhou porque a internet caiu marcou a API como fora do ar. Sem
    // zerar isso, o Wi-Fi voltar trocaria um diagnóstico por outro pior: um que é só o eco
    // da queda anterior, e que ficaria ali até alguém pedir algo ao servidor.
    montar('barra');
    rede(false);
    servidorForaDoAr();

    rede(true);

    expect(screen.getByRole('status')).toHaveTextContent('Conexão restabelecida');
  });
});
