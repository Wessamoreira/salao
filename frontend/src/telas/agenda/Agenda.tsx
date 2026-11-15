import { Esqueleto, Falha, Vazio } from '../../layout/Pagina';
import { useMemo, useRef, useState } from 'react';
import {
  useAgendaDoDia, useCancelar, useConfirmar, useReagendar, useRegistrarFalta, useRemoverBloqueio,
  type Agendamento, type AgendaDoDia, type BlocoDoAgendamento,
} from '../../api/agenda';
import {
  cargaEmMinutos, faixaDoDia, instanteDoPixel, linhasDaGrade, periodoDoArrasto, pixelsPorMinuto,
  posicao, posicaoDoAgora, rotuloDeCarga, rotuloDeHora, DENSIDADES,
  type Densidade, type FaixaDoDia,
} from './grade';
import { PainelDoGesto, mensagem, type Pedido } from './Gesto';
import { Link, useSearchParams } from 'react-router-dom';
import { pode, useCapacidades } from '../../api/capacidades';
import { useVagasAbertas } from '../../api/fila';
import { useConectado } from '../../pwa/conectado';
import { diaCivil, ehDiaCivil, fusoDoNavegador, somarDias } from '../../layout/tempo';
import './agenda.css';

/** A jornada mais cheia do salão é a régua da barra de carga — não um número inventado. */
function maiorCarga(agenda: AgendaDoDia): number {
  const cargas = agenda.profissionais.map((p) =>
    cargaEmMinutos(agenda.agendamentos.filter(
      (a) => a.profissionalId === p.id && ocupaAGrade(a))));
  return Math.max(...cargas, 1);
}

/** Cancelado e falta continuam desenhados, mas não contam como dia vendido. */
function ocupaAGrade(a: Agendamento): boolean {
  return a.status === 'SOLICITADO' || a.status === 'CONFIRMADO'
    || a.status === 'EM_ATENDIMENTO' || a.status === 'CONCLUIDO';
}

/** O status por extenso. A tarja mostra cor e forma; o painel diz a palavra. */
const STATUS_POR_EXTENSO: Record<string, string> = {
  CONFIRMADO: 'Confirmado',
  SOLICITADO: 'Aguardando confirmação',
  EM_ATENDIMENTO: 'Em atendimento',
  CONCLUIDO: 'Concluído',
  NO_SHOW: 'Não compareceu',
  CANCELADO: 'Cancelado',
};

const CLASSE_POR_STATUS: Record<string, string> = {
  CONFIRMADO: 'confirmado',
  SOLICITADO: 'solicitado',
  EM_ATENDIMENTO: 'atendendo',
  CONCLUIDO: 'confirmado',
  NO_SHOW: 'falta',
  CANCELADO: 'cancelado',
};

function porExtenso(data: string): { dia: string; semana: string } {
  const [ano, mes, dia] = data.split('-').map(Number);
  const d = new Date(Date.UTC(ano, mes - 1, dia));
  return {
    dia: new Intl.DateTimeFormat('pt-BR', {
      timeZone: 'UTC', day: '2-digit', month: 'long',
    }).format(d),
    semana: new Intl.DateTimeFormat('pt-BR', { timeZone: 'UTC', weekday: 'long' }).format(d),
  };
}

const CHAVE_DA_DENSIDADE = 'salao:agenda-densidade';

/**
 * A densidade escolhida da última vez.
 *
 * Preferência de quem olha a tela, não permissão — a mesma natureza da lateral recolhida, e
 * por isso o mesmo lugar. Um salão não troca de tamanho toda semana: escolher a régua uma vez
 * por máquina é o que faz o controle valer a pena.
 */
function densidadeGuardada(): Densidade {
  try {
    const guardada = localStorage.getItem(CHAVE_DA_DENSIDADE);
    return guardada && guardada in DENSIDADES ? (guardada as Densidade) : 'padrao';
  } catch {
    // Navegador com armazenamento bloqueado ainda precisa desenhar a agenda.
    return 'padrao';
  }
}

