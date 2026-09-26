import { useState } from 'react';
import {
  deslocar, dinheiro, periodoPorExtenso, ultimoPeriodoFechado, useFechamentos, useFecharPeriodo,
  usePrevia, type LinhaDaPrevia, type Periodicidade, type Periodo, type Previa,
} from '../../api/financeiro';
import { pode, useCapacidades } from '../../api/capacidades';
import { ErroDaApi } from '../../api/http';
import { plural } from '../../layout/plural';
import { diaCivil, fusoDoNavegador } from '../../layout/tempo';
import { Esqueleto, Falha, Pagina, Vazio } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { Extrato } from './Extrato';
import { Fechamentos } from './Fechamentos';
import { Vales } from './Vales';
import './financeiro.css';

/**
 * RT-FIN-013 — o dinheiro de quem trabalha.
 *
 * <h2>A tela é o período</h2>
 *
 * Tudo pende de "de/até": a prévia, os vales, os contracheques, o extrato. Duas pessoas olhando
 * períodos diferentes e concluindo que uma está errada é a conversa mais cara deste sistema — é
 * por isso que `ExtratosController` recusa um padrão de "mês corrente" na API. A tela escolhe
 * **um** padrão, visível e navegável, em vez de deixar cada seção com o seu.
 *
 * <h2>A prévia é a lista, e é a confirmação</h2>
 *
 * A tabela que responde "quem tem a receber" é literalmente a que o servidor usa para calcular o
 * repasse, com o mesmo hash. Uma tabela "de visualização" ao lado garantiria que um dia as duas
 * divergissem.
 */
export function Financeiro() {
  const capacidades = useCapacidades();
  const fuso = capacidades.data?.estabelecimento?.timezone ?? fusoDoNavegador();
  const periodicidade: Periodicidade =
    capacidades.data?.estabelecimento?.periodicidadeDeFechamento ?? 'MENSAL';

  const [periodo, definirPeriodo] = useState<Periodo | null>(null);
  const atual = periodo ?? ultimoPeriodoFechado(diaCivil(new Date(), fuso), periodicidade);

  const podeVerTudo = pode(capacidades.data, 'financeiro:read:all');

  // Espera as capacidades antes de desenhar qualquer coisa. Sem isto, quem só tem
  // `financeiro:read:own` disparava três consultas que o servidor recusa — e o 403 aparecia
  // antes da explicação de por que a tela não abre.
  if (capacidades.isPending) {
    return <Pagina><div className="fin-tela"><Esqueleto linhas={4} altura={56} /></div></Pagina>;
  }
  if (!podeVerTudo) {
    return <SoOProprio />;
  }

  return (
    <Pagina>
      <div className="fin-tela">
        <BarraDoPeriodo periodo={atual} aoTrocar={definirPeriodo} />
        <QuemTemAReceber periodo={atual} />
        <Vales periodo={atual} />
        <Fechamentos periodo={atual} />
      </div>
    </Pagina>
  );
}

/**
 * Quem tem `financeiro:read:own` e não `read:all`.
 *
 * O menu aparece para os dois (RT-IAM-006), e `:own` ainda não filtra — falta o vínculo
 * usuário → profissional. Deixar a tela tentar carregar produziria um 403 cru; dizer o que está
 * faltando custa um parágrafo e não mente.
 */
function SoOProprio() {
  return (
    <Pagina>
      <div className="fin-tela">
        <Vazio icone="cifrao" titulo="Seu extrato ainda não abre por aqui">
          O sistema sabe quanto você tem a receber — a conta é feita a cada comanda fechada. O que
          falta é ligar a sua conta de acesso à sua ficha de profissional; até lá, quem vê o
          extrato é quem administra o salão.
        </Vazio>
      </div>
    </Pagina>
  );
}

/**
 * O período, no alto, governando a tela inteira.
 *
 * Setas para o anterior e o seguinte, do mesmo tamanho; datas à mão quando for outro recorte.
 * Navegar sem digitar é o que faz a conferência de três meses ser três cliques.
 */
function BarraDoPeriodo({ periodo, aoTrocar }: {
  periodo: Periodo;
  aoTrocar: (periodo: Periodo) => void;
}) {
  const [aberto, definirAberto] = useState(false);

  return (
    <div className="fin-periodo">
      <button className="fin-passo" type="button" aria-label="Período anterior"
        onClick={() => aoTrocar(deslocar(periodo, -1))}>‹</button>

      <div className="fin-periodo-nome">
        <strong>{periodoPorExtenso(periodo)}</strong>
        <button className="fin-outro" type="button" aria-expanded={aberto}
          onClick={() => definirAberto(!aberto)}>
          {aberto ? 'fechar' : 'outro recorte'}
        </button>
      </div>

      <button className="fin-passo" type="button" aria-label="Período seguinte"
        onClick={() => aoTrocar(deslocar(periodo, 1))}>›</button>

      {aberto && (
        <div className="fin-datas">
          <label className="fin-campo">
            <span>De</span>
            <input type="date" value={periodo.de}
              onChange={(e) => aoTrocar({ ...periodo, de: e.target.value })} />
          </label>
          <label className="fin-campo">
            <span>Até</span>
            <input type="date" value={periodo.ate}
              onChange={(e) => aoTrocar({ ...periodo, ate: e.target.value })} />
          </label>
        </div>
      )}
    </div>
  );
}

