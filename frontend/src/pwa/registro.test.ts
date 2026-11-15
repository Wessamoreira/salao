import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/*
 * R-UX-23 — versão nova não assume no meio do trabalho.
 *
 * O ciclo do service worker montado com EventTarget: o jsdom não tem service worker, e o que
 * importa provar aqui é a ORDEM — esperar, pedir, recarregar uma vez — e não o navegador.
 */

class Trabalhador extends EventTarget {
  state = 'installing';
  postMessage = vi.fn();
}

function cenario({ haviaVersao, esperando }: { haviaVersao: boolean; esperando?: Trabalhador }) {
  const sw = Object.assign(new EventTarget(), {
    controller: haviaVersao ? new Trabalhador() : null,
  }) as unknown as ServiceWorkerContainer;
  const reg = Object.assign(new EventTarget(), {
    waiting: esperando ?? null,
    installing: null as Trabalhador | null,
    update: vi.fn(async () => undefined),
  });
  // O registro real e o dublê só precisam concordar no que `acompanhar` usa.
  const real = reg as unknown as ServiceWorkerRegistration;
  const recarregar = vi.fn();
  return { sw, reg, real, recarregar };
}

/** Estado de módulo limpo a cada teste: o registro guarda o que viu. */
async function carregar() {
  vi.resetModules();
  return import('./registro');
}

describe('versão nova do app', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('espera o clique: baixada, aparece o aviso, e nada recarrega sozinho', async () => {
    const { acompanhar, aplicarAtualizacao, useEstadoDoApp } = await carregar();
    const esperando = new Trabalhador();
    const { sw, real, recarregar } = cenario({ haviaVersao: true, esperando });

    acompanhar(real, sw, recarregar);

    const { renderHook } = await import('@testing-library/react');
    const { result } = renderHook(() => useEstadoDoApp());
    expect(result.current.versaoNova).toBe(true);
    expect(esperando.postMessage).not.toHaveBeenCalled();
    expect(recarregar).not.toHaveBeenCalled();

    aplicarAtualizacao();
    expect(esperando.postMessage).toHaveBeenCalledWith({ tipo: 'atualizar' });

    // A versão nova assumiu: recarrega uma vez, mesmo que o evento chegue de novo.
    sw.dispatchEvent(new Event('controllerchange'));
    sw.dispatchEvent(new Event('controllerchange'));
    expect(recarregar).toHaveBeenCalledTimes(1);
  });

  it('uma versão que chega durante o uso também espera', async () => {
    const { acompanhar, useEstadoDoApp } = await carregar();
    const { sw, reg, real, recarregar } = cenario({ haviaVersao: true });
    acompanhar(real, sw, recarregar);

    const { renderHook, act } = await import('@testing-library/react');
    const { result } = renderHook(() => useEstadoDoApp());
    expect(result.current.versaoNova).toBe(false);

    const chegando = new Trabalhador();
    reg.installing = chegando;
    act(() => {
      reg.dispatchEvent(new Event('updatefound'));
      chegando.state = 'installed';
      chegando.dispatchEvent(new Event('statechange'));
    });

    expect(result.current.versaoNova).toBe(true);
    expect(recarregar).not.toHaveBeenCalled();
  });

  it('na primeira instalação não há aviso nem recarga', async () => {
    // Sem controlador não existe versão velha rodando. O `clients.claim()` da primeira
    // instalação dispara `controllerchange`; recarregar ali piscaria a tela de quem chegou.
    const { acompanhar, useEstadoDoApp } = await carregar();
    const { sw, real, recarregar } = cenario({ haviaVersao: false, esperando: new Trabalhador() });
    acompanhar(real, sw, recarregar);

    const { renderHook } = await import('@testing-library/react');
    expect(renderHook(() => useEstadoDoApp()).result.current.versaoNova).toBe(false);

    sw.dispatchEvent(new Event('controllerchange'));
    expect(recarregar).not.toHaveBeenCalled();
  });

  it('procura versão nova a cada hora, sem ninguém navegar', async () => {
    // O balcão abre às 8h e não recarrega o documento o dia inteiro.
    const { acompanhar } = await carregar();
    const { sw, reg, real, recarregar } = cenario({ haviaVersao: true });
    acompanhar(real, sw, recarregar);

    expect(reg.update).not.toHaveBeenCalled();
    vi.advanceTimersByTime(60 * 60_000);
    expect(reg.update).toHaveBeenCalledTimes(1);
  });
});
