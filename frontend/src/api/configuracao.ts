import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './http';
import { chaveDasCapacidades } from './capacidades';

export type BaseDeComissao = 'BRUTO' | 'LIQUIDO';
export type Periodicidade = 'SEMANAL' | 'QUINZENAL' | 'MENSAL';

export interface Configuracao {
  id: string;
  nome: string;
  fuso: string;
  moeda: string;
  baseComissao: BaseDeComissao;
  descontoAfetaComissao: boolean;
  periodicidadeDeFechamento: Periodicidade;
  paisPadrao: string;
  dddPadrao: string | null;
  ativo: boolean;
}

export function useConfiguracao() {
  return useQuery({
    queryKey: ['configuracao'],
    queryFn: () => api<Configuracao>('/v1/configuracao'),
  });
}

export function useSalvarConfiguracao() {
  const fila = useQueryClient();
  return useMutation({
    mutationFn: (dados: {
      nome: string; documento: string | null; fusoIana: string; moeda: string;
      baseComissao: BaseDeComissao; descontoAfetaComissao: boolean;
      periodicidadeDeFechamento: Periodicidade; dddPadrao: string | null;
    }) => api<Configuracao>('/v1/configuracao', { method: 'PUT', body: JSON.stringify(dados) }),
    onSuccess: () => {
      fila.invalidateQueries({ queryKey: ['configuracao'] });
      // O nome do salão aparece na casca, que vem de /me/capabilities: sem isto, ele
      // continuaria antigo no canto da tela até alguém recarregar a página.
      fila.invalidateQueries({ queryKey: chaveDasCapacidades });
    },
  });
}

/**
 * Os fusos que um salão brasileiro pode ter.
 *
 * Lista curada, não o catálogo IANA inteiro: são as horas legais do Brasil, e uma
 * caixa com seiscentos fusos faria alguém escolher errado com mais facilidade do que
 * escolher certo. O domínio recusa offset fixo (`-03:00`) de qualquer forma, porque
 * offset não conhece horário de verão.
 */
export const FUSOS: { valor: string; rotulo: string }[] = [
  { valor: 'America/Sao_Paulo', rotulo: 'Brasília (SP, RJ, MG, Sul, Centro-Oeste)' },
  { valor: 'America/Bahia', rotulo: 'Bahia' },
  { valor: 'America/Fortaleza', rotulo: 'Nordeste (CE, PE, PB, RN, PI, AL, SE, MA)' },
  { valor: 'America/Belem', rotulo: 'Pará e Amapá' },
  { valor: 'America/Manaus', rotulo: 'Amazonas, Roraima, Rondônia, Mato Grosso' },
  { valor: 'America/Cuiaba', rotulo: 'Mato Grosso' },
  { valor: 'America/Rio_Branco', rotulo: 'Acre' },
  { valor: 'America/Noronha', rotulo: 'Fernando de Noronha' },
];