/**
 * A régua da grade, em minutos por linha (R-UX-11).
 *
 * Mostra o número, e não "ampla/compacta": intervalo em minutos é o vocabulário do balcão —
 * "encaixe de 15 minutos" é como a recepção fala — e nomes de tamanho não dizem quanto vale
 * uma linha. O rótulo por extenso fica no título, para quem passa o mouse.
 */
function Densidades({ atual, aoEscolher }: {
  atual: Densidade;
  aoEscolher: (densidade: Densidade) => void;
}) {
  return (
    <div className="agenda-densidade" role="group" aria-label="Minutos por linha da grade">
      <span aria-hidden="true">min</span>
      {(Object.keys(DENSIDADES) as Densidade[]).map((nome) => (
        <button key={nome} type="button" aria-pressed={nome === atual}
          aria-label={`${DENSIDADES[nome]} minutos por linha`}
          title={`${DENSIDADES[nome]} minutos por linha`}
          onClick={() => aoEscolher(nome)}>
          {DENSIDADES[nome]}
          {/* No toque o botão estica e o rótulo solto lia como uma quarta opção (RT-INF-018):
              lá a unidade entra na opção. O nome acessível já vem do aria-label. */}
          <span className="agenda-densidade-unidade" aria-hidden="true"> min</span>
        </button>
      ))}
    </div>
  );
}

/**
 * RT-AGD-010 — quem cancela na grade fica sabendo que abriu vaga.
 *
 * Sem isto, a vaga só existe para quem lembra de abrir a Fila de espera — e o buraco que a
 * recepção acabou de criar é justamente o momento em que ela poderia preenchê-lo.
 *
 * Quem decide se há vaga com alguém que cabe é o servidor; aqui só aparece a contagem. A consulta é
 * a mesma da tela da fila, então abrir a fila depois disto não custa nova requisição.
 */
function AvisoDeVagas() {
  const vagas = useVagasAbertas();
  const quantas = vagas.data?.length ?? 0;

  if (quantas === 0) {
    return null;
  }
  return (
    <p className="agenda-vagas" role="status">
      {quantas === 1
        ? 'Abriu uma vaga e há quem esteja esperando por ela.'
        : `Abriram ${quantas} vagas e há quem esteja esperando por elas.`}
      <Link to="/fila">Ver a fila de espera</Link>
    </p>
  );
}

