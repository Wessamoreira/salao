import { formatarTelefone } from '../../api/clientes';

/**
 * O telefone, com os dígitos que casaram em destaque.
 *
 * É neles que o olho confere contra o identificador de chamadas — quem busca "4321"
 * está olhando para o aparelho enquanto lê a tela. Destacar o trecho poupa a
 * releitura do número inteiro.
 *
 * Peso, e não cor: o sistema reserva cor para status (16-design-system).
 */
export function Telefone({ e164, destacar }: { e164: string | null; destacar?: string }) {
  const formatado = formatarTelefone(e164);
  if (!formatado) return null;

  const digitos = destacar?.replace(/\D/g, '') ?? '';
  if (!digitos || !e164?.endsWith(digitos)) return <>{formatado}</>;

  // O trecho a destacar é o fim do número; a máscara não atrapalha porque a
  // contagem é feita sobre os dígitos, da direita para a esquerda.
  let restantes = digitos.length;
  let corte = formatado.length;
  while (corte > 0 && restantes > 0) {
    corte -= 1;
    if (/\d/.test(formatado[corte])) restantes -= 1;
  }

  return (
    <>
      {formatado.slice(0, corte)}
      <b>{formatado.slice(corte)}</b>
    </>
  );
}
