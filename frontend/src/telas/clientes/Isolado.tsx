import { Component, type ReactNode } from 'react';

/**
 * Uma falha na ficha não pode derrubar a busca.
 *
 * Sem isto, qualquer erro ao desenhar a ficha apaga a tela inteira — e quem está com o
 * telefone tocando perde também a única coisa de que precisa naquele segundo. Foi o que
 * aconteceu na prévia, e a página em branco não dizia nada.
 *
 * O escopo é deliberadamente estreito: envolve a ficha, não a tela. Um limite em volta
 * de tudo esconderia a falha inteira em vez de conter o estrago.
 */
export class Isolado extends Component<{ children: ReactNode }, { quebrou: boolean }> {
  state = { quebrou: false };

  static getDerivedStateFromError() {
    return { quebrou: true };
  }

  componentDidCatch(erro: unknown) {
    // Console, não tela: a mensagem do erro é para quem investiga, e pode conter detalhe
    // interno que não se mostra a quem está atendendo.
    console.error('Falha ao desenhar a ficha', erro);
  }

  render() {
    if (this.state.quebrou) {
      return (
        <div className="cli-ficha">
          <p className="cli-erro">
            Não foi possível abrir esta ficha. A busca continua funcionando — escolha de novo,
            ou recarregue a página.
          </p>
        </div>
      );
    }
    return this.props.children;
  }
}