export function Agenda() {
  const { data: capacidades } = useCapacidades();
  const [parametros, definirParametros] = useSearchParams();
  const [escolhido, definirEscolhido] = useState<string | null>(null);
  const [densidade, definirDensidade] = useState<Densidade>(densidadeGuardada);
  /* RT-AGD-013 — o que o arrasto desenhou, esperando a pergunta "o quê". */
  const [pedidoDoGesto, definirPedidoDoGesto] = useState<Pedido | null>(null);

  /*
   * O dia é o dia do SALÃO, e ele está no endereço.
   *
   * Duas correções no mesmo lugar, porque são a mesma frase dita de dois jeitos.
   *
   * O dia vinha de `Intl.DateTimeFormat('en-CA')` sem fuso — o dia da MÁQUINA. Às 00h30
   * de São Paulo, um salão em Manaus ainda está no dia anterior, e a recepção abria a
   * agenda de amanhã sem ter como perceber. É o inviolável nº 3 e a ADR-0009: conversão
   * só na borda, com o fuso do estabelecimento.
   *
   * E o dia morava num `useState`: recarregar a página voltava para hoje, o endereço não
   * podia ser mandado para ninguém, e o botão "voltar" do navegador não desfazia a seta.
   * Dia é para onde se vai — então é endereço.
   */
  const fuso = capacidades?.estabelecimento?.timezone ?? fusoDoNavegador();
  const hoje = diaCivil(new Date(), fuso);
  const pedido = parametros.get('dia');
  // Endereço colado errado não derruba a tela: `?dia=31-02` cairia no `Intl` como data
  // inválida, que LANÇA, e a agenda inteira sumiria por causa da barra de endereço.
  const data = ehDiaCivil(pedido) ? pedido : hoje;

  /*
   * `replace` de propósito: andar seis dias com a seta não pode custar seis toques no
   * "voltar" para sair da agenda. O endereço continua certo para recarregar e para
   * mandar para alguém — que é o que se ganha aqui —, e hoje é o endereço sem parâmetro.
   */
  const irParaODia = (novo: string) => {
    const proximos = new URLSearchParams(parametros);
    if (novo === hoje) proximos.delete('dia');
    else proximos.set('dia', novo);
    definirParametros(proximos, { replace: true });
  };
  const { data: agenda, dataUpdatedAt, isPending, isPlaceholderData, error, refetch } =
    useAgendaDoDia(data);
  const conectado = useConectado();

  // Reavaliado a cada render; a linha do agora só precisa da precisão do minuto, e um
  // timer que redesenha a grade inteira a cada segundo é gasto sem ganho.
  const agora = new Date();
  const extenso = porExtenso(data);

  const px = pixelsPorMinuto(densidade);
  // A tela esconde o gesto de quem não escreve; o caso de uso é quem garante (inviolável nº 6).
  const podeEscrever = pode(capacidades, 'agenda:write:all');

  const escolherDensidade = (nova: Densidade) => {
    definirDensidade(nova);
    try {
      localStorage.setItem(CHAVE_DA_DENSIDADE, nova);
    } catch {
      // Navegador com armazenamento bloqueado: a escolha vale para esta sessão e pronto.
      // Perder a preferência é um aborrecimento; derrubar a agenda por causa dela, não.
    }
  };

  const faixa = useMemo<FaixaDoDia>(() => {
    if (!agenda) return { primeiraHora: 8, ultimaHora: 20 };
    const periodos = [
      ...Object.values(agenda.jornadas).flat(),
      ...agenda.agendamentos.map((a) => ({ inicio: a.inicio, fim: a.fim })),
    ];
    return faixaDoDia(periodos, agenda.fuso);
  }, [agenda]);

  const alturaDaPista = (faixa.ultimaHora - faixa.primeiraHora) * 60 * px;

  return (
    <section className="agenda" aria-label="Agenda do dia">
      <header className="agenda-topo">
        <div>
          <div className="agenda-data">{extenso.dia}</div>
          <div className="agenda-dia-da-semana">{extenso.semana}</div>
        </div>
        <div className="agenda-topo-espaco" />
        {!conectado && agenda && (
          <span className="agenda-dados-offline">
            Dados de {new Intl.DateTimeFormat('pt-BR', {
              hour: '2-digit', minute: '2-digit',
            }).format(new Date(dataUpdatedAt))}
          </span>
        )}
        <Densidades atual={densidade} aoEscolher={escolherDensidade} />

        <nav className="agenda-navegacao" aria-label="Navegar entre os dias">
          <button type="button" onClick={() => irParaODia(somarDias(data, -1))}
            aria-label="Dia anterior">←</button>
          {/* `aria-current="date"` diz o que a cor já diz para quem vê: é este o dia de
              hoje, e o botão não vai levar a lugar nenhum. */}
          <button type="button" onClick={() => irParaODia(hoje)}
            aria-current={data === hoje ? 'date' : undefined}>hoje</button>
          <button type="button" onClick={() => irParaODia(somarDias(data, 1))}
            aria-label="Próximo dia">→</button>
        </nav>
      </header>

      {/* Só para quem chama a fila (agenda:write:all). Quem não chama não tem o que fazer com o
          aviso, e o telefone das clientes está do outro lado do link. */}
      {capacidades?.flags?.podeAgendarParaOutros && <AvisoDeVagas />}

      {error && !agenda && (
        <Falha aoTentar={() => refetch()}>
          A agenda deste dia não chegou. Os dias já abertos continuam na tela.
        </Falha>
      )}
      {/* Esqueleto com a forma da grade — só na PRIMEIRA carga, porque a partir dela o
          dia anterior fica na tela (ver `useAgendaDoDia`). */}
      {isPending && !error && <Esqueleto linhas={7} altura={64} />}

      {agenda && agenda.profissionais.length === 0 && (
        <Vazio icone="pessoas" titulo="Ninguém para atender ainda">
          A agenda aparece quando houver profissional cadastrado com jornada.
        </Vazio>
      )}

      {agenda && agenda.profissionais.length > 0 && (
        <div className={`agenda-corpo${isPlaceholderData ? ' trocando' : ''}`}
          aria-busy={isPlaceholderData || undefined}>
          <Horas faixa={faixa} altura={alturaDaPista} px={px}
            agora={posicaoDoAgora(agora, agenda.data, faixa, agenda.fuso, px)}
            rotuloDoAgora={rotuloDeHora(agora.toISOString(), agenda.fuso)} />
          <div className="agenda-colunas">
            {agenda.profissionais.map((profissional) => (
              <Coluna
                key={profissional.id}
                agenda={agenda}
                faixa={faixa}
                altura={alturaDaPista}
                px={px}
                densidade={densidade}
                agora={agora}
                profissional={profissional}
                maiorCarga={maiorCarga(agenda)}
                aoEscolher={definirEscolhido}
                podeEscrever={podeEscrever}
                aoPedir={definirPedidoDoGesto}
              />
            ))}
          </div>
          {escolhido && agenda.agendamentos.some((a) => a.id === escolhido) && (
            <Detalhe
              agendamento={agenda.agendamentos.find((a) => a.id === escolhido)!}
              nome={agenda.nomesDeClientes[
                agenda.agendamentos.find((a) => a.id === escolhido)!.clienteId]}
              fuso={agenda.fuso}
              podeEscrever={podeEscrever}
              aoFechar={() => definirEscolhido(null)}
            />
          )}

          {/* O painel do gesto fica por cima da grade, ancorado no lado do detalhe: os dois
              nunca aparecem juntos, porque um nasce de arrastar no vazio e o outro de clicar
              numa tarja. */}
          {pedidoDoGesto && (
            <PainelDoGesto pedido={pedidoDoGesto} fuso={agenda.fuso}
              aoFechar={() => definirPedidoDoGesto(null)} />
          )}
        </div>
      )}
    </section>
  );
}

