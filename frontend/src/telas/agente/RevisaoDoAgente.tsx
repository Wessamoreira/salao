import { useMemo, useState, type ReactNode } from 'react';
import {
  useAcoesDoAgente, usePessoasDoAgente, useResumoDoAgente,
  type Acao, type Chamada, type DesfechoDaChamada, type IntencaoDaAcao, type Recorte,
  type StatusDaIntencao,
} from '../../api/agente';
import { useCapacidades } from '../../api/capacidades';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina, Vazio } from '../../layout/Pagina';
import { diaCivil, formatar, periodoDe, somarDias } from '../../layout/tempo';
import './agente.css';

/**
 * RT-CNV-007 — a revisão do que o agente fez.
 *
 * <h2>Duas perguntas, e a tela abre respondendo as duas</h2>
 *
 * "O agente gravou alguma coisa?" e "alguém tentou o que não podia?". Os recortes são essas
 * perguntas, e os totais no alto dizem quantas de cada houve antes de qualquer clique.
 *
 * <h2>O trilho das trancas</h2>
 *
 * Cada pedido do modelo passa por três verificações, e a trilha registra onde parou. Mostrar só
 * "SEM_PERMISSAO" obrigaria o dono a saber a ordem das trancas para entender o que ficou para
 * trás; o trilho desenha a ordem, e o ponto onde o pedido foi barrado fica marcado por forma e
 * por texto — não só por cor.
 *
 * <h2>Nada aqui é decidido na tela</h2>
 *
 * "Gravou" e "bloqueada" chegam calculados, e o recorte é filtrado no servidor. A tela traduz
 * códigos em frases; não reinterpreta o que aconteceu.
 */
