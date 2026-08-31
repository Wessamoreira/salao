import { useState } from 'react';
import type { Canal, Consentimento, Finalidade } from '../../api/clientes';
import { useConsentimentos, useRegistrarConsentimento } from '../../api/clientes';

const ROTULO: Record<Finalidade, string> = {
  LEMBRETE_DE_AGENDAMENTO: 'Lembrete de horário',
  MARKETING: 'Promoções e aniversário',
  PESQUISA_DE_SATISFACAO: 'Pesquisa de satisfação',
};

/**
 * Só os canais que alguém escolhe no balcão.
 *
 * `SITE` e `IMPORTACAO` existem no domínio e ficam fora daqui de propósito: o
 * primeiro é autoatendimento, que ainda não existe, e o segundo descreve dado
 * vindo de outro sistema. Oferecer "importação" numa tela de atendimento
 * convidaria alguém a registrar como importado o que foi perguntado na hora.
 */
const CANAIS: { valor: Canal; rotulo: string }[] = [
  { valor: 'BALCAO', rotulo: 'no balcão' },
  { valor: 'WHATSAPP', rotulo: 'por WhatsApp' },
  { valor: 'TELEFONE', rotulo: 'por telefone' },
  { valor: 'FICHA_PAPEL', rotulo: 'em ficha assinada' },
];

export function Consentimentos({ clienteId }: { clienteId: string }) {
  const { data: consentimentos } = useConsentimentos(clienteId);
  const registrar = useRegistrarConsentimento(clienteId);
  const [canal, setCanal] = useState<Canal>('BALCAO');

  if (!consentimentos) return null;

  return (
    <section className="secao">
      <h3 className="secao-titulo">Contato</h3>

      {/*
        O canal vem ANTES das decisões, não depois.
        Ele é a prova de onde a resposta veio, e precisa estar escolhido no momento
        do clique — embaixo da lista, era possível registrar as três e só então
        reparar que o canal estava errado, num registro que não se corrige (a tabela
        é append-only).

        Uma vez por seção, não por finalidade: quem pergunta as três de uma vez
        pergunta pelo mesmo canal.
      */}
      <p className="cli-canal cli-canal-topo">
        Perguntado{' '}
        <select
          aria-label="Canal do registro"
          value={canal}
          onChange={(e) => setCanal(e.target.value as Canal)}
        >
          {CANAIS.map((c) => (
            <option key={c.valor} value={c.valor}>{c.rotulo}</option>
          ))}
        </select>
      </p>

      {consentimentos.map((c) => (
        <Finalidade
          key={c.finalidade}
          consentimento={c}
          ocupado={registrar.isPending}
          aoDecidir={(concedido) =>
            registrar.mutate({ finalidade: c.finalidade, concedido, canal })
          }
        />
      ))}

      {registrar.isError && (
        <p className="erro">Não foi possível registrar. Tente de novo.</p>
      )}
    </section>
  );
}

/**
 * Três estados, e por isso não é um interruptor.
 *
 * "Sem registro" não é "não pode" (RN-CLI-018): para o lembrete de horário, sem
 * registro significa PODE, porque a base legal é execução de contrato. Um
 * interruptor tem dois estados e mentiria sobre o terceiro — mostraria "desligado"
 * para uma cliente que o salão pode e deve avisar.
 *
 * Por isso a tela separa o EFEITO (pode ou não receber, hoje) do REGISTRO (o que
 * a pessoa disse, se disse alguma coisa).
 */
function Finalidade({
  consentimento,
  ocupado,
  aoDecidir,
}: {
  consentimento: Consentimento;
  ocupado: boolean;
  aoDecidir: (concedido: boolean) => void;
}) {
  const { decisao, podeReceber, baseLegal, desde } = consentimento;

  return (
    <div className="cli-consentimento">
      <div className="cli-consentimento-cabeca">
        <span className="cli-consentimento-nome">{ROTULO[consentimento.finalidade]}</span>
        <span className="cli-consentimento-estado">
          {podeReceber ? 'pode receber' : 'não recebe'}
        </span>
      </div>

      <div className="cli-consentimento-base">
        {decisao === 'SEM_REGISTRO'
          ? baseLegal === 'EXECUCAO_DE_CONTRATO'
            ? 'Sem registro — faz parte do serviço contratado'
            : 'Sem registro — precisa do aceite dela'
          : `${decisao === 'CONCEDIDO' ? 'Aceitou' : 'Recusou'} em ${data(desde)}`}
      </div>

      <div className="cli-escolha">
        <button
          type="button"
          aria-pressed={decisao === 'CONCEDIDO'}
          disabled={ocupado}
          onClick={() => aoDecidir(true)}
        >
          Aceitou
        </button>
        {/* Revogar tem exatamente o mesmo peso visual que aceitar. A lei exige que
            seja igualmente fácil (art. 8 §5), e um botão menor ou escondido seria
            a letra cumprida e o espírito não. */}
        <button
          type="button"
          aria-pressed={decisao === 'RECUSADO'}
          disabled={ocupado}
          onClick={() => aoDecidir(false)}
        >
          Recusou
        </button>
      </div>
    </div>
  );
}

function data(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleDateString('pt-BR');
}
