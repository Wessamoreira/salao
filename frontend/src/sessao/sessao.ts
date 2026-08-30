import { create } from 'zustand';

/**
 * O access token vive SÓ em memória.
 *
 * `localStorage` é proibido no projeto (inviolável nº 6 do CLAUDE.md): é XSS servido de
 * bandeja — qualquer script injetado lê e leva embora. Em memória, um XSS consegue usar a
 * sessão enquanto a aba está aberta, mas não a carrega para outro lugar.
 *
 * O custo é recarregar a página perder o token — e é exatamente por isso que existe o
 * refresh em cookie `HttpOnly`: no boot, o front troca o cookie por um token novo. O cookie
 * o JavaScript não lê, então nem um XSS o alcança.
 */
interface EstadoDaSessao {
  token: string | null;
  definirToken: (token: string | null) => void;
  encerrar: () => void;
}

export const useSessao = create<EstadoDaSessao>((set) => ({
  token: null,
  definirToken: (token) => set({ token }),
  encerrar: () => set({ token: null }),
}));

/** Leitura fora de React — o cliente HTTP não é um componente. */
export const tokenAtual = () => useSessao.getState().token;
export const definirTokenAtual = (token: string | null) =>
  useSessao.getState().definirToken(token);
