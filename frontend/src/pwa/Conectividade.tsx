import { useConectado } from './conectado';
import './conectividade.css';

/**
 * O aviso explica a consequência, não só a tecnologia. Dados em memória seguem visíveis;
 * escrita nunca é enfileirada fingindo sucesso (R-UX-14).
 */
export function Conectividade() {
  const estaConectado = useConectado();
  if (estaConectado) return null;

  return (
    <div className="conectividade" role="status" aria-live="polite">
      <span className="conectividade-ponto" aria-hidden="true" />
      <strong>Sem conexão</strong>
      <span>Dados abertos disponíveis; alterações exigem internet.</span>
    </div>
  );
}
