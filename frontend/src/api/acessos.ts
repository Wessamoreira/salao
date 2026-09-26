import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';
import { chaveDasCapacidades } from './capacidades';

export type Perfil = 'ADMIN' | 'GERENTE' | 'RECEPCAO' | 'PROFISSIONAL' | 'PAINEL' | 'BOT';

export interface Usuario {
  id: string;
  nome: string;
  email: string;
  /** RT-CNV-002 — E.164. Nulo para quem não usa o agente, que é quase todo mundo. */
  telefone: string | null;
  perfil: Perfil;
  ativo: boolean;
  mfaAtivo: boolean;
  /** Nulo é "criou e nunca entrou" — quase sempre quer dizer que a senha não chegou. */
  ultimoAcessoEm: string | null;
}

/**
 * O que um perfil concede, como o servidor o descreve.
 *
 * As frases vêm de lá de propósito (RT-IAM-009): um dicionário de permissões aqui seria a
 * segunda cópia do mapa do servidor, e a segunda cópia envelhece calada.
 */
export interface PerfilDisponivel {
  perfil: Perfil;
  rotulo: string;
  resumo: string;
  exigeMfa: boolean;
  podeFazer: string[];
}

const CHAVE = ['acessos'] as const;

export function useUsuarios() {
  return useQuery({
    queryKey: [...CHAVE, 'usuarios'],
    queryFn: () => api<Usuario[]>('/v1/usuarios'),
  });
}

export function usePerfis() {
  return useQuery({
    queryKey: [...CHAVE, 'perfis'],
    // O catálogo só muda quando o mapa de permissões do servidor mudar — o que exige um
    // deploy. Buscá-lo a cada abertura da tela seria uma requisição por curiosidade.
    staleTime: 30 * 60 * 1000,
    queryFn: () => api<PerfilDisponivel[]>('/v1/perfis'),
  });
}

/** Invalida a lista e, quando o alvo é quem está olhando, o próprio menu. */
function aoEscrever(fila: ReturnType<typeof useQueryClient>) {
  return () => {
    void fila.invalidateQueries({ queryKey: CHAVE });
    void fila.invalidateQueries({ queryKey: chaveDasCapacidades });
  };
}

export interface NovoAcesso {
  nome: string;
  email: string;
  senha: string;
  perfil: Perfil;
}

export function useCriarAcesso() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: NovoAcesso) =>
      api<void>('/v1/usuarios', { method: 'POST', body: JSON.stringify(dados) }),
    onSuccess: aoEscrever(fila),
  });
}

/**
 * RN-IAM-024 — corrigir a ficha: nome e e-mail, e mais nada.
 *
 * Perfil, senha e ativação têm gancho próprio porque são **atos**, cada um com efeito que a
 * pessoa sente: o perfil e a senha derrubam a sessão dela. Escondê-los dentro de "salvar"
 * faria um botão manso produzir uma consequência que ninguém pediu.
 */
export function useEditarUsuario() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...dados }: { id: string; nome: string; email: string }) =>
      api<void>(`/v1/usuarios/${id}`, { method: 'PUT', body: JSON.stringify(dados) }),
    onSuccess: aoEscrever(fila),
  });
}

export function useDefinirPerfil() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, perfil }: { id: string; perfil: Perfil }) =>
      api<void>(`/v1/usuarios/${id}/perfil`, {
        method: 'PATCH', body: JSON.stringify({ perfil }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useDefinirAtivacao() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ativo }: { id: string; ativo: boolean }) =>
      api<void>(`/v1/usuarios/${id}/ativacao`, {
        method: 'PATCH', body: JSON.stringify({ ativo }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/** RN-IAM-025 — senha nova para outra pessoa; as sessões dela caem. */
export function useDefinirSenha() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, senha }: { id: string; senha: string }) =>
      api<void>(`/v1/usuarios/${id}/senha`, {
        method: 'POST', body: JSON.stringify({ senha }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

export function useResetarSegundoFator() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      api<void>(`/v1/usuarios/${id}/resetar-mfa`, { method: 'POST' }),
    onSuccess: aoEscrever(fila),
  });
}

/** RN-IAM-022 — o telefone pelo qual o agente reconhece esta pessoa. Vazio o remove. */
export function useDefinirTelefoneDoUsuario() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: ({ id, telefone }: { id: string; telefone: string }) =>
      api<void>(`/v1/usuarios/${id}/telefone`, {
        method: 'PUT', body: JSON.stringify({ telefone }),
      }),
    onSuccess: aoEscrever(fila),
  });
}

/**
 * "entrou hoje, 08:12" · "entrou há 3 dias" · "entrou em 02/04/2026".
 *
 * Exige um instante: **nunca ter entrado não é um último acesso**, é uma pendência — quase
 * sempre a senha que não chegou —, e quem decide como ela aparece é a tela, que a marca. Aceitar
 * nulo aqui faria a mesma frase existir em dois lugares, e o segundo é o que ninguém atualiza.
 */
export function ultimoAcesso(iso: string, agora = new Date()): string {
  const quando = new Date(iso);
  const dias = Math.floor(
    (dataSimples(agora).getTime() - dataSimples(quando).getTime()) / 86_400_000);

  if (dias === 0) {
    const hora = quando.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    return `entrou hoje, ${hora}`;
  }
  if (dias === 1) return 'entrou ontem';
  if (dias < 30) return `entrou há ${dias} dias`;
  return `entrou em ${quando.toLocaleDateString('pt-BR')}`;
}

function dataSimples(quando: Date): Date {
  return new Date(quando.getFullYear(), quando.getMonth(), quando.getDate());
}
