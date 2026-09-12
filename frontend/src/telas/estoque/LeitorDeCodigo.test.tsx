import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { LeitorDeCodigo } from './LeitorDeCodigo';

describe('leitor de código de barras', () => {
  it('Enter entrega o código aparado e limpa o campo para o próximo bipe', () => {
    const aoLer = vi.fn();
    render(<LeitorDeCodigo aoLer={aoLer} />);
    const campo = screen.getByLabelText('Código de barras');

    fireEvent.change(campo, { target: { value: '  7891234567895  ' } });
    fireEvent.keyDown(campo, { key: 'Enter' });

    expect(aoLer).toHaveBeenCalledWith('7891234567895');
    expect(campo).toHaveValue('');
  });

  it('Enter com o campo vazio não é leitura', () => {
    // Muitos leitores emitem o terminador duas vezes; o segundo chega com o campo já limpo.
    const aoLer = vi.fn();
    render(<LeitorDeCodigo aoLer={aoLer} />);

    fireEvent.keyDown(screen.getByLabelText('Código de barras'), { key: 'Enter' });

    expect(aoLer).not.toHaveBeenCalled();
  });

  it('tecla comum não fecha a leitura: só o Enter', () => {
    const aoLer = vi.fn();
    render(<LeitorDeCodigo aoLer={aoLer} />);
    const campo = screen.getByLabelText('Código de barras');

    fireEvent.change(campo, { target: { value: '789' } });
    fireEvent.keyDown(campo, { key: '9' });

    expect(aoLer).not.toHaveBeenCalled();
  });

  it('não rouba o foco por padrão: numa tela compartilhada isso travaria os outros campos', () => {
    render(
      <>
        <input aria-label="outro campo" />
        <LeitorDeCodigo aoLer={vi.fn()} />
      </>,
    );

    expect(screen.getByLabelText('Código de barras')).not.toHaveFocus();
  });

  it('com segurarFoco nasce focado: perder o foco é perder o bipe', () => {
    // Um leitor HID é um teclado. Se o campo não está focado no instante do gatilho, os
    // caracteres vão para outro lugar — e a pessoa bipa de novo mais forte, sem entender.
    render(<LeitorDeCodigo aoLer={vi.fn()} segurarFoco />);

    expect(screen.getByLabelText('Código de barras')).toHaveFocus();
  });

  it('pausar diz isso em palavra, não só pela cor da borda', () => {
    // Para quem bipou, "não aconteceu nada" e "não estava escutando" são a mesma experiência.
    render(<LeitorDeCodigo aoLer={vi.fn()} segurarFoco />);

    fireEvent.click(screen.getByRole('button', { name: 'Pausar' }));

    expect(screen.getByRole('status')).toHaveTextContent(/Pausado/);
    expect(screen.getByRole('button', { name: 'Retomar' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('retomar devolve o foco ao campo', () => {
    render(<LeitorDeCodigo aoLer={vi.fn()} segurarFoco />);
    fireEvent.click(screen.getByRole('button', { name: 'Pausar' }));

    fireEvent.click(screen.getByRole('button', { name: 'Retomar' }));

    expect(screen.getByLabelText('Código de barras')).toHaveFocus();
  });

  it('sem segurarFoco não há botão de pausar: não há foco sendo tomado de ninguém', () => {
    render(<LeitorDeCodigo aoLer={vi.fn()} />);

    expect(screen.queryByRole('button')).toBeNull();
  });

  it('ocupado bloqueia o campo e avisa, para não enfileirar bipes', () => {
    render(<LeitorDeCodigo aoLer={vi.fn()} ocupado />);

    expect(screen.getByLabelText('Código de barras')).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('Procurando…');
  });

  it('o Enter do leitor chega cancelado: é o que impede o form em volta de enviar', () => {
    // Na comanda o leitor mora DENTRO do form de lançar produto, e o terminador do bipe
    // lançaria o item que estivesse selecionado.
    //
    // A asserção é sobre `defaultPrevented`, e não sobre um onSubmit que não foi chamado:
    // jsdom NÃO implementa submissão implícita por Enter, então um teste escrito daquele
    // jeito passa com ou sem o `preventDefault` — foi o que a mutação mostrou.
    render(<LeitorDeCodigo aoLer={vi.fn()} />);
    const campo = screen.getByLabelText('Código de barras');
    fireEvent.change(campo, { target: { value: '7891234567895' } });

    const seguiu = fireEvent.keyDown(campo, { key: 'Enter' });

    expect(seguiu).toBe(false);
  });

  it('duas leitoras na mesma tela não colidem de id', () => {
    // Com id fixo, o `label` de uma passaria a apontar para o campo da outra.
    render(
      <>
        <LeitorDeCodigo aoLer={vi.fn()} rotulo="Bipar produto" />
        <LeitorDeCodigo aoLer={vi.fn()} rotulo="Bipar lote" />
      </>,
    );

    const produto = screen.getByLabelText('Bipar produto');
    const lote = screen.getByLabelText('Bipar lote');
    expect(produto.id).not.toBe(lote.id);
    expect(produto).not.toBe(lote);
  });
});
