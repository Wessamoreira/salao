import { useEffect, useState } from 'react';

/**
 * Segura o termo por um instante antes de buscar.
 *
 * Sem isso, "Maria" dispara cinco consultas e a quinta pode chegar antes da terceira — a
 * lista pisca e às vezes assenta no resultado errado. 220ms é curto o bastante para não
 * ser sentido por quem digita e longo o bastante para o teclado inteiro caber dentro.
 *
 * Nasceu na tela de clientes e saiu de lá quando a busca da casca (⌘K) passou a procurar
 * cliente também: duas caixas de busca com pausas diferentes seriam duas velocidades para
 * a mesma consulta, e a segunda cópia divergiria na primeira vez que alguém ajustasse uma.
 */
export function useTermoComPausa(termo: string, espera = 220): string {
  const [estavel, definirEstavel] = useState(termo);

  useEffect(() => {
    const prazo = setTimeout(() => definirEstavel(termo), espera);
    return () => clearTimeout(prazo);
  }, [termo, espera]);

  return estavel;
}