function QuemTemAReceber({ periodo }: { periodo: Periodo }) {
  const previa = usePrevia(periodo);
  const fechamentos = useFechamentos(periodo);
  const [abrindo, definirAbrindo] = useState<string | null>(null);

  const jaFechado = fechamentos.data?.some((f) => f.estornadoEm === null) ?? false;

  return (
    <Secao
      id="fin-a-receber"
      titulo="Quem tem a receber"
      ajuda={previa.data
        ? `${dinheiro(previa.data.totalARepassar)} para `
          + `${plural(previa.data.quantosRecebem, 'pessoa', 'pessoas')}.`
        : 'O saldo de cada um no período, e o que sai de repasse.'}
      acoes={previa.data && !jaFechado
        ? <Fechar periodo={periodo} previa={previa.data} />
        : undefined}
    >
      {previa.isPending && <Esqueleto linhas={4} altura={48} />}
      {previa.isError && (
        <Falha aoTentar={() => previa.refetch()}>{mensagem(previa.error)}</Falha>
      )}

      {jaFechado && (
        <p className="fin-ajuda fin-atencao">
          Este período já foi fechado. Os contracheques estão logo abaixo; para refazê-lo, estorne
          o fechamento primeiro.
        </p>
      )}

      {previa.data?.linhas.length === 0 && (
        <Vazio icone="cifrao" titulo="Nada apurado neste período">
          Nenhuma comanda fechada e nenhum vale entre estas datas.
        </Vazio>
      )}

      {previa.data && previa.data.linhas.length > 0 && (
        <ul className="fin-lista">
          {previa.data.linhas.map((linha) => (
            <li key={linha.profissionalId}>
              <Linha linha={linha} aberta={abrindo === linha.profissionalId}
                aoAbrir={() => definirAbrindo(
                  abrindo === linha.profissionalId ? null : linha.profissionalId)} />
              {abrindo === linha.profissionalId && (
                <Extrato profissionalId={linha.profissionalId} periodo={periodo} />
              )}
            </li>
          ))}
        </ul>
      )}
    </Secao>
  );
}

function Linha({ linha, aberta, aoAbrir }: {
  linha: LinhaDaPrevia;
  aberta: boolean;
  aoAbrir: () => void;
}) {
  return (
    <article className="fin-pessoa">
      <h3>{linha.profissionalNome}</h3>

      <p className="fin-numero">
        <span>anterior</span>
        {/* Saldo negativo aparece, e é justamente a linha que alguém vai perguntar. */}
        <strong className={linha.saldoAnterior < 0 ? 'devendo' : undefined}>
          {dinheiro(linha.saldoAnterior)}
        </strong>
      </p>
      <p className="fin-numero">
        <span>no período</span>
        <strong>{dinheiro(linha.movimentoDoPeriodo)}</strong>
      </p>
      <p className="fin-numero">
        <span>saldo</span>
        <strong className={linha.saldoFinal < 0 ? 'devendo' : undefined}>
          {dinheiro(linha.saldoFinal)}
        </strong>
      </p>
      <p className="fin-numero destaque">
        <span>a repassar</span>
        <strong>{dinheiro(linha.aRepassar)}</strong>
      </p>

      <button className="pagina-acao discreta" type="button" onClick={aoAbrir}>
        {aberta ? 'Fechar' : 'Ver extrato'}
      </button>
    </article>
  );
}

/**
 * R-UX-05 — a confirmação diz o efeito em número, e leva a versão que está na tela.
 *
 * Se alguém fechou uma comanda entre ver e confirmar, o servidor recusa e a tela recarrega a
 * prévia: o número confirmado é o número que se viu, não o que estava lá quando a página abriu.
 */
function Fechar({ periodo, previa }: { periodo: Periodo; previa: Previa }) {
  const fechar = useFecharPeriodo();
  const consulta = usePrevia(periodo);
  const [confirmando, definirConfirmando] = useState(false);

  const desatualizada = fechar.error instanceof ErroDaApi
    && fechar.error.codigo === 'ER-FIN-PREVIA_DESATUALIZADA';

  if (!confirmando) {
    return (
      <button className="pagina-acao principal" type="button"
        disabled={previa.quantosRecebem === 0}
        onClick={() => { fechar.reset(); definirConfirmando(true); }}>
        Fechar o período
      </button>
    );
  }

  return (
    <div className="fin-confirmar">
      <p>
        Fechar gera <strong>{dinheiro(previa.totalARepassar)}</strong> de repasse para{' '}
        <strong>{plural(previa.quantosRecebem, 'pessoa', 'pessoas')}</strong>. Não volta atrás
        sem estorno.
      </p>

      {desatualizada && (
        <p className="erro" role="alert">
          O número mudou enquanto você olhava — alguém fechou comanda ou registrou vale. Confira o
          total acima e confirme de novo.
        </p>
      )}
      {fechar.isError && !desatualizada && (
        <p className="erro" role="alert">{mensagem(fechar.error)}</p>
      )}

      <div className="fin-acoes">
        <button className="pagina-acao principal" type="button" disabled={fechar.isPending}
          onClick={() => fechar.mutate(
            { ...periodo, versaoDaPrevia: previa.versao },
            { onSuccess: () => definirConfirmando(false), onError: () => void consulta.refetch() },
          )}>
          {fechar.isPending ? 'Fechando…' : 'Confirmar o fechamento'}
        </button>
        <button className="pagina-acao discreta" type="button"
          onClick={() => definirConfirmando(false)}>Cancelar</button>
      </div>
    </div>
  );
}

/** A recusa do servidor, com as palavras dele. */
export function mensagem(erro: unknown): string {
  if (erro instanceof ErroDaApi) return erro.message;
  return 'Não foi possível falar com o servidor. Tente de novo.';
}
