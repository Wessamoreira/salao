import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatar } from './tempo';

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
 * <h2>E por que ele agora leva a algum lugar</h2>
 *
 * Ocupar o lugar mais nobre sem fazer nada é enfeite — e era a crítica C4 do checklist do
 * front. O que a recepção faz depois de olhar a hora é abrir o dia: o relógio é o caminho
 * mais curto de qualquer tela para a **agenda de hoje**.
 *
 * O destino é `/agenda` sem dia: hoje é o padrão da tela, e um `?dia=` calculado aqui
 * apontaria para ontem numa tela deixada aberta a noite inteira — que é exatamente o que
 * acontece com o computador do balcão.
 *
 * Quem não tem agenda no menu (o servidor é quem diz) continua vendo o relógio parado,
 * sem link: esconder o menu e deixar o atalho seria a mesma porta com outro nome.
 *
 * <h2>Atualiza no minuto, não no segundo</h2>
 *
 * Relógio com segundos pede atenção que a recepção não tem para dar, e um `setInterval`
 * de 1s acorda o React sessenta vezes por minuto para mudar dois dígitos. O primeiro
 * tique é alinhado à virada do minuto para o relógio não ficar meio minuto atrasado.
 */
export function HoraDoSalao({ fuso, rotaDaAgenda }: { fuso: string; rotaDaAgenda?: string }) {
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
  const titulo = `Horário do salão · ${fuso.replace('_', ' ')}`;

  const marcaDeTempo = (
    <time className="casca-hoje" dateTime={agora.toISOString()}>
      <span>{semana.charAt(0).toLocaleUpperCase('pt-BR') + semana.slice(1)}</span>
      <span className="casca-hoje-dia">{dia}</span>
      <strong>{hora}</strong>
    </time>
  );

  if (!rotaDaAgenda) return <span title={titulo}>{marcaDeTempo}</span>;

  /*
   * O nome acessível diz o destino antes do dado: sem ele, o leitor de tela anuncia
   * "link, Qui 23 set 14:32" — informação certa, destino nenhum. A hora continua no
   * nome porque ela É o conteúdo, e some para quem não vê se ficar só no visual.
   */
  return (
    <Link className="casca-hoje-link" to={rotaDaAgenda} title={`Agenda de hoje · ${titulo}`}
      aria-label={`Agenda de hoje · ${dia}, ${hora}`}>
      {marcaDeTempo}
    </Link>
  );
}
