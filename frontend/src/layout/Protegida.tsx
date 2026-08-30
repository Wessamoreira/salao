import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useCapacidades } from '../api/capacidades';
import { useSessao } from '../sessao/sessao';

/**
 * Guarda de rota — **conveniência, não segurança**.
 *
 * Ela evita piscar uma tela vazia para quem não pode vê-la. Quem protege de verdade é o
 * backend, que recusa a chamada mesmo que alguém navegue direto pela URL ou chame a API
 * pelo terminal. Esconder botão é UX; a autorização acontece no caso de uso.
 */
export function Protegida({ children }: { children: ReactNode }) {
  const token = useSessao((s) => s.token);
  const local = useLocation();
  const { data: capacidades, isPending, isError } = useCapacidades();

  if (!token) return <Navigate to="/entrar" replace state={{ de: local.pathname }} />;
  if (isPending) return null;
  if (isError || !capacidades) return <Navigate to="/entrar" replace />;

  // O backend já recusa tudo além do caminho de inscrição (RN-IAM-014). Redirecionar aqui
  // é o que evita a pessoa bater numa parede de 403 sem entender o motivo.
  const precisaInscrever = capacidades.mfaObrigatorio && !capacidades.mfaAtivo;
  if (precisaInscrever && local.pathname !== '/seguranca/segundo-fator') {
    return <Navigate to="/seguranca/segundo-fator" replace />;
  }

  return <>{children}</>;
}