function Horas({ faixa, altura, px, agora, rotuloDoAgora }: {
  faixa: FaixaDoDia; altura: number; px: number; agora: number | null; rotuloDoAgora: string;
}) {
  const horas = [];
  for (let h = faixa.primeiraHora; h <= faixa.ultimaHora; h++) horas.push(h);

  return (
    <div className="agenda-horas" style={{ paddingTop: 44 }} aria-hidden="true">
      <div style={{ position: 'relative', height: altura }}>
        {horas.map((h) => (
          <span key={h} className="agenda-hora"
            style={{ top: (h - faixa.primeiraHora) * 60 * px }}>
            {String(h).padStart(2, '0')}:00
          </span>
        ))}
        {agora !== null && (
          <span className="agenda-selo-agora" style={{ top: agora }}>{rotuloDoAgora}</span>
        )}
      </div>
    </div>
  );
}

interface ColunaProps {
  agenda: AgendaDoDia;
  faixa: FaixaDoDia;
  altura: number;
  px: number;
  densidade: Densidade;
  agora: Date;
  profissional: { id: string; nome: string; apelido: string | null };
  maiorCarga: number;
  aoEscolher: (id: string) => void;
  podeEscrever: boolean;
  aoPedir: (pedido: Pedido) => void;
}

function Coluna({ agenda, faixa, altura, px, densidade, agora, profissional, maiorCarga,
                  aoEscolher, podeEscrever, aoPedir }: ColunaProps) {
  const pista = useRef<HTMLDivElement>(null);
  const reagendar = useReagendar();
  const removerBloqueio = useRemoverBloqueio();
  /*
   * A seleção mora na COLUNA, e o reagendamento na tela.
   *
   * Selecionar é sempre de uma pessoa só — marcar e bloquear são atos de uma agenda. Arrastar
   * uma tarja atravessa colunas, e por isso precisa de um estado que as duas enxerguem. Subir a
   * seleção junto faria a tela redesenhar a grade inteira a cada pixel de arrasto.
   */
  const [selecao, definirSelecao] = useState<{ a: number; b: number } | null>(null);
  /* A tarja que está sendo arrastada, e para que pixel. */
  const [movendo, definirMovendo] = useState<{ id: string; versao: number; topo: number } | null>(
    null);

  const topoDoPonteiro = (clientY: number) => {
    const caixa = pista.current?.getBoundingClientRect();
    return caixa ? clientY - caixa.top : 0;
  };

  const meus = agenda.agendamentos.filter((a) => a.profissionalId === profissional.id);
  const carga = cargaEmMinutos(meus.filter(ocupaAGrade));
  const jornada = agenda.jornadas[profissional.id] ?? [];
  const topoDoAgora = posicaoDoAgora(agora, agenda.data, faixa, agenda.fuso, px);
  const exibido = profissional.apelido ?? profissional.nome;

  return (
    <div className="agenda-coluna">
      <header className="agenda-profissional">
        <span className="agenda-inicial" aria-hidden="true">{exibido.charAt(0)}</span>
        <span className="agenda-nome">{exibido}</span>
        <span className="agenda-carga"
          title={`${rotuloDeCarga(carga)} de atendimento neste dia`}>
          <strong>{rotuloDeCarga(carga)}</strong>
          <span className="agenda-carga-barra">
            <div style={{ width: `${Math.min(100, (carga / maiorCarga) * 100)}%` }} />
          </span>
        </span>
      </header>

      <div
        ref={pista}
        className={`agenda-pista${podeEscrever ? ' escrevivel' : ''}`}
        style={{ height: altura }}
        data-pista={profissional.id}
        onPointerDown={(evento) => {
          // Botão esquerdo e só no vazio: tarja e bloqueio param a propagação.
          if (!podeEscrever || evento.button !== 0) return;
          evento.currentTarget.setPointerCapture(evento.pointerId);
          const topo = topoDoPonteiro(evento.clientY);
          definirSelecao({ a: topo, b: topo });
        }}
        onPointerMove={(evento) => {
          if (selecao) definirSelecao({ a: selecao.a, b: topoDoPonteiro(evento.clientY) });
        }}
        onPointerUp={(evento) => {
          if (!selecao) return;
          const periodo = periodoDoArrasto(selecao.a, topoDoPonteiro(evento.clientY), faixa,
            agenda.data, agenda.fuso, px, densidade);
          definirSelecao(null);
          aoPedir({
            profissionalId: profissional.id,
            profissionalNome: profissional.apelido ?? profissional.nome,
            ...periodo,
          });
        }}
        onPointerCancel={() => definirSelecao(null)}
      >
        {/* Fora da jornada primeiro: é fundo, e tudo o mais desenha por cima. */}
        <ForaDaJornada jornada={jornada} faixa={faixa} altura={altura} fuso={agenda.fuso}
          px={px} />

        {linhasDaGrade(faixa, densidade).map((l) => (
          <div key={l.minuto} className={`agenda-linha${l.cheia ? ' cheia' : ''}`}
            style={{ top: l.topo }} />
        ))}

        {agenda.bloqueios
          .filter((b) => b.profissionalId === profissional.id)
          .map((b) => {
            const { topo, altura: h } = posicao(b.janela.inicio, b.janela.fim, faixa,
              agenda.fuso, px);
            /*
             * Remover bloqueio não pede confirmação; cancelar atendimento pede.
             *
             * Um bloqueio não tem ninguém do outro lado — errar custa um clique. Cancelar chega
             * a uma cliente, e por isso exige motivo.
             */
            return podeEscrever ? (
              <button key={b.id} type="button" className="agenda-bloco bloqueio"
                style={{ top: topo, height: h }}
                title={b.descricao ?? b.motivo}
                aria-label={`Tirar o bloqueio de ${b.motivo.toLowerCase()}`}
                onPointerDown={(evento) => evento.stopPropagation()}
                onClick={() => removerBloqueio.mutate(b.id)}>
                <span className="cliente">{b.motivo.toLowerCase()}</span>
              </button>
            ) : (
              <div key={b.id} className="agenda-bloco bloqueio"
                style={{ top: topo, height: h }}
                title={b.descricao ?? b.motivo}>
                <span className="cliente">{b.motivo.toLowerCase()}</span>
              </div>
            );
          })}

        {meus.flatMap((a) => a.blocos.map((bloco, i) => (
          <Tarja key={`${a.id}-${i}`} agendamento={a} bloco={bloco} faixa={faixa}
            fuso={agenda.fuso} px={px} nome={agenda.nomesDeClientes[a.clienteId]}
            aoEscolher={aoEscolher}
            /* Só o primeiro bloco arrasta: os demais são pausa e recurso do mesmo
               atendimento, e arrastar a pausa moveria a coloração inteira por um pedaço
               que a pessoa não reconhece como "o atendimento". */
            aoArrastar={podeEscrever && i === 0
              ? (evento) => {
                evento.stopPropagation();
                (evento.currentTarget as HTMLElement).setPointerCapture(evento.pointerId);
                definirMovendo({ id: a.id, versao: a.versao,
                  topo: topoDoPonteiro(evento.clientY) });
              }
              : undefined}
            aoMover={movendo?.id === a.id
              ? (evento) => definirMovendo({ ...movendo, topo: topoDoPonteiro(evento.clientY) })
              : undefined}
            aoSoltar={movendo?.id === a.id
              ? (evento) => {
                const inicio = instanteDoPixel(topoDoPonteiro(evento.clientY), faixa,
                  agenda.data, agenda.fuso, px, densidade);
                definirMovendo(null);
                if (inicio !== a.inicio) {
                  reagendar.mutate({ id: a.id, inicio, versao: a.versao });
                }
              }
              : undefined}
            arrastada={movendo?.id === a.id ? movendo.topo : null}
          />
        )))}

        {/* A recusa aparece com a frase do servidor, e a tarja já voltou sozinha: o arrasto é
            otimista porque a agenda é uma tela de segundos, e otimista sem volta é mentira. */}
        {reagendar.isError && (
          <p className="agenda-recusa" role="alert">{mensagem(reagendar.error)}</p>
        )}

        {/* O período aparece enquanto se arrasta: arrastar às cegas é o que transforma um
            gesto rápido num 409 e numa conversa (R-UX-06). */}
        {selecao && (
          <div className="agenda-selecao" aria-hidden="true"
            style={{
              top: Math.min(selecao.a, selecao.b),
              height: Math.max(Math.abs(selecao.b - selecao.a), 12),
            }} />
        )}

        {topoDoAgora !== null && (
          <div className="agenda-agora" style={{ top: topoDoAgora }}
            role="separator" aria-label="Agora" />
        )}
      </div>
    </div>
  );
}

