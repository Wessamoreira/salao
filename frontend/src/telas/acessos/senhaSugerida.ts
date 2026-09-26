/**
 * A senha inicial que o administrador entrega a outra pessoa.
 *
 * <h2>Por que a tela gera</h2>
 *
 * O que alguém digita sozinho no campo "senha desta pessoa" é `Salao@2026`. Sortear tira a
 * decisão de quem não tem por que tomá-la.
 *
 * <h2>Por que palavras, e não dezesseis caracteres aleatórios</h2>
 *
 * Quatro palavras de uma lista de 96, mais dois dígitos, dão cerca de 33 bits. É pouco para um
 * hash roubado e muito para o que protege esta conta de verdade: o bloqueio progressivo depois de
 * quatro erros (RN-IAM-005) e, nos perfis que mandam no dinheiro, o segundo fator obrigatório.
 *
 * Esta senha vai viajar por WhatsApp ou por um papel, e vai ser digitada uma vez por alguém que
 * não a escolheu. Uma frase que se dita ao telefone sem soletrar vale mais, nesse caminho, do que
 * entropia que ninguém consegue transportar.
 *
 * Palavras curtas e sem acento de propósito: para não depender de teclado nem de ouvido.
 */
const PALAVRAS = [
  'sol', 'lua', 'mar', 'rio', 'ceu', 'flor', 'folha', 'galho',
  'pedra', 'areia', 'vento', 'chuva', 'nuvem', 'neve', 'fogo', 'terra',
  'campo', 'monte', 'vale', 'praia', 'ilha', 'ponte', 'porta', 'janela',
  'mesa', 'banco', 'livro', 'papel', 'tinta', 'pincel', 'tesoura', 'espelho',
  'escova', 'pente', 'toalha', 'vidro', 'barro', 'ferro', 'ouro', 'prata',
  'linho', 'seda', 'renda', 'linha', 'agulha', 'fivela', 'uva', 'figo',
  'caju', 'coco', 'pera', 'goiaba', 'banana', 'abacate', 'cereja', 'amora',
  'manga', 'gato', 'lobo', 'urso', 'peixe', 'gaivota', 'coruja', 'tucano',
  'pomba', 'abelha', 'formiga', 'cavalo', 'barco', 'remo', 'vela', 'farol',
  'mapa', 'estrada', 'trilha', 'roda', 'carro', 'trem', 'tambor', 'flauta',
  'corda', 'ritmo', 'bolo', 'queijo', 'mel', 'sal', 'arroz', 'milho',
  'trigo', 'azeite', 'canela', 'menta', 'cravo', 'telhado', 'parede', 'escada',
];

/** Quantas palavras a frase tem. Quatro passa dos 12 caracteres que o servidor exige. */
const PALAVRAS_NA_FRASE = 4;

export function senhaSugerida(): string {
  const partes: string[] = [];
  for (let i = 0; i < PALAVRAS_NA_FRASE; i += 1) {
    partes.push(PALAVRAS[aleatorio(PALAVRAS.length)]);
  }
  partes.push(String(aleatorio(100)).padStart(2, '0'));
  return partes.join('-');
}

/**
 * Inteiro uniforme em `[0, limite)` — com rejeição, e não com resto.
 *
 * `valor % 96` sozinho favoreceria as primeiras palavras da lista, porque 2³² não é múltiplo de
 * 96. O viés é pequeno e não muda quase nada aqui; descartar o rabo custa uma linha e evita ter
 * de explicar "quase nada" depois.
 */
function aleatorio(limite: number): number {
  const teto = Math.floor(2 ** 32 / limite) * limite;
  const caixa = new Uint32Array(1);
  let valor = 0;
  do {
    crypto.getRandomValues(caixa);
    valor = caixa[0];
  } while (valor >= teto);
  return valor % limite;
}
