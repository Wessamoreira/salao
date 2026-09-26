import { useState } from 'react';
import {
  useBloquear, useCriarAgendamento, useCriarSerie, usePorDiaNaFila, MOTIVOS_DO_BLOQUEIO,
  type Frequencia, type MotivoDoBloqueio, type SerieCriada,
} from '../../api/agenda';
import { useBusca } from '../../api/clientes';
import { emHoras, useServicos } from '../../api/catalogo';
import { ErroDaApi } from '../../api/http';
import { rotuloDeHora } from './grade';

/** O que o arrasto desenhou: uma coluna e um pedaço de tempo. */
export interface Pedido {
  profissionalId: string;
  profissionalNome: string;
  inicio: string;
  fim: string;
}

/**
 * RT-AGD-013 — o painel que o arrasto abre.
 *
 * <h2>O gesto define o período; o painel pergunta só o quê</h2>
 *
 * Uma tela com seis campos obrigatórios faria a recepção anotar no papel — que é o que este
 * sistema existe para evitar. É o que o próprio `BloqueiosDaGradeController` já dizia:
 * *"o período vem do gesto"*.
 */
export function PainelDoGesto({ pedido, fuso, aoFechar }: {
  pedido: Pedido;
  fuso: string;
  aoFechar: () => void;
}) {
  const [escolha, definirEscolha] = useState<'marcar' | 'bloquear' | null>(null);

  return (
    <aside className="agd-gesto" aria-label="O que fazer com este horário">
      <header>
        <div>
          <strong>{rotuloDeHora(pedido.inicio, fuso)} — {rotuloDeHora(pedido.fim, fuso)}</strong>
          <p>{pedido.profissionalNome}</p>
        </div>
        <button type="button" className="agd-gesto-fechar" onClick={aoFechar}
          aria-label="Fechar">×</button>
      </header>

      {escolha === null && (
        <div className="agd-gesto-escolha">
          <button type="button" className="pagina-acao principal"
            onClick={() => definirEscolha('marcar')}>Marcar atendimento</button>
          <button type="button" className="pagina-acao discreta"
            onClick={() => definirEscolha('bloquear')}>Bloquear o horário</button>
        </div>
      )}

      {escolha === 'marcar' && <Marcar pedido={pedido} fuso={fuso} aoFechar={aoFechar} />}
      {escolha === 'bloquear' && <Bloquear pedido={pedido} aoFechar={aoFechar} />}
    </aside>
  );
}

/**
 * Marcar: quem, o quê, e — se for o caso — de quanto em quanto tempo ela volta.
 *
 * <h2>A duração é do serviço, não do arrasto</h2>
 *
 * Um atendimento tem duração própria, com os blocos dele (RT-CAT-002). Deixar o arrasto mandar
 * faria um arrasto torto virar uma coloração de vinte minutos. O painel mostra em que hora
 * termina, para que a diferença entre o que se arrastou e o que se marca seja visível.
 *
 * <h2>A recorrência mora aqui, e não numa tela de séries</h2>
 *
 * A decisão de repetir se toma no mesmo segundo em que se marca a primeira. Uma tela à parte
 * seria mais um lugar para esquecer de olhar.
 */
