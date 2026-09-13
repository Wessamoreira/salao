import { Component, type ErrorInfo, type ReactNode } from 'react';
import './barreira.css';

/**
 * A rede embaixo do trapézio.
 *
 * <h2>Tela branca é o pior defeito possível</h2>
 *
 * Quando um componente estoura durante o render, o React desmonta a árvore INTEIRA e
 * sobra `<body>` vazio. Para quem está no balcão com a cliente esperando, não há
 * diferença entre "o sistema quebrou" e "a internet caiu" e "eu cliquei errado" — e a
 * única saída que a pessoa conhece é ligar para alguém.
 *
 * Aconteceu aqui: o front passou a ler `capacidades.grupos` e o backend em execução era
 * de antes do campo existir. Um `undefined.map` num menu levou junto a agenda, as
 * comandas e o financeiro.
 *
 * <h2>O que ela mostra</h2>
 *
 * O que aconteceu, o que fazer agora, e a mensagem técnica atrás de um `<details>` — que
 * é o que a pessoa vai copiar quando pedir ajuda. Nada de "Ops!" nem de desculpa: erro
 * não pede perdão, ele diz o estado e o próximo passo.
 *
 * <h2>Duas, e não uma</h2>
 *
 * A de fora (em `App`) é a última rede: pega falha ao montar o roteador, a sessão, o
 * provedor de consultas. A de dentro (em `Casca`, ao redor do `Outlet`) pega falha de uma
 * TELA — e essa distinção é o que importa na prática: com uma barreira só, uma tela
 * quebrada apagava a lateral junto, e a pessoa perdia até o caminho para sair dali. Com
 * a de dentro, o menu continua funcionando e é só clicar em outra seção.
 *
 * A de dentro é montada com `key={pathname}`: trocar de rota a remonta, então o erro
 * limpa sozinho. Sem isso, uma tela que falhou uma vez ficaria mostrando o erro para
 * sempre — inclusive depois de voltar nela com o problema já resolvido.
 *
 * <h2>Onde ela NÃO substitui tratamento de erro</h2>
 *
 * Falha de rede, 403 e sessão expirada continuam sendo tratadas onde acontecem, com
 * mensagem própria. Isto aqui é para o que ninguém previu — e é justamente por ser para
 * o imprevisto que ele não pode ser um `catch` silencioso: o erro vai para o console
 * inteiro, para o `sw`/observabilidade encontrarem depois.
 */
interface Estado { erro: Error | null }

interface Propriedades {
  children: ReactNode;
  /** `tela` ocupa a janela (fora da casca); `pagina` ocupa só a área de trabalho. */
  variante?: 'tela' | 'pagina';
}

export class Barreira extends Component<Propriedades, Estado> {
  state: Estado = { erro: null };

  static getDerivedStateFromError(erro: Error): Estado {
    return { erro };
  }

  componentDidCatch(erro: Error, info: ErrorInfo) {
    // Console, e não engolido: quem for investigar precisa da pilha de componentes.
    console.error('Falha de render capturada pela barreira', erro, info.componentStack);
  }

  render() {
    if (!this.state.erro) return this.props.children;

    const naPagina = this.props.variante === 'pagina';

    return (
      <div className={`barreira${naPagina ? ' na-pagina' : ''}`} role="alert">
        <div className="barreira-caixa">
          <h2>Esta tela não carregou</h2>
          <p>
            O sistema encontrou um estado que não sabia desenhar. Nada do que você fez foi
            perdido.
            {naPagina
              ? ' As outras seções continuam funcionando — o menu à esquerda segue valendo.'
              : ' Recarregar costuma resolver.'}
          </p>
          <button type="button" onClick={() => window.location.reload()}>
            Recarregar
          </button>
          <details>
            <summary>Detalhe técnico</summary>
            <code>{this.state.erro.message}</code>
          </details>
        </div>
      </div>
    );
  }
}