export function RevisaoDoAgente() {
  const { data: capacidades } = useCapacidades();
  const fuso = capacidades?.estabelecimento.timezone ?? FUSO_PADRAO;

  const [recorte, definirRecorte] = useState<Recorte>('TODAS');
  const [pessoa, definirPessoa] = useState<{ id: string; nome: string } | null>(null);
  const [dias, definirDias] = useState<number>(7);
  const [aberta, definirAberta] = useState<string | null>(null);

  const { de, ate } = useMemo(() => periodoDe(dias, fuso, new Date()), [dias, fuso]);
  const filtro = { recorte, pessoa: pessoa?.id ?? null, de, ate };
  const acoes = useAcoesDoAgente(filtro);
  const resumo = useResumoDoAgente(filtro);
  const pessoas = usePessoasDoAgente(de, ate);

  const itens = acoes.data?.pages.flatMap((pagina) => pagina.itens) ?? [];
  const participantes = pessoas.data ?? [];
  const hoje = diaCivil(new Date(), fuso);

  const barra = (
    <>
      <div className="agt-recorte" role="group" aria-label="Mostrar">
        {RECORTES.map((opcao) => (
          <button
            key={opcao.valor}
            type="button"
            aria-pressed={recorte === opcao.valor}
            onClick={() => { definirRecorte(opcao.valor); definirAberta(null); }}
          >
            {opcao.rotulo}
          </button>
        ))}
      </div>
      <label className="agt-filtro">
        <span>Pessoa</span>
        <select
          value={pessoa?.id ?? ''}
          onChange={(e) => {
            const escolhida = participantes.find((p) => p.usuarioId === e.target.value);
            definirPessoa(escolhida ? { id: escolhida.usuarioId, nome: escolhida.nome } : null);
            definirAberta(null);
          }}
        >
          <option value="">Todas as pessoas</option>
          {/* Quem foi escolhida e não conversou no período novo continua escolhida — e a
              opção diz isso, em vez de o seletor mostrar "Todas" com o filtro ainda valendo. */}
          {pessoa && !participantes.some((p) => p.usuarioId === pessoa.id) && (
            <option value={pessoa.id}>{pessoa.nome} · sem conversas</option>
          )}
          {participantes.map((p) => (
            <option key={p.usuarioId} value={p.usuarioId}>{p.nome} · {p.conversas}</option>
          ))}
        </select>
      </label>
      <label className="agt-filtro">
        <span>Período</span>
        <select
          value={dias}
          onChange={(e) => { definirDias(Number(e.target.value)); definirAberta(null); }}
        >
          {PERIODOS.map((periodo) => (
            <option key={periodo.dias} value={periodo.dias}>{periodo.rotulo}</option>
          ))}
        </select>
      </label>
    </>
  );

  let conteudo: ReactNode;
  if (acoes.isPending) {
    conteudo = <Esqueleto linhas={8} altura={48} />;
  } else if (acoes.isError) {
    conteudo = <Falha aoTentar={() => acoes.refetch()}>{mensagem(acoes.error)}</Falha>;
  } else if (itens.length === 0) {
    conteudo = (
      <Vazio icone="balao" titulo={VAZIO[recorte].titulo}>{VAZIO[recorte].texto}</Vazio>
    );
  } else {
    conteudo = (
      <>
        {porDia(itens, fuso).map((grupo) => (
          <section key={grupo.dia} className="agt-grupo" aria-labelledby={`agt-dia-${grupo.dia}`}>
            <h2 className="agt-dia" id={`agt-dia-${grupo.dia}`}>{rotuloDoDia(grupo.dia, hoje)}</h2>
            <ol className="agt-lista">
              {grupo.itens.map((acao) => (
                <Linha
                  key={acao.id}
                  acao={acao}
                  fuso={fuso}
                  aberta={aberta === acao.id}
                  aoAlternar={() => definirAberta(aberta === acao.id ? null : acao.id)}
                />
              ))}
            </ol>
          </section>
        ))}
        {acoes.hasNextPage && (
          <div className="agt-mais">
            <button
              type="button"
              className="pagina-acao discreta"
              disabled={acoes.isFetchingNextPage}
              onClick={() => acoes.fetchNextPage()}
            >
              {acoes.isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}
            </button>
          </div>
        )}
      </>
    );
  }

  return (
    <Pagina barra={barra}>
      <div className="agt-tela">
        {resumo.data ? (
          <p className="agt-resumo">
            <Total numero={inteiro(resumo.data.conversas)}
              rotulo={resumo.data.conversas === 1 ? 'conversa' : 'conversas'} />
            <Total numero={inteiro(resumo.data.gravaram)}
              rotulo={resumo.data.gravaram === 1 ? 'gravou' : 'gravaram'} />
            <Total numero={inteiro(resumo.data.bloqueadas)}
              rotulo={resumo.data.bloqueadas === 1 ? 'bloqueada' : 'bloqueadas'} />
            <Total numero={dinheiro(resumo.data.custoUsd)} rotulo="de custo estimado" />
          </p>
        ) : !resumo.isError && (
          <div className="agt-resumo-espera"><Esqueleto linhas={1} altura={40} /></div>
        )}
        {conteudo}
      </div>
    </Pagina>
  );
}

function Total({ numero, rotulo }: { numero: string; rotulo: string }) {
  return (
    <span className="agt-total"><strong className="agt-numero">{numero}</strong> {rotulo}</span>
  );
}

function Linha({ acao, fuso, aberta, aoAlternar }: {
  acao: Acao;
  fuso: string;
  aberta: boolean;
  aoAlternar: () => void;
}) {
  const detalhe = `agt-detalhe-${acao.id}`;
  const marca = acao.gravou ? 'gravou'
    : acao.bloqueada ? 'bloqueada'
      : acao.desfecho === 'FALHOU' ? 'falhou' : undefined;
  // Sem cor: propor não é estado de alerta, é a tentativa de escrita que o dono quer achar de olho.
  const propos = !marca && acao.intencoes.some((intencao) => intencao.papel === 'PROPOSTA');

  return (
    <li className="agt-item" data-marca={marca}>
      <button
        type="button"
        className="agt-linha"
        aria-expanded={aberta}
        aria-controls={detalhe}
        onClick={aoAlternar}
      >
        <time className="agt-hora" dateTime={acao.ocorridaEm}>
          {formatar(new Date(acao.ocorridaEm), fuso,
            { hour: '2-digit', minute: '2-digit', hour12: false })}
        </time>
        <span className="agt-quem">{quem(acao)}</span>
        <span className="agt-pedido">
          {acao.veioDeAudio && <span className="agt-etiqueta">Áudio</span>}
          <span className={`agt-pedido-texto${acao.anonimizadaEm ? ' apagado' : ''}`}>
            {trecho(acao)}
          </span>
        </span>
        {marca && <span className={`agt-etiqueta ${marca}`}>{ETIQUETA[marca]}</span>}
        {propos && <span className="agt-etiqueta">Propôs</span>}
      </button>
      {aberta && <Detalhe id={detalhe} acao={acao} fuso={fuso} />}
    </li>
  );
}

