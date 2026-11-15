/**
 * "Bom dia." · "Boa tarde." · "Boa noite." — pela hora do aparelho.
 *
 * Do aparelho, e não do salão: antes de entrar não se sabe qual é o salão. As viradas são as
 * do costume, não as do relógio astronômico: meio-dia é tarde, 18h já é noite.
 */
export function saudacao(agora: Date = new Date()): string {
  const hora = agora.getHours();
  if (hora >= 5 && hora < 12) return 'Bom dia.';
  if (hora >= 12 && hora < 18) return 'Boa tarde.';
  return 'Boa noite.';
}
