import { Pagina, Vazio } from '../layout/Pagina';

/**
 * Marcador honesto de tela que ainda não existe.
 *
 * Diz qual rotina a entrega, em vez de fingir conteúdo: uma tela com dados falsos parece
 * pronta e engana quem revisa — inclusive o dono do salão, numa demonstração.
 */
export function EmBreve({ titulo, rotina }: { titulo: string; rotina: string }) {
  return (
    <Pagina>
      <Vazio icone="caixa" titulo={`${titulo} ainda não está pronto`}>
        Esta área chega com a rotina <code>{rotina}</code>. Até lá, nada aqui é dado real —
        e é de propósito: tela com número inventado parece pronta e engana quem revisa.
      </Vazio>
    </Pagina>
  );
}
