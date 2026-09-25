import { useState } from 'react';
import {
  emHoras, useRecursos, useSalvarServico, type Etapa, type Servico, type TipoDeEtapa,
} from '../../api/catalogo';
import { PrecoDoServico } from './PrecoDoServico';
import { mensagem } from './Catalogo';

const ETAPA_NOVA: Etapa = { tipo: 'ATIVO', duracaoMinutos: 30, recursosExigidos: [] };

/**
 * A receita de um serviço.
 *
 * <h2>O front não valida a receita</h2>
 *
 * "Começa e termina com trabalho" e "duas esperas não se seguem" são regras de domínio
 * (RN-CAT-002, RN-CAT-003) e vivem no servidor. Este editor **ajuda** — o botão de adicionar
 * oferece o tipo que costuma vir em seguida — mas não recusa nada por conta própria: quem recusa
 * é o servidor, e é a frase dele que aparece. Regra copiada para o JavaScript é regra que um dia
 * diz uma coisa enquanto a agenda faz outra.
 *
 * <h2>A receita inteira, sempre</h2>
 *
 * Salvar manda as etapas todas, não o que mudou. "Troque a etapa 2 para 45 minutos" exigiria que
 * os dois lados concordassem sobre qual é a etapa 2 — e duas pessoas editando ao mesmo tempo não
 * concordam. A `versao` resolve: quem chegar depois é recusado, em vez de apagar o trabalho do
 * outro (RN-CAT-015).
 */
