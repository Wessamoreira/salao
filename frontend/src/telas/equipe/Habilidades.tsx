import { useState } from 'react';
import { emHoras, useServicos } from '../../api/catalogo';
import { useHabilidades, useSalvarHabilidades, type Habilidades as Dados } from '../../api/equipe';
import { Esqueleto } from '../../layout/Pagina';
import { mensagem } from './Equipe';

/**
 * O que esta pessoa executa, e em quanto tempo.
 *
 * <h2>O padrão é aberto, e a tela respeita isso</h2>
 *
 * Executar tudo é o regime normal (RN-EQP-013): a lista de serviços só aparece depois de escolher
 * restringir. Mostrar quarenta caixas de seleção para quem executa tudo é pedir uma decisão que a
 * maioria dos salões não precisa tomar.
 *
 * <h2>Duração própria mora aqui, e não no catálogo</h2>
 *
 * "A Ana leva 20 minutos a mais na coloração" é profissional × serviço (RN-CAT-014), e a mesma
 * escrita que grava a habilidade grava a duração — mandá-las em requisições separadas faria a
 * segunda apagar a primeira em silêncio.
 */
export function Habilidades({ profissionalId }: { profissionalId: string }) {
  const servicos = useServicos(false);
  const habilidades = useHabilidades(profissionalId);
  const salvar = useSalvarHabilidades(profissionalId);
  const [rascunho, definirRascunho] = useState<Dados | null>(null);

  const atual = rascunho ?? habilidades.data;
  if (habilidades.isPending || !atual) return <Esqueleto linhas={2} altura={40} />;

  const trocar = (mudanca: Partial<Dados>) => definirRascunho({ ...atual, ...mudanca });

  return (
    <section className="eqp-habilidades" aria-label="Habilidades">
      <h3>O que executa</h3>

      <div className="eqp-regime" role="radiogroup" aria-label="Regime de habilidades">
        <label>
          <input type="radio" name={`regime-${profissionalId}`} checked={atual.executaTodos}
            onChange={() => trocar({ executaTodos: true })} />
          <span>Executa todos os serviços do cardápio</span>
        </label>
        <label>
          <input type="radio" name={`regime-${profissionalId}`} checked={!atual.executaTodos}
            onChange={() => trocar({ executaTodos: false })} />
          <span>Só alguns</span>
        </label>
      </div>

      {!atual.executaTodos && (
        <ul className="eqp-servicos">
          {servicos.data?.map((servico) => {
            const marcado = atual.servicos.includes(servico.id);
            const propria = atual.duracoes[servico.id];
            return (
              <li key={servico.id}>
                <label className="eqp-servico">
                  <input type="checkbox" checked={marcado}
                    onChange={(e) => trocar({
                      servicos: e.target.checked
                        ? [...atual.servicos, servico.id]
                        : atual.servicos.filter((id) => id !== servico.id),
                    })} />
                  <span>{servico.nome}</span>
                  <small>{emHoras(servico.duracaoTotal)} no cardápio</small>
                </label>

                {/* A duração própria só faz sentido para quem executa o serviço — e o servidor
                    recusa duração sem habilidade (RN-CAT-014). */}
                {marcado && (
                  <label className="eqp-duracao">
                    <span>leva</span>
                    <input type="number" min={1} max={600} placeholder={String(servico.duracaoTotal)}
                      aria-label={`Minutos que leva em ${servico.nome}`}
                      value={propria ?? ''}
                      onChange={(e) => {
                        const duracoes = { ...atual.duracoes };
                        if (e.target.value === '') delete duracoes[servico.id];
                        else duracoes[servico.id] = Number(e.target.value);
                        trocar({ duracoes });
                      }} />
                    <span>min</span>
                  </label>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="eqp-jornada-acoes">
        <button type="button" className="pagina-acao principal"
          disabled={rascunho === null || salvar.isPending}
          onClick={() => salvar.mutate(rascunho!, { onSuccess: () => definirRascunho(null) })}>
          {salvar.isPending ? 'Salvando…' : 'Salvar habilidades'}
        </button>
        {rascunho !== null && (
          <button type="button" className="pagina-acao discreta"
            onClick={() => definirRascunho(null)}>Descartar</button>
        )}
      </div>
    </section>
  );
}