function Detalhe({ id, acao, fuso }: { id: string; acao: Acao; fuso: string }) {
  return (
    <div className="agt-detalhe" id={id}>
      {acao.desfecho === 'RECUSADA' && (
        <p className="agt-motivo">Recusada: {motivoDaRecusa(acao.motivoRecusa)}</p>
      )}
      {acao.desfecho === 'FALHOU' && (
        <p className="agt-motivo neutro">
          A conversa falhou depois das tentativas e ficou sem resposta.
        </p>
      )}

      {acao.anonimizadaEm ? (
        <p className="agt-apagado">
          O que foi dito saiu em {formatar(new Date(acao.anonimizadaEm), fuso,
            { day: '2-digit', month: 'short', year: 'numeric' }).replaceAll('.', '')}, 90 dias
          depois da conversa. Ficou o registro do que o agente fez.
        </p>
      ) : (
        <dl className="agt-fala">
          <div>
            <dt>{acao.tipo === 'BOTAO' ? 'Tocou em' : acao.veioDeAudio ? 'Pediu, por áudio' : 'Pediu'}</dt>
            <dd>{acao.pedido ?? '—'}</dd>
          </div>
          {acao.resposta && (
            <div>
              <dt>Respondeu</dt>
              <dd>{acao.resposta}</dd>
            </div>
          )}
        </dl>
      )}

      {acao.ferramentas.length > 0 && (
        <section className="agt-bloco" aria-label="O que o modelo pediu">
          <h3 className="agt-bloco-titulo">O que o modelo pediu</h3>
          <p className="agt-bloco-ajuda">
            Cada pedido passa por três trancas antes de fazer qualquer coisa. Gravar nunca acontece
            aqui: vira um pedido que só a pessoa confirma.
          </p>
          <ol className="agt-trilho">
            {acao.ferramentas.map((chamada, i) => <Trilho key={i} chamada={chamada} />)}
          </ol>
        </section>
      )}

      {acao.intencoes.length > 0 && (
        <section className="agt-bloco" aria-label="Pedidos de confirmação">
          <h3 className="agt-bloco-titulo">Pedidos de confirmação</h3>
          <ul className="agt-intencoes">
            {acao.intencoes.map((intencao, i) => <Intencao key={i} intencao={intencao} />)}
          </ul>
        </section>
      )}

      <p className="agt-rodape">{rodape(acao)}</p>
    </div>
  );
}

const TRANCAS: { desfecho: DesfechoDaChamada; passou: string; barrou: string }[] = [
  { desfecho: 'INEXISTENTE', passou: 'Existe', barrou: 'Ferramenta inexistente' },
  { desfecho: 'FORA_DA_LISTA', passou: 'Liberada ao agente', barrou: 'Fora da lista do agente' },
  { desfecho: 'SEM_PERMISSAO', passou: 'Permissão de quem pediu', barrou: 'Sem permissão de quem pediu' },
];

const RESULTADOS: Partial<Record<DesfechoDaChamada, { rotulo: string; tipo: string }>> = {
  EXECUTADA: { rotulo: 'Consultou', tipo: 'consultou' },
  PROPOSTA: { rotulo: 'Virou pedido de confirmação', tipo: 'pedido' },
  ERRO_DE_DOMINIO: { rotulo: 'A regra do salão recusou', tipo: 'recusou' },
  FALHA: { rotulo: 'Falhou', tipo: 'recusou' },
};

