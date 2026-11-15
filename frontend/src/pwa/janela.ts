import { useSyncExternalStore } from 'react';

/**
 * A janela instalada está usando a própria barra de título? (Window Controls Overlay)
 *
 * O CSS sozinho já acerta o desenho — `env(titlebar-area-height)` vale zero fora dela. O que
 * ele não decide é se a faixa EXISTE: com a faixa sempre no DOM, o nome da seção e o do
 * salão apareciam duas vezes para leitor de tela e busca de texto. E a pessoa pode ligar e
 * desligar a barra com a janela aberta (o botão de seta ao lado dos controles), então não
 * é um valor lido uma vez no carregamento.
 *
 * Só o Chromium implementa; nos outros, `windowControlsOverlay` não existe e a resposta é não.
 */
interface SobreposicaoDaJanela extends EventTarget { visible: boolean }

function sobreposicao(): SobreposicaoDaJanela | undefined {
  if (typeof navigator === 'undefined') return undefined;
  return (navigator as Navigator & { windowControlsOverlay?: SobreposicaoDaJanela })
    .windowControlsOverlay;
}

function assinar(avisar: () => void): () => void {
  const alvo = sobreposicao();
  alvo?.addEventListener('geometrychange', avisar);
  return () => alvo?.removeEventListener('geometrychange', avisar);
}

export function useBarraDeTituloPropria(): boolean {
  return useSyncExternalStore(assinar, () => Boolean(sobreposicao()?.visible), () => false);
}