/**
 * O que está fora do expediente, escurecido.
 *
 * Coluna vazia e clara parece disponível — e a recepção ofereceria um horário em que a
 * profissional não trabalha, descobrindo o erro só quando o cadastro recusasse.
 */
function ForaDaJornada({ jornada, faixa, altura, fuso, px }: {
  jornada: Array<{ inicio: string; fim: string }>;
  faixa: FaixaDoDia; altura: number; fuso: string; px: number;
}) {
  if (jornada.length === 0) {
    return <div className="agenda-fora-da-jornada" style={{ top: 0, height: altura }} />;
  }
  const trechos: Array<{ topo: number; altura: number }> = [];
  let cursor = 0;
  for (const intervalo of jornada) {
    const { topo, altura: h } = posicao(intervalo.inicio, intervalo.fim, faixa, fuso, px);
    if (topo > cursor) trechos.push({ topo: cursor, altura: topo - cursor });
    cursor = topo + h;
  }
  if (cursor < altura) trechos.push({ topo: cursor, altura: altura - cursor });

  return (
    <>
      {trechos.map((t) => (
        <div key={t.topo} className="agenda-fora-da-jornada"
          style={{ top: t.topo, height: t.altura }} />
      ))}
    </>
  );
}

/**
 * Uma tarja: um bloco do agendamento, e não o agendamento inteiro.
 *
 * É a peça que distingue esta grade de uma agenda genérica. A pausa da coloração vira
 * hachura sem texto — a cliente está no salão e a profissional está livre —, e é isso
 * que permite ver o buraco de 40 minutos onde cabe um encaixe.
 */
