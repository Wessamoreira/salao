/**
 * As iniciais do rosto da cliente na lista e na ficha (RT-CLI-008).
 *
 * Primeiro e último nome — "Josefa Antônia de Andrade" vira "JA", e não "JAD": três letras num
 * círculo de 32px viram borrão. Uma palavra só vira uma letra.
 */
export function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return '?';
  const primeira = partes[0].charAt(0);
  const ultima = partes.length > 1 ? partes[partes.length - 1].charAt(0) : '';
  return (primeira + ultima).toLocaleUpperCase('pt-BR');
}
