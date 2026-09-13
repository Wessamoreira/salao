import { useQuery } from '@tanstack/react-query';
import { api } from './http';

export interface Menu {
  id: string;
  rotulo: string;
  rota: string;
  icone: string;
  /** Id de um `GrupoDeMenu`. Define em que seção da lateral o item aparece. */
  grupo: string;
}

/**
 * Uma seção da navegação lateral.
 *
 * Vem do servidor pela mesma razão que os menus vêm: agrupar aqui seria recriar no
 * JavaScript a regra que `/me/capabilities` existe para eliminar. A ordem da lista é a
 * ordem da tela e o rótulo é o que aparece — o front não traduz nem reordena.
 */
export interface GrupoDeMenu {
  id: string;
  rotulo: string;
}

/**
 * O contrato que faz o front não decidir nada (inviolável nº 6).
 *
 * Menus, limites e obrigatoriedade de MFA chegam prontos do servidor. Não existe
 * `if (perfil === 'ADMIN')` em lugar nenhum deste código — e as flags descrevem EFEITO
 * (`podeVerValorDeOutros`), não papel, para continuarem certas quando o mapa de permissões
 * mudar no backend.
 */
export interface Capacidades {
  usuarioId: string;
  nome: string;
  email: string;
  perfil: string;
  estabelecimento: { id: string; nome: string; timezone: string; moeda: string };
  permissoes: string[];
  grupos: GrupoDeMenu[];
  menus: Menu[];
  flags: Record<string, boolean>;
  limites: Record<string, number>;
  mfaAtivo: boolean;
  mfaObrigatorio: boolean;
}

export const chaveDasCapacidades = ['capacidades'] as const;

export function useCapacidades() {
  return useQuery({
    queryKey: chaveDasCapacidades,
    queryFn: () => api<Capacidades>('/v1/me/capabilities'),
    // Permissão muda pouco e a tela inteira depende disto; refazer a cada foco
    // de janela seria uma requisição por alt-tab.
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
}

/** Pergunta por permissão, nunca por perfil. */
export function pode(capacidades: Capacidades | undefined, permissao: string): boolean {
  return capacidades?.permissoes.includes(permissao) ?? false;
}