function Trilho({ chamada }: { chamada: Chamada }) {
  const barrada = TRANCAS.findIndex((tranca) => tranca.desfecho === chamada.desfecho);
  const resultado = barrada === -1
    ? RESULTADOS[chamada.desfecho] ?? { rotulo: chamada.desfecho, tipo: 'consultou' }
    : null;
  const argumentos = Object.entries(chamada.argumentos ?? {});

  return (
    <li className="agt-chamada">
      <code className="agt-ferramenta">{chamada.nome}</code>
      <ol className="agt-passos">
        {TRANCAS.map((tranca, i) => {
          const estado = barrada === -1 || i < barrada ? 'passou'
            : i === barrada ? 'barrou' : 'nao-chegou';
          const rotulo = estado === 'barrou' ? tranca.barrou : tranca.passou;
          return (
            <li key={tranca.desfecho} className="agt-passo" data-estado={estado}
              aria-label={`${rotulo}: ${ESTADO_DO_PASSO[estado]}`}>
              <Marca estado={estado} />
              <span>{rotulo}</span>
            </li>
          );
        })}
        {resultado && (
          <li className="agt-passo agt-resultado" data-resultado={resultado.tipo}>
            {resultado.rotulo}
          </li>
        )}
      </ol>
      {argumentos.length > 0 && (
        <dl className="agt-argumentos">
          {argumentos.map(([chave, valor]) => (
            <div key={chave}>
              <dt>{chave}</dt>
              <dd>{typeof valor === 'object' ? JSON.stringify(valor) : String(valor)}</dd>
            </div>
          ))}
        </dl>
      )}
    </li>
  );
}

function Marca({ estado }: { estado: 'passou' | 'barrou' | 'nao-chegou' }) {
  const comum = { width: 12, height: 12, viewBox: '0 0 12 12', 'aria-hidden': true } as const;
  if (estado === 'barrou') {
    return (
      <svg {...comum}>
        <path d="m3 3 6 6M9 3 3 9" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" />
      </svg>
    );
  }
  if (estado === 'nao-chegou') {
    return (
      <svg {...comum}>
        <circle cx="6" cy="6" r="3.5" fill="none" stroke="currentColor" strokeWidth={1.25}
          strokeDasharray="2 1.6" />
      </svg>
    );
  }
  return <svg {...comum}><circle cx="6" cy="6" r="3.5" fill="currentColor" /></svg>;
}

function Intencao({ intencao }: { intencao: IntencaoDaAcao }) {
  return (
    <li className="agt-intencao">
      <span className="agt-intencao-papel">{PAPEL[intencao.papel]}</span>
      <span className="agt-intencao-resumo">{intencao.resumo ?? intencao.ferramenta}</span>
      <span className="agt-etiqueta" data-status={intencao.status}>
        {STATUS[intencao.status] ?? intencao.status}
      </span>
      {intencao.motivo && <span className="agt-intencao-motivo">{intencao.motivo}</span>}
    </li>
  );
}

// ------------------------------------------------------------------ texto

const FUSO_PADRAO = 'America/Sao_Paulo';

const RECORTES: { valor: Recorte; rotulo: string }[] = [
  { valor: 'TODAS', rotulo: 'Todas' },
  { valor: 'GRAVARAM', rotulo: 'Gravaram' },
  { valor: 'BLOQUEADAS', rotulo: 'Bloqueadas' },
];

/** O servidor aceita até 92 dias; os atalhos cobrem o que se revisa de fato. */
const PERIODOS = [
  { dias: 1, rotulo: 'Hoje' },
  { dias: 7, rotulo: 'Últimos 7 dias' },
  { dias: 30, rotulo: 'Últimos 30 dias' },
  { dias: 90, rotulo: 'Últimos 90 dias' },
];

const VAZIO: Record<Recorte, { titulo: string; texto: string }> = {
  TODAS: {
    titulo: 'Ninguém conversou com o agente neste período',
    texto: 'As conversas pelo WhatsApp aparecem aqui assim que terminam.',
  },
  GRAVARAM: {
    titulo: 'O agente não gravou nada neste período',
    texto: 'Gravar exige que a pessoa responda com o código de confirmação. Cada gravação confirmada aparece aqui.',
  },
  BLOQUEADAS: {
    titulo: 'Nenhuma tentativa bloqueada neste período',
    texto: 'Número fora da equipe, pedido acima da permissão ou ferramenta que o agente não tem: o que as trancas recusarem aparece aqui.',
  },
};