function Marcar({ pedido, fuso, aoFechar }: {
  pedido: Pedido; fuso: string; aoFechar: () => void;
}) {
  const servicos = useServicos(false);
  const criar = useCriarAgendamento();
  const serie = useCriarSerie();

  const [termo, definirTermo] = useState('');
  const [cliente, definirCliente] = useState<{ id: string; nome: string } | null>(null);
  const [servicoId, definirServico] = useState('');
  const [repetir, definirRepetir] = useState(false);
  const [frequencia, definirFrequencia] = useState<Frequencia>('SEMANAL');
  const [quantidade, definirQuantidade] = useState('8');
  const [justificativa, definirJustificativa] = useState('');

  const achados = useBusca(cliente ? '' : termo);
  const escolhido = servicos.data?.find((s) => s.id === servicoId);
  const enviando = criar.isPending || serie.isPending;
  const pode = cliente !== null && servicoId !== '' && !enviando;

  // O servidor recusa fora da jornada e oferece o caminho: a tela pergunta em vez de forçar
  // escondido (RT-AGD-002).
  const foraDaJornada = criar.error instanceof ErroDaApi
    && criar.error.codigo === 'ER-AGD-FORA_DA_JORNADA';

  if (serie.isSuccess) {
    return <DepoisDaSerie serie={serie.data} fuso={fuso} aoFechar={aoFechar} />;
  }

  return (
    <form
      className="agd-gesto-forma"
      aria-label="Marcar atendimento"
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!pode) return;
        const dados = {
          clienteId: cliente.id, profissionalId: pedido.profissionalId,
          servicoId, inicio: pedido.inicio,
          ...(foraDaJornada
            ? { forcarForaJornada: true, justificativa: justificativa.trim() }
            : {}),
        };
        if (repetir) {
          serie.mutate({ ...dados, frequencia, quantidade: Number(quantidade) });
        } else {
          criar.mutate(dados, { onSuccess: aoFechar });
        }
      }}
    >
      {cliente === null ? (
        <label className="agd-campo">
          <span>Cliente</span>
          <input value={termo} autoFocus placeholder="Nome ou telefone"
            onChange={(e) => definirTermo(e.target.value)} />
          {achados.data && achados.data.itens.length > 0 && (
            <ul className="agd-achados">
              {achados.data.itens.slice(0, 6).map((c) => (
                <li key={c.id}>
                  <button type="button"
                    onClick={() => definirCliente({ id: c.id, nome: c.nome })}>
                    {c.nome}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </label>
      ) : (
        <p className="agd-escolhido">
          <strong>{cliente.nome}</strong>
          <button type="button" className="pagina-acao discreta"
            onClick={() => { definirCliente(null); definirTermo(''); }}>trocar</button>
        </p>
      )}

      <label className="agd-campo">
        <span>Serviço</span>
        <select value={servicoId} onChange={(e) => definirServico(e.target.value)}>
          <option value="">escolha</option>
          {servicos.data?.map((s) => (
            <option key={s.id} value={s.id}>{s.nome} · {emHoras(s.duracaoTotal)}</option>
          ))}
        </select>
      </label>

      {escolhido && (
        <p className="agd-ajuda">
          {/* A diferença entre o que se arrastou e o que se marca fica visível, não escondida. */}
          Começa {rotuloDeHora(pedido.inicio, fuso)} e leva {emHoras(escolhido.duracaoTotal)} —
          a duração é a do serviço, não a do arrasto.
        </p>
      )}

      <label className="agd-repetir">
        <input type="checkbox" checked={repetir}
          onChange={(e) => definirRepetir(e.target.checked)} />
        <span>Ela volta sempre</span>
      </label>

      {repetir && (
        <div className="agd-serie">
          <label className="agd-campo">
            <span>De quanto em quanto</span>
            <select value={frequencia}
              onChange={(e) => definirFrequencia(e.target.value as Frequencia)}>
              <option value="SEMANAL">toda semana</option>
              <option value="QUINZENAL">a cada quinze dias</option>
            </select>
          </label>
          <label className="agd-campo">
            <span>Quantas vezes</span>
            <input value={quantidade} inputMode="numeric"
              aria-label="Quantas vezes"
              onChange={(e) => definirQuantidade(e.target.value.replace(/\D/g, ''))} />
          </label>
        </div>
      )}

      {foraDaJornada && (
        <label className="agd-campo agd-atencao">
          <span>Fora da jornada dela. Por que marcar assim mesmo?</span>
          <input value={justificativa} autoFocus
            placeholder="a cliente só consegue nesse horário"
            onChange={(e) => definirJustificativa(e.target.value)} />
        </label>
      )}

      {(criar.isError || serie.isError) && !foraDaJornada && (
        <p className="erro" role="alert">{mensagem(criar.error ?? serie.error)}</p>
      )}

      <div className="agd-gesto-acoes">
        <button type="submit" className="pagina-acao principal"
          disabled={!pode || (foraDaJornada && justificativa.trim() === '')}>
          {enviando ? 'Marcando…' : foraDaJornada ? 'Marcar assim mesmo' : 'Marcar'}
        </button>
        <button type="button" className="pagina-acao discreta" onClick={aoFechar}>Cancelar</button>
      </div>
    </form>
  );
}

/**
 * O que a série produziu — e, principalmente, o que ela não conseguiu.
 *
 * Esconder as recusadas faria a recepção descobrir uma a uma quando as clientes ligassem.
 */
function DepoisDaSerie({ serie, fuso, aoFechar }: {
  serie: SerieCriada; fuso: string; aoFechar: () => void;
}) {
  const enfileirar = usePorDiaNaFila(serie.recorrencia.id);
  const [naFila, definirNaFila] = useState<string[]>([]);

  return (
    <div className="agd-gesto-forma">
      <p className="agd-ajuda">
        <strong>{serie.agendamentos.length}</strong> marcados.
        {serie.naoCouberam.length > 0 && ` ${serie.naoCouberam.length} não couberam.`}
      </p>

      {serie.naoCouberam.length > 0 && (
        <ul className="agd-nao-couberam">
          {serie.naoCouberam.map((instante) => (
            <li key={instante}>
              <span>{new Date(instante).toLocaleDateString('pt-BR', { timeZone: fuso })}</span>
              {naFila.includes(instante)
                ? <span className="agd-marca">na fila</span>
                : (
                  <button type="button" className="pagina-acao discreta"
                    disabled={enfileirar.isPending}
                    onClick={() => enfileirar.mutate(instante.slice(0, 10),
                      { onSuccess: () => definirNaFila([...naFila, instante]) })}>
                    Pôr na fila
                  </button>
                )}
            </li>
          ))}
        </ul>
      )}

      {enfileirar.isError && <p className="erro" role="alert">{mensagem(enfileirar.error)}</p>}

      <div className="agd-gesto-acoes">
        <button type="button" className="pagina-acao principal" onClick={aoFechar}>Pronto</button>
      </div>
    </div>
  );
}

/** Bloquear: aqui o arrasto é a resposta inteira, e só o motivo precisa ser escolhido. */
function Bloquear({ pedido, aoFechar }: { pedido: Pedido; aoFechar: () => void }) {
  const bloquear = useBloquear();
  const [motivo, definirMotivo] = useState<MotivoDoBloqueio>('PESSOAL');
  const [descricao, definirDescricao] = useState('');

  return (
    <form
      className="agd-gesto-forma"
      aria-label="Bloquear o horário"
      onSubmit={(evento) => {
        evento.preventDefault();
        bloquear.mutate({
          profissionalId: pedido.profissionalId, motivo,
          descricao: descricao.trim() || null,
          inicio: pedido.inicio, fim: pedido.fim,
        }, { onSuccess: aoFechar });
      }}
    >
      <label className="agd-campo">
        <span>Por quê</span>
        <select value={motivo} autoFocus
          onChange={(e) => definirMotivo(e.target.value as MotivoDoBloqueio)}>
          {MOTIVOS_DO_BLOQUEIO.map((m) => (
            <option key={m.valor} value={m.valor}>{m.rotulo}</option>
          ))}
        </select>
      </label>
      <label className="agd-campo">
        <span>Observação</span>
        <input value={descricao} onChange={(e) => definirDescricao(e.target.value)} />
      </label>

      {bloquear.isError && <p className="erro" role="alert">{mensagem(bloquear.error)}</p>}

      <div className="agd-gesto-acoes">
        <button type="submit" className="pagina-acao principal" disabled={bloquear.isPending}>
          {bloquear.isPending ? 'Bloqueando…' : 'Bloquear'}
        </button>
        <button type="button" className="pagina-acao discreta" onClick={aoFechar}>Cancelar</button>
      </div>
    </form>
  );
}

/** A recusa do servidor, com as palavras dele. */
export function mensagem(erro: unknown): string {
  if (erro instanceof ErroDaApi) return erro.message;
  return 'Não foi possível falar com o servidor. Tente de novo.';
}
