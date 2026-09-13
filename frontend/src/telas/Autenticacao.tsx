import type { ReactNode } from 'react';
import './autenticacao.css';

/**
 * A porta do sistema — senha, segundo fator e recuperação entram por aqui.
 *
 * <h2>Não é split-screen</h2>
 *
 * O painel escuro com slogan de um lado e formulário do outro é o layout de login mais
 * replicado que existe, e foi o que fazia esta tela parecer qualquer tela. Aqui é uma
 * coluna só, centrada: quem chega às 8h quer um campo e um botão, não uma promessa de
 * marketing sobre um produto que a pessoa já usa.
 *
 * <h2>A página é o livro de horários</h2>
 *
 * O fundo tem as linhas do livro de agendamento — traço fino na meia hora, mais forte
 * na hora cheia, na mesma cadência da grade. É o único ornamento da tela, ele é material
 * do ofício e não decoração abstrata, e é o que faz esta porta ser a porta DESTE
 * produto. Nada mais compete com o formulário.
 *
 * @param titulo  o que a pessoa veio fazer; vira o `h1` da página
 * @param apoio   uma frase, quando o passo precisa de explicação. Sem frase, sem espaço
 */
export function Autenticacao({ salao = 'Salão', titulo, apoio, children }: {
  salao?: string;
  titulo: string;
  apoio?: ReactNode;
  children: ReactNode;
}) {
  return (
    <main className="auth-tela">
      <div className="auth-coluna">
        <header className="auth-marca">
          {/* A mesma placa da casca: entrar e estar dentro são o mesmo produto. */}
          <span className="auth-placa" aria-hidden="true">{iniciais(salao)}</span>
          <span className="auth-marca-texto">
            <strong>{salao}</strong>
            <small>gestão do salão</small>
          </span>
        </header>

        <section className="auth-cartao">
          <h1 className="auth-titulo">{titulo}</h1>
          {apoio && <p className="auth-apoio">{apoio}</p>}
          {children}
        </section>
      </div>
    </main>
  );
}

function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return 'S';
  // Uma palavra vira UMA letra: "Salão" abreviado em "SA" não abrevia nada, só trunca.
  if (partes.length === 1) return partes[0].charAt(0).toLocaleUpperCase('pt-BR');
  return `${partes[0][0]}${partes.at(-1)?.[0] ?? ''}`.toLocaleUpperCase('pt-BR');
}
