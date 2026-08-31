import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';

export interface Cliente {
  id: string;
  nome: string;
  apelido: string | null;
  telefone: string | null;
  email: string | null;
}

/**
 * O que o servidor entendeu do termo, junto com o que achou.
 *
 * A interpretação vem de lá de propósito: reimplementar "dígito significa telefone"
 * (RN-CLI-009) aqui seria a regra de domínio escrita duas vezes, e a segunda cópia
 * divergiria em silêncio — mostrando na tela algo diferente do que a consulta fez.
 * O mesmo vale para `aviso`: "informe ao menos 2 letras" é texto do domínio.
 */
export interface ResultadoDaBusca {
  interpretacao: 'TELEFONE' | 'NOME' | 'INSUFICIENTE';
  aviso: string | null;
  itens: Cliente[];
}

export type Grau = 'PROVAVEL' | 'POSSIVEL' | 'MESMO_TELEFONE';

export interface PossivelDuplicado {
  cliente: Cliente;
  grau: Grau;
}

export type Finalidade = 'LEMBRETE_DE_AGENDAMENTO' | 'MARKETING' | 'PESQUISA_DE_SATISFACAO';
export type Canal = 'BALCAO' | 'WHATSAPP' | 'TELEFONE' | 'SITE' | 'FICHA_PAPEL' | 'IMPORTACAO';

export interface Consentimento {
  finalidade: Finalidade;
  baseLegal: 'EXECUCAO_DE_CONTRATO' | 'CONSENTIMENTO';
  decisao: 'CONCEDIDO' | 'RECUSADO' | 'SEM_REGISTRO';
  podeReceber: boolean;
  desde: string | null;
  canal: Canal | null;
}

export function useBusca(termo: string) {
  return useQuery({
    queryKey: ['clientes', 'busca', termo],
    queryFn: () => api<ResultadoDaBusca>(`/v1/clientes?q=${encodeURIComponent(termo)}`),
    // Sem termo não há requisição: o endpoint devolveria vazio, e pedir isso a cada
    // montagem da tela seria uma chamada que já se sabe inútil.
    enabled: termo.trim().length > 0,
    // A recepção digita, lê, apaga e digita de novo. Manter o resultado anterior
    // enquanto o novo chega evita a lista piscar entre teclas.
    placeholderData: (anterior) => anterior,
    staleTime: 30 * 1000,
  });
}

export function useCliente(id: string | null) {
  return useQuery({
    queryKey: ['clientes', id],
    queryFn: () => api<Cliente>(`/v1/clientes/${id}`),
    enabled: id !== null,
  });
}

export function useConsentimentos(id: string | null) {
  return useQuery({
    queryKey: ['clientes', id, 'consentimentos'],
    queryFn: () => api<Consentimento[]>(`/v1/clientes/${id}/consentimentos`),
    enabled: id !== null,
  });
}

/** Enquanto se digita: quem já existe e talvez seja esta pessoa (RT-CLI-003). */
export function useDuplicados(nome: string, telefone: string) {
  const parametros = new URLSearchParams();
  if (nome.trim()) parametros.set('nome', nome.trim());
  if (telefone.trim()) parametros.set('telefone', telefone.trim());

  return useQuery({
    queryKey: ['clientes', 'duplicados', nome.trim(), telefone.trim()],
    queryFn: () => api<PossivelDuplicado[]>(`/v1/clientes/possiveis-duplicados?${parametros}`),
    enabled: nome.trim().length >= 2 || telefone.trim().length >= 3,
    staleTime: 30 * 1000,
  });
}

export function useCadastrar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: { nome: string; telefone?: string }) =>
      api<void>('/v1/clientes', { method: 'POST', body: JSON.stringify(dados) }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['clientes'] }),
  });
}

export function useRegistrarConsentimento(clienteId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: { finalidade: Finalidade; concedido: boolean; canal: Canal }) =>
      api<Consentimento[]>(`/v1/clientes/${clienteId}/consentimentos/${dados.finalidade}`, {
        method: 'PUT',
        body: JSON.stringify({ concedido: dados.concedido, canal: dados.canal }),
      }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['clientes', clienteId] }),
  });
}

/** RT-CLI-005 — apaga os dados a pedido da titular. Não tem desfazer. */
export function useAnonimizar() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api<void>(`/v1/clientes/${id}`, { method: 'DELETE' }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['clientes'] }),
  });
}

export function useMesclar(sobreviventeId: string) {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (absorvidaId: string) =>
      api<Cliente>(`/v1/clientes/${sobreviventeId}/mesclar`, {
        method: 'POST',
        body: JSON.stringify({ absorvidaId }),
      }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['clientes'] }),
  });
}

/**
 * Formata E.164 para leitura. O banco guarda só dígitos de propósito (RN-CLI-001);
 * a máscara é assunto de quem exibe.
 */
export function formatarTelefone(e164: string | null): string {
  if (!e164) return '';
  const nacional = e164.startsWith('55') ? e164.slice(2) : e164;
  if (nacional.length === 11) {
    return `(${nacional.slice(0, 2)}) ${nacional.slice(2, 7)}-${nacional.slice(7)}`;
  }
  if (nacional.length === 10) {
    return `(${nacional.slice(0, 2)}) ${nacional.slice(2, 6)}-${nacional.slice(6)}`;
  }
  return e164;
}

/** Como a pessoa é chamada no salão. "Dona Zilda" é o que a recepção procura. */
export function comoChamar(cliente: Cliente): string {
  return cliente.apelido?.trim() || cliente.nome;
}
