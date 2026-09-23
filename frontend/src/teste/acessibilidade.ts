import axe from 'axe-core';
import { expect } from 'vitest';

/**
 * Acessibilidade medida, não opinada (F3 do checklist do front).
 *
 * Roda o axe da Deque sobre o que a tela renderizou e falha listando a violação em
 * português do defeito: qual regra, em que elemento, e o link da explicação. É o mesmo
 * motor que a extensão do navegador usa.
 *
 * <h2>O que ele pega e o que não pega</h2>
 *
 * Pega o que é estrutural: rótulo faltando, contraste insuficiente, cabeçalho fora de
 * ordem, botão sem nome acessível, `aria-*` inválido. **Não** pega o que depende de
 * julgamento — se o texto do rótulo faz sentido, se a ordem de tabulação é a ordem da
 * tarefa, se a mensagem de erro diz o que fazer. Isso continua sendo revisão humana, e é
 * por isso que este auxiliar não substitui os testes de comportamento.
 *
 * <h2>Contraste fica de fora aqui</h2>
 *
 * O jsdom não calcula cor computada de verdade: a regra de contraste passaria sempre, o
 * que é pior do que não rodar — daria a impressão de estar medida. O contraste está medido
 * à mão, com a fórmula da WCAG, na tabela de `16-design-system.md`.
 */
export async function deveSerAcessivel(raiz: HTMLElement): Promise<void> {
  const resultado = await axe.run(raiz, {
    resultTypes: ['violations'],
    rules: {
      'color-contrast': { enabled: false },
      // A prévia de teste monta um pedaço da tela, não a página inteira: exigir landmark
      // e <h1> aqui reprovaria o recorte, não o produto.
      region: { enabled: false },
      'page-has-heading-one': { enabled: false },
      'landmark-one-main': { enabled: false },
    },
  });

  if (resultado.violations.length > 0) {
    const descricao = resultado.violations
      .map((violacao) => {
        const onde = violacao.nodes.map((no) => `      ${no.html}`).join('\n');
        return `  • ${violacao.id} (${violacao.impact}): ${violacao.help}\n`
          + `    ${violacao.helpUrl}\n${onde}`;
      })
      .join('\n');
    expect.fail(`${resultado.violations.length} violação(ões) de acessibilidade:\n${descricao}`);
  }
}
