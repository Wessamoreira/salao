import { useState } from 'react';
import {
  emHoras, useAtivacaoDoServico, useServicos, type Servico,
} from '../../api/catalogo';
import { ErroDaApi } from '../../api/http';
import { Esqueleto, Falha, Pagina, Vazio } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { EditorDeServico } from './EditorDeServico';
import { Recursos } from './Recursos';
import './catalogo.css';

/**
 * RT-CAT-005 — o cardápio.
 *
 * <h2>Por que esta tela existia só no servidor</h2>
 *
 * O catálogo tem endpoint desde a Fase 1 e nunca teve tela. Na prática, cadastrar o cardápio de um
 * salão novo exigia chamada HTTP na mão — e sem serviço não há o que marcar na agenda nem o que
 * lançar na comanda. É o primeiro bloqueio de `18-o-que-falta-para-usar.md`.
 *
 * <h2>A receita é a tela</h2>
 *
 * Nome e preço são campos como em qualquer cadastro. A **sequência de blocos** é a única coisa
 * daqui que não existe em outro sistema de salão, e é o que faz a agenda saber que a profissional
 * está livre enquanto a química age. Ela ocupa o centro do editor.
 *
 * <h2>Dois números, não um</h2>
 *
 * Duração total é tempo de cadeira; minutos do profissional é tempo de trabalho. Numa coloração
 * de 1h40, são 1h00 — e é o segundo número que decide se cabe um corte no meio. Os dois vêm do
 * servidor: separar o que ocupa a profissional do que não ocupa é regra de domínio (RN-CAT-001),
 * e somá-la aqui criaria uma segunda versão dela.
 */
export function Catalogo() {
  return (
    <Pagina>
      <div className="cat-tela">
        <Servicos />
        <Recursos />
      </div>
    </Pagina>
  );
}

function Servicos() {
  const servicos = useServicos();
  // Um editor por vez: abrir o segundo fecha o primeiro, e o que foi digitado não fica órfão.
  const [editando, definirEditando] = useState<string | 'novo' | null>(null);

  return (
    <Secao
      id="cat-servicos"
      titulo="Serviços"
      ajuda="O que o salão vende, quanto tempo leva e quanto custa hoje."
      acoes={(
        <button className="pagina-acao principal" type="button"
          onClick={() => definirEditando(editando === 'novo' ? null : 'novo')}>
          {editando === 'novo' ? 'Cancelar' : 'Novo serviço'}
        </button>
      )}
    >
      {editando === 'novo' && (
        <EditorDeServico aoFechar={() => definirEditando(null)} />
      )}

      {servicos.isPending && <Esqueleto linhas={4} altura={64} />}
      {servicos.isError && (
        <Falha aoTentar={() => servicos.refetch()}>{mensagem(servicos.error)}</Falha>
      )}

      {servicos.data?.length === 0 && editando !== 'novo' && (
        <Vazio icone="cardapio" titulo="O cardápio está vazio">
          Cadastre o primeiro serviço. Sem serviço não há o que marcar na agenda nem o que lançar
          na comanda.
        </Vazio>
      )}

      {servicos.data && servicos.data.length > 0 && (
        <ul className="cat-lista">
          {servicos.data.map((servico) => (
            <li key={servico.id}>
              {editando === servico.id
                ? (
                  <EditorDeServico servico={servico} aoFechar={() => definirEditando(null)} />
                )
                : (
                  <Linha servico={servico} aoEditar={() => definirEditando(servico.id)} />
                )}
            </li>
          ))}
        </ul>
      )}
    </Secao>
  );
}

function Linha({ servico, aoEditar }: { servico: Servico; aoEditar: () => void }) {
  const ativacao = useAtivacaoDoServico();

  return (
    <article className={`cat-servico${servico.ativo ? '' : ' inativo'}`}>
      <div className="cat-servico-nome">
        <h3>{servico.nome}</h3>
        {!servico.ativo && <span className="cat-tarja">fora do cardápio</span>}
        {servico.descricao && <p>{servico.descricao}</p>}
      </div>

      <dl className="cat-numeros">
        <div>
          <dt>na cadeira</dt>
          <dd>{emHoras(servico.duracaoTotal)}</dd>
        </div>
        {/* Só aparece quando é diferente: repetir o mesmo número duas vezes ensina que os dois
            são a mesma coisa, que é exatamente o contrário do que eles são. */}
        {servico.minutosDoProfissional !== servico.duracaoTotal && (
          <div>
            <dt>de trabalho</dt>
            <dd>{emHoras(servico.minutosDoProfissional)}</dd>
          </div>
        )}
        <div>
          <dt>hoje</dt>
          <dd className="cat-preco">
            {servico.precoVigente == null
              ? <span className="cat-sem-preco">sem preço</span>
              : reais(servico.precoVigente)}
          </dd>
        </div>
      </dl>

      <div className="cat-servico-acoes">
        <button className="pagina-acao discreta" type="button" onClick={aoEditar}>Editar</button>
        <button className="pagina-acao discreta" type="button" disabled={ativacao.isPending}
          onClick={() => ativacao.mutate({ id: servico.id, ativo: !servico.ativo })}>
          {servico.ativo ? 'Tirar do cardápio' : 'Voltar ao cardápio'}
        </button>
      </div>

      {ativacao.isError && (
        <p className="erro" role="alert">{mensagem(ativacao.error)}</p>
      )}
    </article>
  );
}

/** `R$ 80,00`. O `Intl` resolve a vírgula, o cifrão e o espaço sem tabela nossa. */
export function reais(valor: number): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(valor);
}

/**
 * A recusa do servidor, com as palavras dele.
 *
 * "Este recurso é exigido por três serviços" é texto do domínio (RN-CAT-012) e diz o que fazer.
 * Trocar por uma frase genérica daqui seria apagar a única informação útil do erro.
 */
export function mensagem(erro: unknown): string {
  if (erro instanceof ErroDaApi) return erro.message;
  return 'Não foi possível falar com o servidor. Tente de novo.';
}
