import type { ReactNode } from 'react';
import { Conectividade } from '../pwa/Conectividade';
import { Assinatura } from '../layout/Simbolo';
import grandeN from '../assets/marca/nexo-n.webp';
import './autenticacao.css';

/**
 * A porta do sistema — senha, segundo fator e recuperação entram por aqui.
 *
 * <h2>Editorial, e com uma cor só (RT-INF-014, direção D)</h2>
 *
 * O dono escolheu, entre quatro direções desenhadas, a editorial: a noite da marca, uma
 * saudação grande, campos que são só uma linha, e o N da arte enorme, vazando pela borda.
 * E pediu a cor mínima: **a única cor da tela é o N**. Texto, campos e botão são branco e
 * dois cinzas azulados (`--noite-*` em `design/tokens.css`, contraste medido).
 *
 * Sem cartão: a folha clara era o que separava formulário de fundo; aqui o fundo já é
 * silencioso o bastante para o campo morar direto nele.
 *
 * <h2>Não é split-screen</h2>
 *
 * Nada de painel com slogan. O N não promete nada: é a marca, e é o único ornamento.
 *
 * @param titulo  o que a pessoa veio fazer; vira o `h1` da página
 * @param apoio   uma frase, quando o passo precisa de explicação. Sem frase, sem espaço
 */
export function Autenticacao({ titulo, apoio, children }: {
  titulo: string;
  apoio?: ReactNode;
  children: ReactNode;
}) {
  return (
    <main className="auth-tela">
      <img className="auth-n" src={grandeN} alt="" decoding="async" />

      <header className="auth-marca">
        <Assinatura altura={28} />
      </header>

      <section className="auth-conteudo" aria-labelledby="auth-titulo">
        <h1 id="auth-titulo" className="auth-titulo">{titulo}</h1>
        {apoio && <p className="auth-apoio">{apoio}</p>}
        {children}
      </section>

      {/* Sem casca, não há barra onde o estado do sistema possa morar: aqui ele flutua. E
          ele importa MAIS aqui — entrar é a única coisa que não dá para fazer offline. */}
      <Conectividade />
    </main>
  );
}
