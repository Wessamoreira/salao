import { createContext } from 'react';

/**
 * O alvo das ações da página, dentro da barra de topo da casca.
 *
 * <h2>Por que contexto e não `getElementById`</h2>
 *
 * O `div` de destino é montado pela casca no mesmo passo de render em que o `Outlet`
 * desenha a tela filha. Buscar por id daria `null` na primeira passada e as ações só
 * apareceriam no segundo render — piscando. Com um ref de callback num estado, a casca
 * re-renderiza uma vez quando o nó existe, e o portal encontra o destino pronto.
 *
 * Em arquivo próprio porque contexto não é componente: junto dos componentes, ele quebra
 * o recarregamento a quente do Vite em toda edição de tela.
 */
export const DestinoDasAcoes = createContext<HTMLElement | null>(null);
