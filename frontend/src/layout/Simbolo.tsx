import icone from '../assets/marca/nexo-icone.png';
import assinatura from '../assets/marca/nexo-assinatura.png';

/*
 * A marca do produto: Nexo — RT-INF-014.
 *
 * As duas peças saem das artes originais do dono (`design/marca/original/`), preparadas por
 * `design/marca/preparar-marca.py`: o ícone com os cantos transparentes, a assinatura sem o
 * fundo. Importadas, e não referenciadas por `/caminho`: assim o Vite dá a elas o hash do
 * build e o service worker as guarda junto com o app — a porta do sistema abre offline.
 *
 * As duas são para fundo escuro. A arte é luz sobre noite; sobre o papel, o brilho some e
 * o nome branco desaparece.
 */

/** O ícone do app, a placa inteira — o mesmo desenho que a pessoa tocou para abrir. */
export function Simbolo({ tamanho = 40 }: { tamanho?: number }) {
  return <img src={icone} width={tamanho} height={tamanho} alt="" decoding="async" />;
}

/**
 * O N e o nome. `alt="Nexo"`: é o nome do produto, e para quem não vê a tela a imagem É o
 * texto. Altura pedida; a largura segue a proporção da arte (603×156).
 */
export function Assinatura({ altura = 48 }: { altura?: number }) {
  return (
    <img src={assinatura} height={altura} width={Math.round(altura * 603 / 156)} alt="Nexo"
      decoding="async" />
  );
}