function Tarja({ agendamento, bloco, faixa, fuso, px, nome, aoEscolher,
                aoArrastar, aoMover, aoSoltar, arrastada }: {
  agendamento: Agendamento; bloco: BlocoDoAgendamento;
  faixa: FaixaDoDia; fuso: string; px: number; nome?: string;
  aoEscolher: (id: string) => void;
  /** Ausentes quando esta tarja não arrasta — sem permissão, ou não é o primeiro bloco. */
  aoArrastar?: (evento: React.PointerEvent<HTMLElement>) => void;
  aoMover?: (evento: React.PointerEvent<HTMLElement>) => void;
  aoSoltar?: (evento: React.PointerEvent<HTMLElement>) => void;
  /** O pixel para onde o ponteiro a levou; nulo quando não está sendo arrastada. */
  arrastada: number | null;
}) {
  const { topo, altura } = posicao(bloco.janela.inicio, bloco.janela.fim, faixa, fuso, px);
  const pausa = bloco.profissionalId === null;
  const classe = pausa ? 'pausa' : (CLASSE_POR_STATUS[agendamento.status] ?? 'confirmado');
  const hora = rotuloDeHora(bloco.janela.inicio, fuso);

  if (pausa) {
    return (
      <div className="agenda-bloco pausa" style={{ top: topo, height: altura }}
        title={`Química agindo — ${nome ?? 'cliente'} está no salão`} aria-hidden="true" />
    );
  }
  return (
    <button type="button"
      className={`agenda-bloco ${classe}${arrastada !== null ? ' arrastando' : ''}`}
      // Enquanto arrasta, a tarja segue o ponteiro: o destino aparece antes de soltar.
      style={{ top: arrastada ?? topo, height: altura }}
      onPointerDown={aoArrastar}
      onPointerMove={aoMover}
      onPointerUp={aoSoltar}
      onClick={() => aoEscolher(agendamento.id)}>
      <span className="hora">{hora}</span>{' '}
      <span className="cliente">{nome ?? 'cliente'}</span>
    </button>
  );
}

