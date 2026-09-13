import { useEffect, useState } from 'react';

/**
 * A data e a hora **do salão**, não as da máquina.
 *
 * <h2>Por que isto ocupa o canto mais nobre do topo</h2>
 *
 * O sistema inteiro é construído sobre uma distinção: o dia do salão não é o dia do
 * servidor (ADR-0009, RN-FIN-010). A comanda fechada às 23h50 de 31 de março em Manaus
 * é de março, e o servidor em UTC já virou abril. Essa regra vive no backend e nunca
 * aparecia na tela — então a recepção não tinha como conferir em que dia o sistema acha
 * que está.
 *
 * E é o dado que mais se olha num balcão: tudo aqui é horário.
 *
 * <h2>Atualiza no minuto, não no segundo</h2>
 *
 * Relógio com segundos pede atenção que a recepção não tem para dar, e um `setInterval`
 * de 1s acorda o React sessenta vezes por minuto para mudar dois dígitos. O primeiro
 * tique é alinhado à virada do minuto para o relógio não ficar meio minuto atrasado.
 */
export function HoraDoSalao({ fuso }: { fuso: string }) {
  const [agora, definirAgora] = useState(() => new Date());

  useEffect(() => {
    let intervalo: ReturnType<typeof setInterval>;
    const ateOProximoMinuto = 60_000 - (Date.now() % 60_000);
    const alinhamento = setTimeout(() => {
      definirAgora(new Date());
      intervalo = setInterval(() => definirAgora(new Date()), 60_000);
    }, ateOProximoMinuto);
    return () => { clearTimeout(alinhamento); clearInterval(intervalo); };
  }, []);

  const semana = formatar(agora, fuso, { weekday: 'short' }).replaceAll('.', '');
  const dia = formatar(agora, fuso, { day: '2-digit', month: 'short' }).replaceAll('.', '');
  const hora = formatar(agora, fuso, { hour: '2-digit', minute: '2-digit', hour12: false });

  return (
    <time className="casca-hoje" dateTime={agora.toISOString()}
      title={`Horário do salão · ${fuso.replace('_', ' ')}`}>
      <span>{semana.charAt(0).toLocaleUpperCase('pt-BR') + semana.slice(1)}</span>
      <span className="casca-hoje-dia">{dia}</span>
      <strong>{hora}</strong>
    </time>
  );
}

/**
 * `Intl.DateTimeFormat` **lança** `RangeError` para um fuso que ele não reconhece — não
 * devolve nada, não avisa: derruba o render, e com ele a aplicação inteira.
 *
 * O fuso vem do banco como texto livre (`America/Sao_Paulo`). Basta um cadastro com
 * grafia errada, ou um IANA que aquele navegador ainda não conhece, para a casca sumir.
 * Um relógio no fuso errado é um defeito pequeno; a tela branca é o maior de todos.
 */
function formatar(quando: Date, fuso: string, opcoes: Intl.DateTimeFormatOptions): string {
  try {
    return new Intl.DateTimeFormat('pt-BR', { ...opcoes, timeZone: fuso }).format(quando);
  } catch {
    return new Intl.DateTimeFormat('pt-BR', opcoes).format(quando);
  }
}
