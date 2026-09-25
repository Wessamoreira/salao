import { useState } from 'react';
import {
  DIAS, useJornada, useSalvarJornada, type Intervalo, type Jornada as JornadaSemanal,
} from '../../api/equipe';
import { Esqueleto } from '../../layout/Pagina';
import { mensagem } from './Equipe';

const PADRAO: Intervalo = { inicio: '09:00', fim: '18:00' };

/**
 * A semana de trabalho — sete dias, cada um com os intervalos que tiver.
 *
 * <h2>Almoço não é campo</h2>
 *
 * Quem trabalha 9–13 e 14–18 tem dois intervalos, e o buraco entre eles é o almoço (RN-EQP-006).
 * Um campo "almoço: de ___ até ___" seria uma terceira representação da mesma coisa — e a
 * primeira a divergir do que a agenda desenha, porque a agenda lê os intervalos.
 *
 * <h2>Repetir o dia é o atalho que faz a tela valer</h2>
 *
 * Jornada de salão é quase sempre a mesma de segunda a sábado. Preencher seis dias iguais na mão
 * é o tipo de trabalho que faz alguém desistir da tela e voltar para a planilha. O botão copia o
 * dia inteiro — inclusive o intervalo do almoço, que é o que mais cansa digitar.
 */
export function Jornada({ profissionalId }: { profissionalId: string }) {
  const jornada = useJornada(profissionalId);
  const salvar = useSalvarJornada(profissionalId);
  const [rascunho, definirRascunho] = useState<JornadaSemanal | null>(null);

  const atual = rascunho ?? jornada.data;
  if (jornada.isPending || !atual) return <Esqueleto linhas={4} altura={40} />;

  const trocar = (mudanca: JornadaSemanal) => definirRascunho({ ...atual, ...mudanca });

  const trocarIntervalo = (dia: string, indice: number, campo: 'inicio' | 'fim', valor: string) => {
    const lista = [...(atual[dia] ?? [])];
    lista[indice] = { ...lista[indice], [campo]: valor };
    trocar({ [dia]: lista });
  };

  return (
    <section className="eqp-jornada" aria-label="Jornada semanal">
      <h3>Jornada</h3>
      <p className="eqp-ajuda">
        Dois intervalos no mesmo dia são manhã e tarde — o buraco entre eles é o almoço.
        Dia sem intervalo é dia de folga.
      </p>

      <ul className="eqp-dias">
        {DIAS.map((dia) => {
          const intervalos = atual[dia.iso] ?? [];
          return (
            <li key={dia.iso} className={intervalos.length === 0 ? 'folga' : undefined}>
              <span className="eqp-dia-nome">{dia.curto}</span>

              <div className="eqp-dia-faixas">
                {intervalos.length === 0 && <span className="eqp-folga">folga</span>}
                {intervalos.map((intervalo, indice) => (
                  <span key={indice} className="eqp-faixa">
                    <input type="time" value={intervalo.inicio.slice(0, 5)}
                      aria-label={`Início do ${indice + 1}º intervalo de ${dia.longo}`}
                      onChange={(e) => trocarIntervalo(dia.iso, indice, 'inicio', e.target.value)} />
                    <span aria-hidden="true">–</span>
                    <input type="time" value={intervalo.fim.slice(0, 5)}
                      aria-label={`Fim do ${indice + 1}º intervalo de ${dia.longo}`}
                      onChange={(e) => trocarIntervalo(dia.iso, indice, 'fim', e.target.value)} />
                    <button type="button" className="eqp-tirar"
                      aria-label={`Tirar o ${indice + 1}º intervalo de ${dia.longo}`}
                      onClick={() => trocar({
                        [dia.iso]: intervalos.filter((_, i) => i !== indice),
                      })}>×</button>
                  </span>
                ))}
              </div>

              <div className="eqp-dia-acoes">
                <button type="button" className="pagina-acao discreta"
                  aria-label={`Adicionar intervalo em ${dia.longo}`}
                  onClick={() => trocar({ [dia.iso]: [...intervalos, PADRAO] })}>
                  + intervalo
                </button>
                {intervalos.length > 0 && (
                  <button type="button" className="pagina-acao discreta"
                    aria-label={`Repetir ${dia.longo} nos outros dias`}
                    onClick={() => definirRascunho(repetir(atual, dia.iso))}>
                    repetir
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="eqp-jornada-acoes">
        <button type="button" className="pagina-acao principal"
          disabled={rascunho === null || salvar.isPending}
          onClick={() => salvar.mutate(rascunho!, { onSuccess: () => definirRascunho(null) })}>
          {salvar.isPending ? 'Salvando…' : 'Salvar jornada'}
        </button>
        {rascunho !== null && (
          <button type="button" className="pagina-acao discreta"
            onClick={() => definirRascunho(null)}>Descartar</button>
        )}
      </div>
    </section>
  );
}

/**
 * Copia um dia para segunda a sábado — domingo fica de fora.
 *
 * Salão que abre domingo é exceção, e quem abre marca o dia na mão. Incluí-lo na cópia faria o
 * caso comum precisar de uma correção depois de cada clique, que é o contrário de atalho.
 */
function repetir(jornada: JornadaSemanal, origem: string): JornadaSemanal {
  const intervalos = jornada[origem] ?? [];
  const copia = { ...jornada };
  for (const dia of DIAS) {
    if (dia.iso !== '7') copia[dia.iso] = intervalos.map((i) => ({ ...i }));
  }
  return copia;
}
