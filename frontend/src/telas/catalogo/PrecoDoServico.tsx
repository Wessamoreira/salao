import { useState } from 'react';
import {
  useDefinirPreco, usePrecos, useRemoverPrecoFuturo, type Servico,
} from '../../api/catalogo';
import { useCapacidades } from '../../api/capacidades';
import { diaCivil, formatar, fusoDoNavegador } from '../../layout/tempo';
import { mensagem, reais } from './Catalogo';

/**
 * RT-CAT-002 na tela — o preço e a data em que passa a valer.
 *
 * <h2>Por que a data é campo, e não detalhe</h2>
 *
 * Preço tem início e nunca tem fim (RN-CAT-005): o novo vale até outro começar. Isso faz "a
 * partir de quando" ser metade da informação — e é o que permite anunciar o aumento antes de
 * aplicá-lo, que é o uso normal de um salão.
 *
 * <h2>O histórico fica à vista</h2>
 *
 * É a prova de quanto custava em cada época, e é o que responde "por que esta comanda de março
 * está com outro valor". Sem ele, a resposta seria a memória de alguém.
 */
export function PrecoDoServico({ servico }: { servico: Servico }) {
  const { data: capacidades } = useCapacidades();
  const precos = usePrecos(servico.id);
  const definir = useDefinirPreco(servico.id);
  const remover = useRemoverPrecoFuturo(servico.id);
  const [valor, definirValor] = useState('');
  const [vigenteDe, definirVigenteDe] = useState('');

  const fuso = capacidades?.estabelecimento?.timezone ?? fusoDoNavegador();
  const hoje = diaCivil(new Date(), fuso);
  const numero = Number(valor.replace(/\./g, '').replace(',', '.'));
  const valido = valor.trim() !== '' && Number.isFinite(numero) && numero > 0;
  const retroativo = vigenteDe !== '' && vigenteDe < hoje;

  return (
    <section className="cat-preco-secao" aria-label={`Preço de ${servico.nome}`}>
      {/* h3, e não h4: dentro do editor não existe o h3 do nome do serviço (a linha da
          lista deu lugar ao formulário), e pular de h2 para h4 quebra a ordem dos
          títulos — quem navega por cabeçalho perde o degrau. */}
      <h3>Preço</h3>

      <form
        className="cat-preco-forma"
        onSubmit={(evento) => {
          evento.preventDefault();
          if (!valido || definir.isPending) return;
          definir.mutate(
            { valor: numero, vigenteDe: vigenteDe || null },
            { onSuccess: () => { definirValor(''); definirVigenteDe(''); } },
          );
        }}
      >
        <label className="cat-campo">
          <span>Valor</span>
          <input inputMode="decimal" value={valor} placeholder="80,00"
            onChange={(e) => definirValor(e.target.value)} />
        </label>
        <label className="cat-campo">
          <span>A partir de</span>
          {/* Vazio quer dizer hoje, e o servidor é quem sabe que dia é hoje no salão: preencher
              com a data desta máquina erraria por três horas em quem administra de longe. */}
          <input type="date" value={vigenteDe} placeholder="hoje"
            onChange={(e) => definirVigenteDe(e.target.value)} />
        </label>
        <button className="pagina-acao principal" type="submit"
          disabled={!valido || definir.isPending}>
          {definir.isPending ? 'Salvando…' : 'Definir preço'}
        </button>
      </form>

      {retroativo && (
        <p className="cat-aviso" role="status">
          Esta data já passou: o preço passa a valer para trás. Comandas já fechadas não mudam —
          cada uma guarda o preço que usou.
        </p>
      )}
      {definir.isError && <p className="erro" role="alert">{mensagem(definir.error)}</p>}
      {remover.isError && <p className="erro" role="alert">{mensagem(remover.error)}</p>}

      {precos.data && precos.data.length > 0 && (
        <ul className="cat-precos">
          {precos.data.map((preco) => {
            const futuro = preco.vigenteDe > hoje;
            return (
              <li key={preco.id} className={futuro ? 'futuro' : undefined}>
                <strong>{reais(preco.valor)}</strong>
                <span>
                  {futuro ? 'a partir de ' : 'desde '}
                  {formatar(new Date(`${preco.vigenteDe}T12:00:00Z`), 'UTC',
                    { day: '2-digit', month: '2-digit', year: 'numeric' })}
                </span>
                {/* Só o que ainda não começou pode ser desmarcado: o preço vigente não se apaga,
                    porque comandas foram fechadas com ele (RN-CAT-008). */}
                {futuro && (
                  <button className="pagina-acao discreta" type="button"
                    disabled={remover.isPending}
                    onClick={() => remover.mutate(preco.id)}>
                    Cancelar
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {precos.data?.length === 0 && (
        <p className="cat-sem-preco">
          Sem preço. A comanda recusa vender um serviço sem preço — e recusa com razão.
        </p>
      )}
    </section>
  );
}
