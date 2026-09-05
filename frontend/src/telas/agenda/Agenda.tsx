import { useMemo, useState } from 'react';
import {
  useAgendaDoDia, type Agendamento, type AgendaDoDia, type BlocoDoAgendamento,
} from '../../api/agenda';
import {
  cargaEmMinutos, faixaDoDia, posicao, posicaoDoAgora, rotuloDeCarga, rotuloDeHora,
  PIXELS_POR_MINUTO, type FaixaDoDia,
} from './grade';
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

function hoje(): string {
  return new Intl.DateTimeFormat('en-CA').format(new Date());
}

function somarDias(data: string, dias: number): string {
  const [ano, mes, dia] = data.split('-').map(Number);
  const d = new Date(Date.UTC(ano, mes - 1, dia + dias));
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'UTC' }).format(d);
}

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

export function Agenda() {
  const [data, definirData] = useState(hoje());
  const [escolhido, definirEscolhido] = useState<string | null>(null);
  const { data: agenda, isPending, error } = useAgendaDoDia(data);

  // Reavaliado a cada render; a linha do agora só precisa da precisão do minuto, e um
  // timer que redesenha a grade inteira a cada segundo é gasto sem ganho.
  const agora = new Date();
  const extenso = porExtenso(data);

  const faixa = useMemo<FaixaDoDia>(() => {
    if (!agenda) return { primeiraHora: 8, ultimaHora: 20 };
    const periodos = [
      ...Object.values(agenda.jornadas).flat(),
      ...agenda.agendamentos.map((a) => ({ inicio: a.inicio, fim: a.fim })),
    ];
    return faixaDoDia(periodos, agenda.fuso);
  }, [agenda]);

  const alturaDaPista = (faixa.ultimaHora - faixa.primeiraHora) * 60 * PIXELS_POR_MINUTO;

  return (
    <section className="agenda" aria-label="Agenda do dia">
      <header className="agenda-topo">
        <div>
          <div className="agenda-data">{extenso.dia}</div>
          <div className="agenda-dia-da-semana">{extenso.semana}</div>
        </div>
        <div style={{ flexGrow: 1 }} />
        <nav className="agenda-navegacao" aria-label="Navegar entre os dias">
          <button type="button" onClick={() => definirData(somarDias(data, -1))}
            aria-label="Dia anterior">←</button>
          <button type="button" onClick={() => definirData(hoje())}>hoje</button>
          <button type="button" onClick={() => definirData(somarDias(data, 1))}
            aria-label="Próximo dia">→</button>
        </nav>
      </header>

      {error && <p className="agenda-vazia">Não foi possível carregar a agenda deste dia.</p>}
      {isPending && !error && <p className="agenda-vazia">Carregando a agenda…</p>}

      {agenda && agenda.profissionais.length === 0 && (
        <p className="agenda-vazia">
          Nenhum profissional cadastrado. A agenda aparece quando houver quem atenda.
        </p>
      )}

      {agenda && agenda.profissionais.length > 0 && (
        <div className="agenda-corpo">
          <Horas faixa={faixa} altura={alturaDaPista}
            agora={posicaoDoAgora(agora, agenda.data, faixa, agenda.fuso)}
            rotuloDoAgora={rotuloDeHora(agora.toISOString(), agenda.fuso)} />
          <div className="agenda-colunas">
            {agenda.profissionais.map((profissional) => (
              <Coluna
                key={profissional.id}
                agenda={agenda}
                faixa={faixa}
                altura={alturaDaPista}
                agora={agora}
                profissional={profissional}
                maiorCarga={maiorCarga(agenda)}
                aoEscolher={definirEscolhido}
              />
            ))}
          </div>
          {escolhido && agenda.agendamentos.some((a) => a.id === escolhido) && (
            <Detalhe
              agendamento={agenda.agendamentos.find((a) => a.id === escolhido)!}
              nome={agenda.nomesDeClientes[
                agenda.agendamentos.find((a) => a.id === escolhido)!.clienteId]}
              fuso={agenda.fuso}
              aoFechar={() => definirEscolhido(null)}
            />
          )}
        </div>
      )}
    </section>
  );
}