const ETIQUETA = { gravou: 'Gravou', bloqueada: 'Bloqueada', falhou: 'Falhou' } as const;

const ESTADO_DO_PASSO = {
  passou: 'passou', barrou: 'barrado aqui', 'nao-chegou': 'não chegou',
} as const;

const MOTIVOS: Record<string, string> = {
  TELEFONE_DESCONHECIDO: 'o número não é de ninguém da equipe',
  USUARIO_INATIVO: 'o usuário deste número está inativo',
  USUARIO_BLOQUEADO: 'o usuário está bloqueado por tentativas de senha',
  PERFIL_EXIGE_SEGUNDO_FATOR: 'o perfil exige segundo fator, que o WhatsApp não tem como pedir',
  PERFIL_SEM_CONVERSA: 'esta conta não conversa pelo canal',
  LIMITE_POR_TELEFONE: 'passou do limite de conversas por hora deste número',
  TETO_DE_CUSTO: 'o teto de custo do dia foi atingido',
};

const PAPEL: Record<IntencaoDaAcao['papel'], string> = {
  PROPOSTA: 'Propôs',
  DECISAO: 'Respondeu ao pedido',
  ACEITE: 'Aceite da profissional',
};

const STATUS: Record<StatusDaIntencao, string> = {
  PENDENTE: 'Esperando o SIM',
  AGUARDANDO_ACEITE: 'Esperando o aceite',
  CONFIRMADA: 'Confirmada',
  RECUSADA: 'Recusada',
  SUBSTITUIDA: 'Substituída',
  EXPIRADA: 'Expirou',
};

function motivoDaRecusa(codigo: string | null): string {
  return (codigo && MOTIVOS[codigo]) ?? codigo ?? 'motivo não registrado';
}

function quem(acao: Acao): string {
  if (acao.quemPediu) return acao.quemPediu;
  return acao.telefoneFinal ? `Número final ${acao.telefoneFinal}` : 'Número não guardado';
}

function trecho(acao: Acao): string {
  if (acao.anonimizadaEm) return 'Conteúdo apagado após 90 dias';
  if (acao.tipo === 'BOTAO') return `Tocou em “${acao.pedido ?? 'um botão'}”`;
  return acao.pedido ?? '—';
}

function rodape(acao: Acao): string {
  const partes = acao.tokensEntrada === 0 && acao.tokensSaida === 0
    ? ['Não chamou o modelo']
    : [acao.modelo, `${inteiro(acao.tokensEntrada)} tokens de entrada`,
      `${inteiro(acao.tokensSaida)} de saída`, `${dinheiro(acao.custoUsd)} estimados`];
  return partes.filter(Boolean).join(' · ');
}

function mensagem(erro: unknown): string {
  return erro instanceof ErroDaApi ? erro.message : 'Tente de novo em instantes.';
}

function inteiro(valor: number): string {
  return new Intl.NumberFormat('pt-BR').format(valor);
}

/** Custo de uma conversa é fração de centavo: com duas casas, toda linha diria US$ 0,00. */
function dinheiro(valor: number): string {
  return new Intl.NumberFormat('pt-BR', {
    style: 'currency', currency: 'USD', minimumFractionDigits: 2,
    maximumFractionDigits: valor > 0 && valor < 0.01 ? 4 : 2,
  }).format(valor);
}

// ------------------------------------------------------------------ tempo

function porDia(acoes: Acao[], fuso: string) {
  const grupos: { dia: string; itens: Acao[] }[] = [];
  for (const acao of acoes) {
    const dia = diaCivil(new Date(acao.ocorridaEm), fuso);
    const ultimo = grupos.at(-1);
    if (ultimo?.dia === dia) ultimo.itens.push(acao);
    else grupos.push({ dia, itens: [acao] });
  }
  return grupos;
}

function rotuloDoDia(dia: string, hoje: string): string {
  if (dia === hoje) return 'Hoje';
  if (dia === somarDias(hoje, -1)) return 'Ontem';
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short',
  }).format(new Date(`${dia}T12:00:00Z`)).replaceAll('.', '');
}
