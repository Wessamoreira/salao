import type { ReactNode } from 'react';

/**
 * Uma seção de tela: título, uma linha de ajuda e o conteúdo.
 *
 * <h2>Por que ela existe</h2>
 *
 * Conciliação, preços e fila tinham a **mesma caixa branca copiada três vezes** —
 * `.cnc-bloco`, `.prc-bloco`, `.fil-bloco`, idênticas até no `padding`. Três definições da
 * mesma coisa divergem no dia em que alguém ajusta uma.
 *
 * <h2>Seção não é caixa</h2>
 *
 * E as três eram caixa branca de canto arredondado com 24px de padding — exatamente o que
 * `16-design-system.md` proíbe na lista final ("espaçamento uniforme de 24px · canto muito
 * arredondado"). Pilha de caixas iguais é o retrato do que foi gerado sem decidir nada.
 *
 * Aqui a seção é **estrutura**: título, régua fina e ar. O papel branco fica reservado ao
 * que é **objeto** — o cartão de uma sugestão, a vaga com quem ligar, a linha do extrato.
 * Assim a tela tem dois níveis de leitura em vez de um: o que é seção e o que é coisa.
 *
 * @param acoes o que a seção permite fazer, alinhado à direita do título
 * @param ajuda uma linha, não um parágrafo: se precisar de dois, o lugar é outro
 */
export function Secao({ titulo, ajuda, acoes, children, id }: {
  titulo: string;
  ajuda?: ReactNode;
  acoes?: ReactNode;
  children: ReactNode;
  id?: string;
}) {
  const idDoTitulo = id ? `${id}-titulo` : undefined;

  return (
    <section className="secao-tela" aria-labelledby={idDoTitulo}>
      <header className="secao-tela-topo">
        <div className="secao-tela-nome">
          <h2 id={idDoTitulo} className="secao-tela-titulo">{titulo}</h2>
          {ajuda && <p className="secao-tela-ajuda">{ajuda}</p>}
        </div>
        {acoes && <div className="secao-tela-acoes">{acoes}</div>}
      </header>
      {children}
    </section>
  );
}
