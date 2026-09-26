import { Link } from 'react-router-dom';
import { useCapacidades } from '../api/capacidades';
import { Pagina, Vazio } from '../layout/Pagina';
import './sem-tela.css';

/**
 * A área que o servidor já sabe fazer e que ainda não tem tela.
 *
 * <h2>Sobrou uma</h2>
 *
 * Eram duas até 26/09; o estoque ganhou a dele (RT-EST-013). O tipo estreitou junto — uma união
 * com um membro só é feia e é honesta, e o dia em que ela virar `never` é o dia de apagar esta
 * tela inteira.
 *
 * <h2>O texto anterior mentia</h2>
 *
 * Ele dizia "esta área chega com a rotina RT-EST-001" — e RT-EST-001 tinha sido entregue
 * semanas antes. Quem lia concluía que o estoque inteiro estava por fazer, quando o que falta
 * é **a tela**: doze rotinas de financeiro estão implementadas e testadas no servidor
 * (`18-o-que-falta-para-usar.md`).
 *
 * Nomear a rotina errada é pior do que não nomear nenhuma: manda procurar no lugar errado.
 *
 * <h2>Três coisas, nesta ordem</h2>
 *
 * O que já existe — para não parecer que o assunto está por começar. **O que dá para fazer
 * hoje**, com o caminho, porque quase sempre dá: a comanda já recebe, dá desconto e apura
 * comissão. E o que esta tela vai trazer, com a rotina certa.
 *
 * O meio é o que importa: uma tela vazia que só se desculpa desperdiça a visita.
 */
export function SemTela({ area }: { area: 'financeiro' }) {
  const { data: capacidades } = useCapacidades();
  const conteudo = CONTEUDO[area];

  // Só oferece caminho que esta pessoa tem: o menu vem do servidor, e um link para uma tela
  // sem permissão seria um beco (inviolável nº 6).
  const menus = capacidades?.menus ?? [];
  const caminhos = conteudo.hoje.filter((item) => menus.some((menu) => menu.rota === item.rota));

  return (
    <Pagina>
      <div className="sem-tela">
        <Vazio icone={conteudo.icone} titulo={conteudo.titulo}>{conteudo.existe}</Vazio>

        <div className="sem-tela-colunas">
          {caminhos.length > 0 && (
            <section>
              <h3>O que dá para fazer hoje</h3>
              <ul className="sem-tela-caminhos">
                {caminhos.map((item) => (
                  <li key={item.rota}>
                    <Link to={item.rota}>{item.acao}</Link>
                    <span>{item.detalhe}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          <section>
            <h3>O que esta tela vai trazer</h3>
            <ul className="sem-tela-falta">
              {conteudo.falta.map((item) => <li key={item}>{item}</li>)}
            </ul>
            <p className="sem-tela-rotina">
              Rotina <code>{conteudo.rotina}</code>, na fase 7 do plano.
            </p>
          </section>
        </div>
      </div>
    </Pagina>
  );
}

interface Conteudo {
  icone: string;
  titulo: string;
  existe: string;
  hoje: Array<{ rota: string; acao: string; detalhe: string }>;
  falta: string[];
  rotina: string;
}

const CONTEUDO: Record<'financeiro', Conteudo> = {
  financeiro: {
    icone: 'cifrao',
    titulo: 'O financeiro funciona — falta a tela dele',
    existe: 'Pagamento com split, comissão em cascata com snapshot no executante, apuração no '
      + 'fechamento da comanda, extrato item a item, vale, fechamento de período, estorno e '
      + 'conciliação com a maquininha estão entregues e testados no servidor.',
    hoje: [
      { rota: '/comandas', acao: 'Receber e fechar comanda',
        detalhe: 'split de pagamento, desconto e comissão apurada na hora' },
      { rota: '/conciliacao', acao: 'Conferir a maquininha',
        detalhe: 'o que caiu, o que falta e a divergência resolvida à mão' },
    ],
    falta: [
      'Ver o extrato de um profissional, item a item',
      'Registrar vale e adiantamento',
      'Fechar o período e registrar o repasse',
      'Definir regra de comissão por serviço e por pessoa',
    ],
    rotina: 'RT-FIN-013',
  },
};
