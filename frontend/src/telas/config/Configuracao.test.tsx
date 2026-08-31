import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Configuracao } from './Configuracao';

const chamar = vi.fn();
vi.mock('../../api/http', () => ({
  api: (caminho: string, init?: RequestInit) => chamar(caminho, init),
  ErroDaApi: class extends Error {},
}));

const ATUAL = {
  id: 'e1', nome: 'Studio Marilda', fuso: 'America/Sao_Paulo', moeda: 'BRL',
  baseComissao: 'BRUTO', descontoAfetaComissao: false,
  periodicidadeDeFechamento: 'MENSAL', paisPadrao: '55', dddPadrao: null, ativo: true,
};

function montar(configuracao: unknown = ATUAL) {
  chamar.mockImplementation(async (caminho: string, init?: RequestInit) => {
    if (init?.method === 'PUT') return { ...ATUAL, ...JSON.parse(String(init.body)) };
    if (caminho === '/v1/configuracao') return configuracao;
    return undefined;
  });
  const fila = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={fila}><Configuracao /></QueryClientProvider>);
}

beforeEach(() => { chamar.mockReset(); });

describe('configuração do salão', () => {
  it('avisa que sem o DDD o cadastro de cliente recusa telefone', async () => {
    // É o buraco que a rotina fecha. Um rótulo "DDD padrão" sozinho não faz ninguém
    // preencher — e quem deixa vazio depois não entende por que o telefone é recusado.
    montar();

    expect(await screen.findByText(/o cadastro recusa esses números/)).toBeInTheDocument();
  });

  it('salva o DDD digitado', async () => {
    montar();

    await userEvent.type(await screen.findByLabelText(/DDD da região/), '11');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(chamar).toHaveBeenCalledWith('/v1/configuracao', expect.objectContaining({
      method: 'PUT',
      body: expect.stringContaining('"dddPadrao":"11"'),
    }));
  });

  it('explica a consequência da base de comissão, não só o nome dela', async () => {
    // Estas opções parecem preferências e decidem quanto as pessoas recebem.
    montar();

    expect(await screen.findByText(/Muda quanto cada profissional recebe/)).toBeInTheDocument();
    expect(screen.getByText(/fechamentos já feitos não são recalculados/)).toBeInTheDocument();
  });

  it('descreve o desconto pelo efeito, não pelo jargão', async () => {
    montar();

    const desconto = await screen.findByLabelText(/Desconto dado à cliente/);
    expect(desconto).toHaveTextContent('sai do salão');
    expect(desconto).toHaveTextContent('é dividido');
  });

  it('confirma que salvou — num formulário sem mudança visível, o silêncio vira dúvida', async () => {
    montar();

    await userEvent.click(await screen.findByRole('button', { name: 'Salvar' }));

    expect(await screen.findByText('Configurações salvas.')).toBeInTheDocument();
  });

  it('a confirmação some ao mexer de novo', async () => {
    montar();

    await userEvent.click(await screen.findByRole('button', { name: 'Salvar' }));
    await screen.findByText('Configurações salvas.');
    await userEvent.type(screen.getByLabelText(/DDD da região/), '2');

    expect(screen.queryByText('Configurações salvas.')).not.toBeInTheDocument();
  });

  it('parte dos valores que o servidor mandou, não de padrões inventados', async () => {
    montar({ ...ATUAL, dddPadrao: '21', baseComissao: 'LIQUIDO',
      fuso: 'America/Manaus', periodicidadeDeFechamento: 'QUINZENAL' });

    expect(await screen.findByLabelText(/DDD da região/)).toHaveValue('21');
    expect(screen.getByLabelText(/Fuso horário/)).toHaveValue('America/Manaus');
    expect(screen.getByLabelText(/A comissão incide sobre/)).toHaveValue('LIQUIDO');
    expect(screen.getByLabelText(/Fechamento do período/)).toHaveValue('QUINZENAL');
  });
});