/**
 * O que não cabe na tarja.
 *
 * Uma tarja de 20px comporta a hora e um nome truncado. Sem este painel, o clique nela
 * seria affordance falsa — e um botão que não faz nada é pior que um bloco que não é
 * botão.
 *
 * `minutosDoProfissional` aparece separado da duração de propósito: numa coloração de
 * 100 minutos, 60 são dela e 40 são química. É a diferença que decide se cabe um
 * encaixe, e é invisível na grade.
 */
/**
 * O detalhe, que agora também age.
 *
 * Confirmar e cancelar são os dois cliques mais frequentes da recepção, e estavam fora da grade
 * — pendência de RT-AGD-011. Cada um leva a `versao` que a tela leu: se alguém mexeu antes, o
 * servidor recusa em vez de sobrescrever.
 */
function Detalhe({ agendamento, nome, fuso, podeEscrever, aoFechar }: {
  agendamento: Agendamento; nome?: string; fuso: string; podeEscrever: boolean;
  aoFechar: () => void;
}) {
  const duracao = agendamento.blocos.reduce((soma, b) => soma
    + (new Date(b.janela.fim).getTime() - new Date(b.janela.inicio).getTime()) / 60000, 0);

  return (
    <aside className="agenda-detalhe" aria-label="Detalhe do atendimento">
      <button type="button" className="agenda-detalhe-fechar" onClick={aoFechar}
        aria-label="Fechar detalhe">×</button>
      <h3>{nome ?? 'Cliente'}</h3>
      <dl>
        <dt>Horário</dt>
        <dd>{rotuloDeHora(agendamento.inicio, fuso)} — {rotuloDeHora(agendamento.fim, fuso)}</dd>
        <dt>Status</dt>
        <dd>{STATUS_POR_EXTENSO[agendamento.status] ?? agendamento.status}</dd>
        <dt>Duração</dt>
        <dd>{rotuloDeCarga(duracao)}</dd>
        <dt>Da agenda</dt>
        <dd>{rotuloDeCarga(agendamento.minutosDoProfissional)}</dd>
      </dl>

      {podeEscrever && <AcoesDoAtendimento agendamento={agendamento} aoFechar={aoFechar} />}
    </aside>
  );
}

