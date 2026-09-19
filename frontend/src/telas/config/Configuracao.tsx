import { useState } from 'react';
import { Acao, Esqueleto, Pagina } from '../../layout/Pagina';
import type { BaseDeComissao, Periodicidade } from '../../api/configuracao';
import { FUSOS, useConfiguracao, useSalvarConfiguracao } from '../../api/configuracao';
import { ErroDaApi } from '../../api/http';
import './config.css';

/**
 * RT-CFG-001 — a configuração do salão.
 *
 * <h2>Três grupos, na ordem em que o dono pensa</h2>
 *
 * Identidade, atendimento e dinheiro. Um formulário corrido de oito campos obrigaria a
 * ler tudo para achar o que se veio mudar.
 *
 * <h2>Cada campo diz a consequência, não só o nome</h2>
 *
 * Estas opções parecem preferências e decidem quanto as pessoas recebem. Trocar a base
 * da comissão muda o pagamento de todo mundo no mês seguinte, e um rótulo
 * "Base de comissão: [Bruto|Líquido]" não avisa ninguém disso. Explicação abaixo do
 * controle é barata; descobrir o efeito no dia do pagamento não é.
 */
export function Configuracao() {
  const { data: atual, isPending } = useConfiguracao();
  const salvar = useSalvarConfiguracao();
  const [alteracoes, setForm] = useState<Formulario | null>(null);
  const [salvo, setSalvo] = useState(false);

  // Antes da primeira edição, a configuração é derivada da consulta. Copiá-la por
  // efeito causava uma renderização vazia e ainda podia sobrescrever o que a pessoa
  // estava digitando durante uma revalidação em segundo plano.
  const form = alteracoes ?? (atual ? deConfiguracao(atual) : null);

  // Esqueleto com a forma do formulário, não a palavra "Carregando": o texto de uma
  // linha sendo trocado por um formulário de três seções é o pulo que se sente como
  // travada.
  if (isPending || !form) {
    return (
      <Pagina>
        <div className="cfg-tela"><Esqueleto linhas={7} altura={52} /></div>
      </Pagina>
    );
  }

  const alterar = <C extends keyof Formulario>(campo: C, valor: Formulario[C]) => {
    setForm({ ...form, [campo]: valor });
    setSalvo(false);
  };

  const podeSalvar = !salvar.isPending && Boolean(form.nome.trim());

  return (
    <Pagina
      acoes={
        <>
          {salvo && <span className="cfg-salvo" role="status">Salvo</span>}
          <Acao type="submit" form="cfg-formulario" disabled={!podeSalvar}>
            {salvar.isPending ? 'Salvando…' : 'Salvar alterações'}
          </Acao>
        </>
      }
    >
    <form
      id="cfg-formulario"
      className="cfg-tela"
      onSubmit={(e) => {
        e.preventDefault();
        salvar.mutate(
          {
            nome: form.nome,
            documento: form.documento.trim() || null,
            fusoIana: form.fuso,
            moeda: 'BRL',
            baseComissao: form.baseComissao,
            descontoAfetaComissao: form.descontoAfetaComissao,
            periodicidadeDeFechamento: form.periodicidade,
            dddPadrao: form.ddd.trim() || null,
            contatoParaClientes: form.contato.trim() || null,
          },
          { onSuccess: () => setSalvo(true) },
        );
      }}
    >
      {salvar.isError && (
        <p className="erro" role="alert">{mensagem(salvar.error)}</p>
      )}

      <section>
        <h2 className="secao-titulo">Identidade</h2>
        <label className="campo">
          <span>Nome do salão</span>
          <input value={form.nome} onChange={(e) => alterar('nome', e.target.value)} required />
          <span className="campo-ajuda">Aparece no menu e nas mensagens enviadas à cliente.</span>
        </label>

        <label className="campo">
          <span>CNPJ (opcional)</span>
          <input value={form.documento} onChange={(e) => alterar('documento', e.target.value)} />
        </label>
      </section>

      <section className="secao">
        <h2 className="secao-titulo">Atendimento</h2>

        <label className="campo">
          <span>DDD da região</span>
          <input
            value={form.ddd}
            onChange={(e) => alterar('ddd', e.target.value)}
            inputMode="numeric"
            placeholder="11"
          />
          {/* O campo que trava o cadastro quando falta. Dizer isso aqui é o que evita
              que alguém o deixe vazio e depois não entenda por que o telefone é recusado. */}
          <span className="campo-ajuda">
            Completa o telefone de quem só informa os oito ou nove dígitos — que é como quem
            atende costuma anotar. <strong>Sem ele, o cadastro recusa esses números.</strong>
          </span>
        </label>

        <label className="campo">
          <span>Fuso horário</span>
          <select value={form.fuso} onChange={(e) => alterar('fuso', e.target.value)}>
            {FUSOS.map((f) => (
              <option key={f.valor} value={f.valor}>{f.rotulo}</option>
            ))}
          </select>
          <span className="campo-ajuda">
            Define o que é “hoje” na agenda. Muda também como os horários já marcados aparecem.
          </span>
        </label>

        <label className="campo">
          <span>Como clientes falam com o salão (opcional)</span>
          <input
            value={form.contato}
            onChange={(e) => alterar('contato', e.target.value)}
            placeholder="(11) 3456-7890 ou https://…"
          />
          {/* RT-CNV-008: o campo só faz sentido sabendo onde ele aparece. Sem isso, parece
              cadastro de contato do salão — e é a saída que o aviso de IA oferece. */}
          <span className="campo-ajuda">
            Quem não é da equipe e escreve para o WhatsApp do assistente recebe, uma vez, um aviso
            de que ali atende uma inteligência artificial usada só pela equipe — e este contato.
            Telefone, ou link que comece com https://.
          </span>
        </label>
      </section>

      <section className="secao">
        <h2 className="secao-titulo">Comissão e fechamento</h2>

        <label className="campo">
          <span>A comissão incide sobre</span>
          <select
            value={form.baseComissao}
            onChange={(e) => alterar('baseComissao', e.target.value as BaseDeComissao)}
          >
            <option value="BRUTO">o valor cheio do serviço</option>
            <option value="LIQUIDO">o valor após as taxas de cartão</option>
          </select>
          <span className="campo-ajuda">
            Muda quanto cada profissional recebe. Vale para os atendimentos daqui em diante;
            fechamentos já feitos não são recalculados.
          </span>
        </label>

        <label className="campo">
          <span>Desconto dado à cliente</span>
          <select
            value={form.descontoAfetaComissao ? 'sim' : 'nao'}
            onChange={(e) => alterar('descontoAfetaComissao', e.target.value === 'sim')}
          >
            <option value="nao">sai do salão — a comissão não muda</option>
            <option value="sim">é dividido — a comissão cai junto</option>
          </select>
          <span className="campo-ajuda">
            Quando a recepção dá 10% de desconto, quem absorve: só o salão, ou salão e
            profissional.
          </span>
        </label>

        <label className="campo">
          <span>Fechamento do período</span>
          <select
            value={form.periodicidade}
            onChange={(e) => alterar('periodicidade', e.target.value as Periodicidade)}
          >
            <option value="SEMANAL">toda semana</option>
            <option value="QUINZENAL">a cada quinze dias</option>
            <option value="MENSAL">todo mês</option>
          </select>
          <span className="campo-ajuda">De quanto em quanto tempo a comissão é apurada e paga.</span>
        </label>
      </section>

    </form>
    </Pagina>
  );
}

