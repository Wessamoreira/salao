import { useState } from 'react';
import {
  useEntrarNaFila, useFilaDeEspera, useRegistrarChamada, useSairDaFila, useVagasAbertas,
  type Candidata, type Chamada, type Espera, type Resultado, type Vaga,
} from '../../api/fila';
import { useCapacidades } from '../../api/capacidades';
import { formatarTelefone, useBusca, comoChamar, type Cliente } from '../../api/clientes';
import { useProfissionais, useServicos } from '../../api/comandas';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { formatar } from '../../layout/tempo';
import './fila.css';

/**
 * RT-AGD-010 — a fila de espera: as vagas que abriram, quem ligar, e quem está esperando.
 *
 * <h2>A recepção chama; o sistema organiza</h2>
 *
 * Decisão do dono (22/09/2026). Sem provedor de WhatsApp, uma oferta automática não chegaria a
 * ninguém. A tela é o papel ao lado do telefone, só que não se perde quando a vaga abre uma semana
 * depois, com outra pessoa no balcão.
 *
 * <h2>Nada aqui decide</h2>
 *
 * Quem cabe, em que ordem, os horários possíveis e se chamar alguém exige motivo vêm prontos do
 * servidor. O número na frente de cada nome é a ordem de chegada — informação, e não enfeite.
 */
export function Fila() {
  const { data: capacidades } = useCapacidades();
  const fuso = capacidades?.estabelecimento.timezone ?? 'America/Sao_Paulo';

  return (
    <Pagina>
      <div className="fil-tela">
        <VagasAbertas fuso={fuso} />
        <NaFila fuso={fuso} />
      </div>
    </Pagina>
  );
}

// ------------------------------------------------------------------ vagas abertas

function VagasAbertas({ fuso }: { fuso: string }) {
  const vagas = useVagasAbertas();

  return (
    <Secao
      id="fil-vagas"
      titulo="Vagas abertas"
      ajuda={'Horários que abriram por cancelamento ou remarcação, com quem da fila cabe neles. '
        + 'Ligue na ordem: quem recusa ou não atende continua na fila.'}
    >

      {vagas.isPending ? <Esqueleto linhas={2} altura={140} />
        : vagas.isError ? <Falha aoTentar={() => vagas.refetch()}>{mensagem(vagas.error)}</Falha>
          : vagas.data.length === 0
            ? (
              <p className="fil-nada">
                Nenhuma vaga com alguém da fila que caiba. Quando alguém cancelar ou remarcar, a vaga
                aparece aqui com quem ligar.
              </p>
            )
            : (
              <ul className="fil-vagas">
                {vagas.data.map((vaga) => <CartaoDaVaga key={vaga.id} vaga={vaga} fuso={fuso} />)}
              </ul>
            )}
    </Secao>
  );
}

function CartaoDaVaga({ vaga, fuso }: { vaga: Vaga; fuso: string }) {
  // Uma chamada aberta por vez na vaga: a recepção está numa ligação só.
  const [aberta, definirAberta] = useState<{ esperaId: string; resultado: Resultado } | null>(null);

  return (
    <li className="fil-vaga">
      <header className="fil-vaga-cabecalho">
        <h3 className="fil-vaga-quando">
          <span>{vaga.profissional ?? 'Profissional'}</span>
          <span className="fil-vaga-hora">
            {dia(vaga.inicio, fuso)} · {hora(vaga.inicio, fuso)}–{hora(vaga.fim, fuso)}
          </span>
        </h3>
        <p className="fil-etiqueta">
          {vaga.origem === 'CANCELAMENTO' ? 'Cancelamento' : 'Remarcação'}
        </p>
      </header>

      <ol className="fil-candidatas">
        {vaga.candidatas.map((candidata) => (
          <LinhaDaCandidata
            key={candidata.esperaId}
            vaga={vaga}
            candidata={candidata}
            fuso={fuso}
            aberta={aberta?.esperaId === candidata.esperaId ? aberta.resultado : null}
            aoAbrir={(resultado) => definirAberta(
              aberta?.esperaId === candidata.esperaId && aberta.resultado === resultado
                ? null : { esperaId: candidata.esperaId, resultado })}
            aoFechar={() => definirAberta(null)}
          />
        ))}
      </ol>
    </li>
  );
}

