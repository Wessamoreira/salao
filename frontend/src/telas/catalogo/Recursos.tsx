import { useState } from 'react';
import {
  useAtivacaoDoRecurso, useRecursos, useSalvarRecurso, type Recurso,
} from '../../api/catalogo';
import { Esqueleto, Falha } from '../../layout/Pagina';
import { Secao } from '../../layout/Secao';
import { mensagem } from './Catalogo';

/**
 * RT-CAT-003 na tela — o que o salão tem em número contável.
 *
 * <h2>Quantidade, e não "existe"</h2>
 *
 * Dois lavatórios não são a mesma coisa que um (RN-CAT-009): é o número que decide quantas
 * colorações cabem ao mesmo tempo. Um cadastro que só dissesse "tem lavatório" deixaria a agenda
 * marcar três lavagens no mesmo minuto.
 *
 * <h2>Desativar é aposentar, e pode ser recusado</h2>
 *
 * Enquanto alguma receita ativa exigir o equipamento, o servidor recusa e diz quais serviços
 * (RN-CAT-012). A tela mostra a frase dele: é a diferença entre "não pode" e "não pode; conserte
 * estes três".
 */
export function Recursos() {
  const recursos = useRecursos();
  const [editando, definirEditando] = useState<string | 'novo' | null>(null);

  return (
    <Secao
      id="cat-recursos"
      titulo="Equipamentos"
      ajuda="Lavatório, cadeira, sala — o que um serviço ocupa e a agenda precisa reservar."
      acoes={(
        <button className="pagina-acao discreta" type="button"
          onClick={() => definirEditando(editando === 'novo' ? null : 'novo')}>
          {editando === 'novo' ? 'Cancelar' : 'Novo equipamento'}
        </button>
      )}
    >
      {editando === 'novo' && <Formulario aoFechar={() => definirEditando(null)} />}

      {recursos.isPending && <Esqueleto linhas={2} altura={44} />}
      {recursos.isError && (
        <Falha aoTentar={() => recursos.refetch()}>{mensagem(recursos.error)}</Falha>
      )}

      {recursos.data?.length === 0 && editando !== 'novo' && (
        <p className="cat-nada">
          Nenhum equipamento cadastrado. Serviços que não disputam nada não precisam de um.
        </p>
      )}

      {recursos.data && recursos.data.length > 0 && (
        <ul className="cat-recursos">
          {recursos.data.map((recurso) => (
            <li key={recurso.id}>
              {editando === recurso.id
                ? <Formulario recurso={recurso} aoFechar={() => definirEditando(null)} />
                : <Linha recurso={recurso} aoEditar={() => definirEditando(recurso.id)} />}
            </li>
          ))}
        </ul>
      )}
    </Secao>
  );
}

function Linha({ recurso, aoEditar }: { recurso: Recurso; aoEditar: () => void }) {
  const ativacao = useAtivacaoDoRecurso();

  return (
    <div className={`cat-recurso${recurso.ativo ? '' : ' inativo'}`}>
      <span className="cat-recurso-nome">{recurso.nome}</span>
      <span className="cat-recurso-quantidade">
        {recurso.quantidade === 1 ? '1 unidade' : `${recurso.quantidade} unidades`}
      </span>
      {!recurso.ativo && <span className="cat-tarja">aposentado</span>}
      <button className="pagina-acao discreta" type="button" onClick={aoEditar}>Editar</button>
      <button className="pagina-acao discreta" type="button" disabled={ativacao.isPending}
        onClick={() => ativacao.mutate({ id: recurso.id, ativo: !recurso.ativo })}>
        {recurso.ativo ? 'Aposentar' : 'Reativar'}
      </button>
      {ativacao.isError && <p className="erro" role="alert">{mensagem(ativacao.error)}</p>}
    </div>
  );
}

function Formulario({ recurso, aoFechar }: { recurso?: Recurso; aoFechar: () => void }) {
  const salvar = useSalvarRecurso();
  const [nome, definirNome] = useState(recurso?.nome ?? '');
  const [quantidade, definirQuantidade] = useState(String(recurso?.quantidade ?? 1));

  const numero = Number(quantidade);
  const valido = nome.trim() !== '' && Number.isInteger(numero) && numero >= 1;

  return (
    <form
      className="cat-recurso-forma"
      aria-label={recurso ? `Editar ${recurso.nome}` : 'Novo equipamento'}
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!valido || salvar.isPending) return;
        salvar.mutate(
          { id: recurso?.id, versao: recurso?.versao, nome: nome.trim(), quantidade: numero },
          { onSuccess: aoFechar },
        );
      }}
    >
      <label className="cat-campo cat-campo-largo">
        <span>Nome</span>
        <input value={nome} autoFocus placeholder="Lavatório"
          onChange={(e) => definirNome(e.target.value)} />
      </label>
      <label className="cat-campo">
        <span>Quantas</span>
        <input type="number" min={1} max={99} value={quantidade}
          onChange={(e) => definirQuantidade(e.target.value)} />
      </label>
      <button className="pagina-acao principal" type="submit" disabled={!valido || salvar.isPending}>
        {salvar.isPending ? 'Salvando…' : 'Salvar'}
      </button>
      <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}
    </form>
  );
}