function Horas({ faixa, altura, agora, rotuloDoAgora }: {
  faixa: FaixaDoDia; altura: number; agora: number | null; rotuloDoAgora: string;
}) {
  const horas = [];
  for (let h = faixa.primeiraHora; h <= faixa.ultimaHora; h++) horas.push(h);

  return (
    <div className="agenda-horas" style={{ paddingTop: 44 }} aria-hidden="true">
      <div style={{ position: 'relative', height: altura }}>
        {horas.map((h) => (
          <span key={h} className="agenda-hora"
            style={{ top: (h - faixa.primeiraHora) * 60 * PIXELS_POR_MINUTO }}>
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
  agora: Date;
  profissional: { id: string; nome: string; apelido: string | null };
  maiorCarga: number;
  aoEscolher: (id: string) => void;
}

function Coluna({ agenda, faixa, altura, agora, profissional, maiorCarga,
                  aoEscolher }: ColunaProps) {
  const meus = agenda.agendamentos.filter((a) => a.profissionalId === profissional.id);
  const carga = cargaEmMinutos(meus.filter(ocupaAGrade));
  const jornada = agenda.jornadas[profissional.id] ?? [];
  const topoDoAgora = posicaoDoAgora(agora, agenda.data, faixa, agenda.fuso);
  const exibido = profissional.apelido ?? profissional.nome;

  const linhas = [];
  for (let h = faixa.primeiraHora; h <= faixa.ultimaHora; h++) {
    linhas.push({ hora: h, cheia: true });
    if (h < faixa.ultimaHora) linhas.push({ hora: h + 0.5, cheia: false });
  }

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

      <div className="agenda-pista" style={{ height: altura }}>
        {/* Fora da jornada primeiro: é fundo, e tudo o mais desenha por cima. */}
        <ForaDaJornada jornada={jornada} faixa={faixa} altura={altura} fuso={agenda.fuso} />

        {linhas.map((l) => (
          <div key={l.hora} className={`agenda-linha${l.cheia ? ' cheia' : ''}`}
            style={{ top: (l.hora - faixa.primeiraHora) * 60 * PIXELS_POR_MINUTO } } />
        ))}

        {agenda.bloqueios
          .filter((b) => b.profissionalId === profissional.id)
          .map((b) => {
            const { topo, altura: h } = posicao(b.janela.inicio, b.janela.fim, faixa,
              agenda.fuso);
            return (
              <div key={b.id} className="agenda-bloco bloqueio"
                style={{ top: topo, height: h }}
                title={b.descricao ?? b.motivo}>
                <span className="cliente">{b.motivo.toLowerCase()}</span>
              </div>
            );
          })}

        {meus.flatMap((a) => a.blocos.map((bloco, i) => (
          <Tarja key={`${a.id}-${i}`} agendamento={a} bloco={bloco} faixa={faixa}
            fuso={agenda.fuso} nome={agenda.nomesDeClientes[a.clienteId]}
            aoEscolher={aoEscolher} />
        )))}

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
function ForaDaJornada({ jornada, faixa, altura, fuso }: {
  jornada: Array<{ inicio: string; fim: string }>;
  faixa: FaixaDoDia; altura: number; fuso: string;
}) {
  if (jornada.length === 0) {
    return <div className="agenda-fora-da-jornada" style={{ top: 0, height: altura }} />;
  }
  const trechos: Array<{ topo: number; altura: number }> = [];
  let cursor = 0;
  for (const intervalo of jornada) {
    const { topo, altura: h } = posicao(intervalo.inicio, intervalo.fim, faixa, fuso);
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
function Tarja({ agendamento, bloco, faixa, fuso, nome, aoEscolher }: {
  agendamento: Agendamento; bloco: BlocoDoAgendamento;
  faixa: FaixaDoDia; fuso: string; nome?: string; aoEscolher: (id: string) => void;
}) {
  const { topo, altura } = posicao(bloco.janela.inicio, bloco.janela.fim, faixa, fuso);
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
    <button type="button" className={`agenda-bloco ${classe}`}
      style={{ top: topo, height: altura }}
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
function Detalhe({ agendamento, nome, fuso, aoFechar }: {
  agendamento: Agendamento; nome?: string; fuso: string; aoFechar: () => void;
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
    </aside>
  );
}
