import '@testing-library/jest-dom/vitest';

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