interface Formulario {
  nome: string;
  documento: string;
  ddd: string;
  contato: string;
  fuso: string;
  baseComissao: BaseDeComissao;
  descontoAfetaComissao: boolean;
  periodicidade: Periodicidade;
}

function deConfiguracao(c: {
  nome: string; fuso: string; dddPadrao: string | null; contatoParaClientes?: string | null;
  baseComissao: BaseDeComissao; descontoAfetaComissao: boolean;
  periodicidadeDeFechamento: Periodicidade;
}): Formulario {
  return {
    nome: c.nome,
    documento: '',
    ddd: c.dddPadrao ?? '',
    contato: exibirContato(c.contatoParaClientes ?? null),
    fuso: c.fuso,
    baseComissao: c.baseComissao,
    descontoAfetaComissao: c.descontoAfetaComissao,
    periodicidade: c.periodicidadeDeFechamento,
  };
}

/**
 * O telefone chega como o banco guarda — só dígitos, com o país. No campo, volta a ser o número que
 * a pessoa reconhece; o servidor normaliza de novo ao salvar, seja qual for o formato.
 */
function exibirContato(contato: string | null): string {
  if (!contato) return '';
  const nacional = /^55(\d{10,11})$/.exec(contato)?.[1];
  if (!nacional) return contato;
  const [ddd, resto] = [nacional.slice(0, 2), nacional.slice(2)];
  const corte = resto.length - 4;
  return `(${ddd}) ${resto.slice(0, corte)}-${resto.slice(corte)}`;
}

function mensagem(erro: unknown): string {
  // O detalhe do domínio explica o caso concreto — "DDD padrão deve ter 2 dígitos: 011" —
  // e é ele que diz o que corrigir.
  return erro instanceof ErroDaApi ? erro.message : 'Não foi possível salvar. Tente de novo.';
}
