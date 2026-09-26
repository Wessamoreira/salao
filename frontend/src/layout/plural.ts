/**
 * `1 lote` · `3 lotes` · `2 pessoas`.
 *
 * O "(s)" é o jeito de não decidir, e quem lê é que paga: "1 lote(s) contados" e "para 1
 * pessoa(s)" são frases que ninguém escreveria à mão.
 *
 * Vive no layout, e não num módulo de API, porque é redação de tela — e porque a segunda cópia
 * dela apareceu no financeiro dois dias depois da primeira, no estoque.
 */
export function plural(n: number, um: string, varios: string): string {
  return `${n} ${n === 1 ? um : varios}`;
}
