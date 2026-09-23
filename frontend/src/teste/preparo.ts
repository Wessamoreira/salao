import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

/*
 * Três segundos para o `findBy`, não um.
 *
 * O padrão de 1s é apertado demais quando a suíte disputa CPU — foi o que aconteceu ao
 * entrar a verificação de acessibilidade, que é pesada: dois testes que nada tinham a ver
 * com ela começaram a falhar por tempo, em execuções alternadas. Teste instável custa mais
 * caro que teste lento, porque ensina a ignorar vermelho.
 *
 * Isto NÃO afrouxa asserção: `findBy` devolve assim que o elemento aparece. O prazo só
 * decide quando desistir.
 */
configure({ asyncUtilTimeout: 3000 });

/*
 * Buracos do jsdom, não do produto.
 *
 * `scrollIntoView` não existe no jsdom e existe em todo navegador. Protegê-lo no código
 * com `?.()` seria carregar para sempre um remendo de ambiente de teste dentro da
 * aplicação — e o próximo a ler acharia que há navegador sem o método.
 */
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}