/** Termina o que já acabou de um jeito ou de outro: não há o que fazer com um cancelado. */
function podeAgir(status: string): boolean {
  return status === 'SOLICITADO' || status === 'CONFIRMADO';
}

function AcoesDoAtendimento({ agendamento, aoFechar }: {
  agendamento: Agendamento; aoFechar: () => void;
}) {
  const confirmar = useConfirmar();
  const cancelar = useCancelar();
  const falta = useRegistrarFalta();
  const [cancelando, definirCancelando] = useState(false);
  const [motivo, definirMotivo] = useState('');
  const [pelaCliente, definirPelaCliente] = useState(true);

  if (!podeAgir(agendamento.status)) return null;

  const erro = confirmar.error ?? cancelar.error ?? falta.error;

  return (
    <div className="agenda-detalhe-acoes">
      {!cancelando && (
        <div className="agenda-detalhe-botoes">
          {agendamento.status === 'SOLICITADO' && (
            <button type="button" className="pagina-acao principal"
              disabled={confirmar.isPending}
              onClick={() => confirmar.mutate(
                { id: agendamento.id, versao: agendamento.versao })}>
              {confirmar.isPending ? 'Confirmando…' : 'Confirmar'}
            </button>
          )}
          <button type="button" className="pagina-acao discreta"
            onClick={() => definirCancelando(true)}>Cancelar</button>
          {/* Sem motivo, ao contrário do cancelamento: o motivo da falta é a própria ausência. */}
          <button type="button" className="pagina-acao discreta" disabled={falta.isPending}
            onClick={() => falta.mutate({ id: agendamento.id, versao: agendamento.versao },
              { onSuccess: aoFechar })}>
            {falta.isPending ? 'Registrando…' : 'Não veio'}
          </button>
        </div>
      )}

      {cancelando && (
        <form
          aria-label="Cancelar o atendimento"
          onSubmit={(evento) => {
            evento.preventDefault();
            cancelar.mutate(
              { id: agendamento.id, versao: agendamento.versao, motivo: motivo.trim(),
                pelaCliente },
              { onSuccess: aoFechar });
          }}
        >
          <label className="agd-campo">
            <span>Por que está sendo cancelado</span>
            <input value={motivo} autoFocus placeholder="a cliente remarcou para sexta"
              onChange={(e) => definirMotivo(e.target.value)} />
          </label>
          <label className="agd-repetir">
            <input type="checkbox" checked={pelaCliente}
              onChange={(e) => definirPelaCliente(e.target.checked)} />
            <span>Quem desmarcou foi a cliente</span>
          </label>
          <p className="agd-ajuda">
            O motivo fica no histórico dela — é ele que sustenta a conversa sobre quem desmarca
            demais.
          </p>
          <div className="agenda-detalhe-botoes">
            <button type="submit" className="pagina-acao principal"
              disabled={motivo.trim() === '' || cancelar.isPending}>
              {cancelar.isPending ? 'Cancelando…' : 'Cancelar o atendimento'}
            </button>
            <button type="button" className="pagina-acao discreta"
              onClick={() => definirCancelando(false)}>Voltar</button>
          </div>
        </form>
      )}

      {erro && <p className="erro" role="alert">{mensagem(erro)}</p>}
    </div>
  );
}