export function EditorDeServico({ servico, aoFechar }: {
  servico?: Servico;
  aoFechar: () => void;
}) {
  const recursos = useRecursos(false);
  const salvar = useSalvarServico();
  const [nome, definirNome] = useState(servico?.nome ?? '');
  const [descricao, definirDescricao] = useState(servico?.descricao ?? '');
  const [etapas, definirEtapas] = useState<Etapa[]>(servico?.etapas ?? [ETAPA_NOVA]);

  const trocar = (indice: number, mudanca: Partial<Etapa>) => {
    definirEtapas(etapas.map((etapa, i) => (i === indice ? { ...etapa, ...mudanca } : etapa)));
  };

  const total = etapas.reduce((soma, etapa) => soma + (etapa.duracaoMinutos || 0), 0);
  const podeSalvar = nome.trim().length > 0 && etapas.length > 0
    && etapas.every((etapa) => etapa.duracaoMinutos > 0) && !salvar.isPending;

  return (
    <div className="cat-editor-caixa">
    <form
      className="cat-editor"
      aria-label={servico ? `Editar ${servico.nome}` : 'Novo serviço'}
      onSubmit={(evento) => {
        evento.preventDefault();
        if (!podeSalvar) return;
        salvar.mutate({
          id: servico?.id,
          versao: servico?.versao,
          nome: nome.trim(),
          descricao: descricao.trim() || null,
          etapas,
        }, { onSuccess: aoFechar });
      }}
    >
      <div className="cat-editor-linha">
        <label className="cat-campo cat-campo-largo">
          <span>Nome</span>
          <input value={nome} onChange={(e) => definirNome(e.target.value)}
            placeholder="Coloração" autoFocus />
        </label>
        <label className="cat-campo cat-campo-largo">
          <span>Descrição</span>
          <input value={descricao} onChange={(e) => definirDescricao(e.target.value)}
            placeholder="raiz e comprimento" />
        </label>
      </div>

      <fieldset className="cat-receita">
        <legend>Receita</legend>
        <p className="cat-receita-ajuda">
          <strong>Trabalho</strong> é a profissional com a cliente. <strong>Espera</strong> é a
          química agindo — a cliente está no salão e a profissional pode atender outra pessoa.
        </p>

        <ol className="cat-etapas">
          {etapas.map((etapa, indice) => (
            <li key={indice} className={`cat-etapa ${etapa.tipo.toLowerCase()}`}>
              <label className="cat-campo">
                <span>Etapa {indice + 1}</span>
                <select value={etapa.tipo} aria-label={`Tipo da etapa ${indice + 1}`}
                  onChange={(e) => trocar(indice, { tipo: e.target.value as TipoDeEtapa })}>
                  <option value="ATIVO">Trabalho</option>
                  <option value="PAUSA">Espera</option>
                </select>
              </label>

              <label className="cat-campo">
                <span>Minutos</span>
                <input type="number" min={1} max={600} value={etapa.duracaoMinutos}
                  aria-label={`Minutos da etapa ${indice + 1}`}
                  onChange={(e) => trocar(indice, { duracaoMinutos: Number(e.target.value) })} />
              </label>

              <fieldset className="cat-recursos-da-etapa">
                {/* RN-CAT-010: o recurso é exigido pelo BLOCO. A coloração ocupa o lavatório só
                    na lavagem; exigi-lo no serviço inteiro reservaria 100 minutos de um
                    equipamento usado por 30. */}
                <legend>Equipamento</legend>
                {recursos.data?.length === 0 && <span className="cat-sem-recurso">nenhum</span>}
                {recursos.data?.map((recurso) => (
                  <label key={recurso.id} className="cat-marcador">
                    <input
                      type="checkbox"
                      checked={etapa.recursosExigidos.includes(recurso.id)}
                      onChange={(e) => trocar(indice, {
                        recursosExigidos: e.target.checked
                          ? [...etapa.recursosExigidos, recurso.id]
                          : etapa.recursosExigidos.filter((id) => id !== recurso.id),
                      })}
                    />
                    <span>{recurso.nome}</span>
                  </label>
                ))}
              </fieldset>

              <button className="pagina-acao discreta" type="button"
                aria-label={`Remover a etapa ${indice + 1}`}
                disabled={etapas.length === 1}
                onClick={() => definirEtapas(etapas.filter((_, i) => i !== indice))}>
                Remover
              </button>
            </li>
          ))}
        </ol>

        <div className="cat-receita-rodape">
          <button className="pagina-acao discreta" type="button"
            onClick={() => definirEtapas([...etapas, { ...ETAPA_NOVA, tipo: proximoTipo(etapas) }])}>
            Adicionar etapa
          </button>
          <span className="cat-receita-total">
            {emHoras(total)} na cadeira
          </span>
        </div>
      </fieldset>

      {servico && (
        <p className="cat-aviso" role="note">
          O que já está marcado não muda: cada agendamento guarda a receita com que foi criado.
          A nova vale para o que for marcado daqui em diante.
        </p>
      )}

      {salvar.isError && <p className="erro" role="alert">{mensagem(salvar.error)}</p>}

      <div className="cat-editor-acoes">
        <button className="pagina-acao principal" type="submit" disabled={!podeSalvar}>
          {salvar.isPending ? 'Salvando…' : servico ? 'Salvar alterações' : 'Cadastrar serviço'}
        </button>
        <button className="pagina-acao discreta" type="button" onClick={aoFechar}>Cancelar</button>
      </div>

    </form>

    {/* Fora do <form>: preço é outra gravação, com outro botão, e formulário dentro de
        formulário não é HTML válido — o navegador desmancha o de dentro. */}
    {servico && <PrecoDoServico servico={servico} />}
    </div>
  );
}

/**
 * O tipo que costuma vir depois — ajuda, não regra.
 *
 * Depois de trabalho vem espera; depois de espera vem trabalho. É o que acontece na coloração,
 * que é o serviço que justifica o modelo inteiro. Quem quiser outra coisa troca no seletor, e
 * quem recusa sequência impossível é o servidor.
 */
function proximoTipo(etapas: Etapa[]): TipoDeEtapa {
  return etapas.at(-1)?.tipo === 'ATIVO' ? 'PAUSA' : 'ATIVO';
}