function LinhaDaCandidata({ vaga, candidata, fuso, aberta, aoAbrir, aoFechar }: {
  vaga: Vaga;
  candidata: Candidata;
  fuso: string;
  aberta: Resultado | null;
  aoAbrir: (resultado: Resultado) => void;
  aoFechar: () => void;
}) {
  const registrar = useRegistrarChamada();
  const nome = candidata.cliente ?? 'Cliente';

  /**
   * Recusou e não atendeu saem num clique — a recepção está com o telefone no ombro. O painel só
   * abre quando há o que perguntar: o horário de quem aceitou, ou o motivo de chamar fora da ordem.
   */
  const aoEscolher = (resultado: Resultado) => {
    registrar.reset();
    if (resultado === 'ACEITOU' || candidata.exigeMotivo) {
      aoAbrir(resultado);
      return;
    }
    registrar.mutate({ vagaId: vaga.id, esperaId: candidata.esperaId, resultado });
  };

  return (
    <li className="fil-candidata">
      <span className="fil-posicao" aria-label={`${candidata.posicao}ª na ordem`}>
        {candidata.posicao}
      </span>

      <div className="fil-candidata-corpo">
        <p className="fil-quem">
          <span className="fil-nome">{nome}</span>
          {candidata.telefone && (
            <a className="fil-telefone" href={`tel:+${candidata.telefone}`}>
              {formatarTelefone(candidata.telefone)}
            </a>
          )}
        </p>
        <p className="fil-detalhe">
          {candidata.servico ?? 'Serviço'} · aceita {janela(candidata)} · na fila desde{' '}
          {diaCurto(candidata.naFilaDesde, fuso)}
        </p>
        {candidata.chamadas.length > 0 && (
          <ul className="fil-chamadas" aria-label="Chamadas nesta vaga">
            {candidata.chamadas.map((chamada, i) => (
              <li key={`${chamada.registradaEm}-${i}`}>{descrever(chamada, fuso)}</li>
            ))}
          </ul>
        )}
      </div>

      <div className="fil-acoes" role="group" aria-label={`Resultado da ligação para ${nome}`}>
        {(['ACEITOU', 'RECUSOU', 'NAO_ATENDEU'] as const).map((resultado) => (
          <button key={resultado} type="button" className="fil-botao"
            aria-expanded={resultado === 'ACEITOU' || candidata.exigeMotivo
              ? aberta === resultado : undefined}
            disabled={registrar.isPending}
            onClick={() => aoEscolher(resultado)}>
            {ROTULOS[resultado]}
          </button>
        ))}
      </div>

      {registrar.isError && !aberta && (
        <p className="erro fil-erro" role="alert">{mensagem(registrar.error)}</p>
      )}

      {aberta && (
        <PainelDaChamada
          vaga={vaga}
          candidata={candidata}
          resultado={aberta}
          fuso={fuso}
          aoFechar={aoFechar}
        />
      )}
    </li>
  );
}

function PainelDaChamada({ vaga, candidata, resultado, fuso, aoFechar }: {
  vaga: Vaga;
  candidata: Candidata;
  resultado: Resultado;
  fuso: string;
  aoFechar: () => void;
}) {
  const registrar = useRegistrarChamada();
  const [horario, definirHorario] = useState<string | null>(null);
  const [motivo, definirMotivo] = useState('');

  const precisaHorario = resultado === 'ACEITOU';
  const motivoValido = !candidata.exigeMotivo || motivo.trim().length >= 3;
  const pronto = (!precisaHorario || horario != null) && motivoValido;
  const idMotivo = `fil-motivo-${vaga.id}-${candidata.esperaId}`;

  return (
    <form className="fil-painel" onSubmit={(e) => {
      e.preventDefault();
      if (!pronto || registrar.isPending) return;
      registrar.mutate({
        vagaId: vaga.id,
        esperaId: candidata.esperaId,
        resultado,
        inicio: precisaHorario ? horario : null,
        motivoDoPulo: candidata.exigeMotivo ? motivo.trim() : null,
      }, { onSuccess: aoFechar });
    }}>
      {precisaHorario && (
        <fieldset className="fil-horarios">
          <legend>Qual horário?</legend>
          <div className="fil-horarios-opcoes">
            {candidata.horarios.map((inicio) => (
              <label key={inicio} className="fil-horario">
                <input type="radio" name={`fil-horario-${vaga.id}-${candidata.esperaId}`}
                  value={inicio} checked={horario === inicio}
                  onChange={() => definirHorario(inicio)} />
                <span>{hora(inicio, fuso)}</span>
              </label>
            ))}
          </div>
        </fieldset>
      )}

      {candidata.exigeMotivo && (
        <div className="fil-motivo">
          <label htmlFor={idMotivo}>Por que chamar fora da ordem?</label>
          <textarea id={idMotivo} rows={2} maxLength={300} value={motivo}
            autoFocus={!precisaHorario}
            placeholder="Ex.: a primeira só pode de manhã"
            onChange={(e) => definirMotivo(e.target.value)} />
          <p className="fil-ajuda">
            Há alguém antes na ordem que ainda não foi chamada nesta vaga. O motivo fica registrado.
          </p>
        </div>
      )}

      {registrar.isError && <p className="erro" role="alert">{mensagem(registrar.error)}</p>}

      <div className="fil-painel-botoes">
        <button className="pagina-acao principal" type="submit"
          disabled={!pronto || registrar.isPending}>
          {registrar.isPending ? 'Registrando…' : confirmacao(resultado, horario, fuso)}
        </button>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------------ quem está esperando

function NaFila({ fuso }: { fuso: string }) {
  const esperas = useFilaDeEspera();
  const [pondo, definirPondo] = useState(false);
  const total = esperas.data?.length ?? 0;

  return (
    <Secao
      id="fil-esperas"
      titulo="Na fila"
      ajuda={esperas.data
        ? total === 0 ? 'Ninguém esperando.'
          : total === 1 ? '1 pessoa esperando.' : `${total} pessoas esperando.`
        : 'Quem espera uma vaga, do mais antigo para o mais novo.'}
      acoes={!pondo && (
        <button className="pagina-acao discreta" type="button" onClick={() => definirPondo(true)}>
          Pôr na fila
        </button>
      )}
    >

      {pondo && <FormularioDeEspera aoFechar={() => definirPondo(false)} />}

      {esperas.isPending ? <Esqueleto linhas={3} altura={36} />
        : esperas.isError ? <Falha aoTentar={() => esperas.refetch()}>{mensagem(esperas.error)}</Falha>
          : total > 0 && (
            <div className="fil-rolagem">
              {/* No toque a linha vira cartão (RT-INF-018); os papéis explícitos mantêm a
                  tabela para o leitor de tela depois da troca de `display`. */}
              <table className="fil-tabela tabela-cartoes" role="table">
                <thead role="rowgroup">
                  <tr role="row">
                    <th role="columnheader">Cliente</th>
                    <th role="columnheader">Serviço</th>
                    <th role="columnheader">Com quem</th>
                    <th role="columnheader">Dias</th>
                    <th role="columnheader">Horário</th>
                    <th role="columnheader">Desde</th>
                    <th role="columnheader"><span className="fil-invisivel">Saída da fila</span></th>
                  </tr>
                </thead>
                <tbody role="rowgroup">
                  {esperas.data.map((espera) => (
                    <LinhaDaEspera key={espera.id} espera={espera} fuso={fuso} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
    </Secao>
  );
}

function LinhaDaEspera({ espera, fuso }: { espera: Espera; fuso: string }) {
  const sair = useSairDaFila();
  const [confirmando, definirConfirmando] = useState<'ATENDIDA' | 'DESISTIU' | null>(null);

  return (
    <tr role="row">
      <td role="cell">
        {espera.cliente ?? 'Cliente'}
        {espera.recorrenciaId && <span className="fil-nota"> · da série</span>}
        {espera.observacao && <span className="fil-observacao">{espera.observacao}</span>}
      </td>
      <td role="cell" data-rotulo="Serviço">{espera.servico ?? '—'}</td>
      <td role="cell" data-rotulo="Com quem">{espera.profissional ?? 'Qualquer uma'}</td>
      <td role="cell" data-rotulo="Dias" className="fil-numero">{intervalo(espera.aceitaDe, espera.aceitaAte)}</td>
      <td role="cell" data-rotulo="Horário" className="fil-numero">{faixa(espera.horaDe, espera.horaAte) ?? 'Qualquer hora'}</td>
      <td role="cell" data-rotulo="Desde" className="fil-numero">{diaCurto(espera.naFilaDesde, fuso)}</td>
      <td role="cell" className="fil-saida tabela-cartoes-largo">
        {confirmando == null ? (
          <>
            <button type="button" className="acao-discreta"
              onClick={() => { sair.reset(); definirConfirmando('ATENDIDA'); }}>
              Conseguiu horário
            </button>
            <button type="button" className="acao-discreta"
              onClick={() => { sair.reset(); definirConfirmando('DESISTIU'); }}>
              Desistiu
            </button>
          </>
        ) : (
          <>
            <button type="button" className="fil-botao" disabled={sair.isPending}
              onClick={() => sair.mutate({ id: espera.id, como: confirmando })}>
              {confirmando === 'ATENDIDA' ? 'Confirmar: conseguiu' : 'Confirmar desistência'}
            </button>
            <button type="button" className="acao-discreta" onClick={() => definirConfirmando(null)}>
              Voltar
            </button>
          </>
        )}
        {sair.isError && <p className="erro" role="alert">{mensagem(sair.error)}</p>}
      </td>
    </tr>
  );
}

function FormularioDeEspera({ aoFechar }: { aoFechar: () => void }) {
  const entrar = useEntrarNaFila();
  const servicos = useServicos();
  const profissionais = useProfissionais();
  const [termo, definirTermo] = useState('');
  const busca = useBusca(termo);
  const [cliente, definirCliente] = useState<Cliente | null>(null);
  const [servicoId, definirServicoId] = useState('');
  const [profissionalId, definirProfissionalId] = useState('');
  const [aceitaDe, definirAceitaDe] = useState('');
  const [aceitaAte, definirAceitaAte] = useState('');
  const [horaDe, definirHoraDe] = useState('');
  const [horaAte, definirHoraAte] = useState('');
  const [observacao, definirObservacao] = useState('');

  const pronto = cliente != null && servicoId !== '' && aceitaDe !== '' && aceitaAte !== '';

  return (
    <form className="fil-formulario" onSubmit={(e) => {
      e.preventDefault();
      if (!pronto || entrar.isPending) return;
      entrar.mutate({
        clienteId: cliente.id,
        servicoId,
        profissionalId: profissionalId || null,
        aceitaDe,
        aceitaAte,
        horaDe: horaDe || null,
        horaAte: horaAte || null,
        observacao: observacao.trim() || null,
      }, { onSuccess: aoFechar });
    }}>
      <div className="fil-formulario-grade">
        <div className="fil-campo-cliente">
          {cliente ? (
            <div className="campo">
              <span>Cliente</span>
              <p className="fil-escolhida">
                {comoChamar(cliente)}
                {cliente.telefone && (
                  <span className="fil-nota"> · {formatarTelefone(cliente.telefone)}</span>
                )}
                <button type="button" className="acao-discreta"
                  onClick={() => { definirCliente(null); definirTermo(''); }}>
                  Trocar
                </button>
              </p>
            </div>
          ) : (
            <label className="campo">
              <span>Cliente</span>
              <input type="search" value={termo} placeholder="Nome ou telefone" autoFocus
                onChange={(e) => definirTermo(e.target.value)} />
              {busca.data?.aviso && <span className="campo-ajuda">{busca.data.aviso}</span>}
            </label>
          )}
          {!cliente && busca.data && busca.data.itens.length > 0 && (
            <ul className="fil-resultados" aria-label="Clientes encontrados">
              {busca.data.itens.slice(0, 6).map((encontrada) => (
                <li key={encontrada.id}>
                  <button type="button" onClick={() => definirCliente(encontrada)}>
                    {comoChamar(encontrada)}
                    {encontrada.telefone && (
                      <span className="fil-nota"> · {formatarTelefone(encontrada.telefone)}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <label className="campo">
          <span>Serviço</span>
          <select value={servicoId} onChange={(e) => definirServicoId(e.target.value)}>
            <option value="">Escolha</option>
            {servicos.data?.filter((s) => s.ativo).map((s) => (
              <option key={s.id} value={s.id}>{s.nome}</option>
            ))}
          </select>
        </label>

        <label className="campo">
          <span>Com quem</span>
          <select value={profissionalId} onChange={(e) => definirProfissionalId(e.target.value)}>
            <option value="">Qualquer uma</option>
            {profissionais.data?.map((p) => (
              <option key={p.id} value={p.id}>{p.apelido?.trim() || p.nome}</option>
            ))}
          </select>
        </label>

        <label className="campo">
          <span>Aceita de</span>
          <input type="date" value={aceitaDe} onChange={(e) => {
            definirAceitaDe(e.target.value);
            if (!aceitaAte) definirAceitaAte(e.target.value);
          }} />
        </label>

        <label className="campo">
          <span>Até</span>
          <input type="date" value={aceitaAte} min={aceitaDe || undefined}
            onChange={(e) => definirAceitaAte(e.target.value)} />
        </label>

        <fieldset className="fil-faixa">
          <legend>Horário <span className="fil-nota">(opcional)</span></legend>
          <div className="fil-faixa-campos">
            <label className="campo">
              <span>Das</span>
              <input type="time" value={horaDe} step={900}
                onChange={(e) => definirHoraDe(e.target.value)} />
            </label>
            <label className="campo">
              <span>Às</span>
              <input type="time" value={horaAte} step={900}
                onChange={(e) => definirHoraAte(e.target.value)} />
            </label>
          </div>
          <span className="campo-ajuda">Sem horário, qualquer hora do dia serve.</span>
        </fieldset>

        <label className="campo fil-campo-largo">
          <span>Observação</span>
          <input type="text" value={observacao} maxLength={500}
            onChange={(e) => definirObservacao(e.target.value)} />
        </label>
      </div>

      {entrar.isError && <p className="erro" role="alert">{mensagem(entrar.error)}</p>}

      <div className="fil-painel-botoes">
        <button className="pagina-acao principal" type="submit" disabled={!pronto || entrar.isPending}>
          {entrar.isPending ? 'Pondo na fila…' : 'Pôr na fila'}
        </button>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
      </div>
    </form>
  );
}

// ------------------------------------------------------------------ texto

const ROTULOS: Record<Resultado, string> = {
  ACEITOU: 'Aceitou',
  RECUSOU: 'Recusou',
  NAO_ATENDEU: 'Não atendeu',
};

function confirmacao(resultado: Resultado, horario: string | null, fuso: string): string {
  if (resultado === 'ACEITOU') {
    return horario ? `Marcar às ${hora(horario, fuso)}` : 'Escolha o horário';
  }
  return resultado === 'RECUSOU' ? 'Registrar recusa' : 'Registrar: não atendeu';
}

function descrever(chamada: Chamada, fuso: string): string {
  const partes = [ROTULOS[chamada.resultado], quando(chamada.registradaEm, fuso)];
  if (chamada.quem) partes.push(chamada.quem);
  const linha = partes.join(' · ');
  return chamada.motivoDoPulo ? `${linha} — fora da ordem: ${chamada.motivoDoPulo}` : linha;
}

function janela(candidata: Candidata): string {
  const dias = intervalo(candidata.aceitaDe, candidata.aceitaAte);
  const horas = faixa(candidata.horaDe, candidata.horaAte);
  return horas ? `${dias}, ${horas}` : dias;
}

/** Datas do servidor sem hora ("2026-09-24"): lidas como texto, sem fuso para escorregar. */
function intervalo(de: string, ate: string): string {
  return de === ate ? dataCurta(de) : `${dataCurta(de)} a ${dataCurta(ate)}`;
}

function dataCurta(data: string): string {
  const [, mes, dia] = data.split('-');
  return `${dia}/${mes}`;
}

function faixa(de: string | null, ate: string | null): string | null {
  return de && ate ? `${de.slice(0, 5)}–${ate.slice(0, 5)}` : null;
}

function hora(instante: string, fuso: string): string {
  return formatar(new Date(instante), fuso, { hour: '2-digit', minute: '2-digit', hour12: false });
}

function dia(instante: string, fuso: string): string {
  return formatar(new Date(instante), fuso, { weekday: 'short', day: '2-digit', month: '2-digit' });
}

function diaCurto(instante: string, fuso: string): string {
  return formatar(new Date(instante), fuso, { day: '2-digit', month: '2-digit' });
}

function quando(instante: string, fuso: string): string {
  return formatar(new Date(instante), fuso, {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

function mensagem(erro: unknown): string {
  return erro instanceof ErroDaApi ? erro.message : 'Tente de novo em instantes.';
}
