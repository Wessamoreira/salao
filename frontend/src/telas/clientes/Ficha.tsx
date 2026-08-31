import { useState } from 'react';
import { pode, useCapacidades } from '../../api/capacidades';
import type { Cliente } from '../../api/clientes';
import { comoChamar, useAnonimizar, useDuplicados, useMesclar } from '../../api/clientes';
import { AvisoDeDuplicado } from './AvisoDeDuplicado';
import { Consentimentos } from './Consentimentos';
import { Telefone } from './Telefone';

/**
 * A ficha de quem está sendo atendida.
 *
 * Fica ao lado da busca, não por cima: a recepção precisa da busca continuar
 * disponível enquanto lê — outra cliente pode ligar no meio.
 */
export function Ficha({ cliente, aoSair }: { cliente: Cliente; aoSair: () => void }) {
  const { data: capacidades } = useCapacidades();
  const chamado = comoChamar(cliente);

  return (
    <div className="cli-ficha">
      <h2 className="cli-ficha-nome">{chamado}</h2>
      {chamado !== cliente.nome && <p className="cli-ficha-formal">{cliente.nome}</p>}

      <dl className="cli-dados">
        <dt>Telefone</dt>
        <dd><Telefone e164={cliente.telefone} /></dd>
        <dt>E-mail</dt>
        <dd>{cliente.email}</dd>
      </dl>

      <Consentimentos clienteId={cliente.id} />

      <FichasParecidas cliente={cliente} />

      {pode(capacidades, 'cliente:anonymize') && (
        <Anonimizar cliente={cliente} aoConcluir={aoSair} />
      )}
    </div>
  );
}

/**
 * Duplicados vistos a partir da ficha aberta — é aqui que a mesclagem é acionável,
 * porque a ficha aberta é a que fica.
 */
function FichasParecidas({ cliente }: { cliente: Cliente }) {
  const { data: candidatos } = useDuplicados(cliente.nome, cliente.telefone ?? '');
  const mesclar = useMesclar(cliente.id);
  const [confirmando, setConfirmando] = useState<string | null>(null);

  const outros = (candidatos ?? []).filter((d) => d.cliente.id !== cliente.id);
  if (outros.length === 0) return null;

  const alvo = outros.find((d) => d.cliente.id === confirmando);

  return (
    <section className="cli-secao">
      <h3 className="cli-secao-titulo">Fichas parecidas</h3>

      {alvo ? (
        <div className="cli-aviso cli-aviso-forte">
          {/* Confirmação explícita porque juntar não tem desfazer (pendência aberta
              da RT-CLI-003). A regra do projeto é executar e oferecer desfazer;
              onde não há desfazer, perguntar antes é o que sobra. */}
          <p>
            Juntar <strong>{comoChamar(alvo.cliente)}</strong> nesta ficha? O histórico das
            duas passa a ficar aqui, e não tem como desfazer.
          </p>
          <button
            type="button"
            className="cli-acao"
            disabled={mesclar.isPending}
            onClick={() =>
              mesclar.mutate(alvo.cliente.id, { onSuccess: () => setConfirmando(null) })
            }
          >
            {mesclar.isPending ? 'Juntando…' : 'Juntar fichas'}
          </button>{' '}
          <button type="button" className="cli-acao-discreta" onClick={() => setConfirmando(null)}>
            Cancelar
          </button>
        </div>
      ) : (
        <>
          <AvisoDeDuplicado duplicados={outros} aoEscolher={setConfirmando} />
          {/* A instrução só aparece quando juntar é mesmo uma opção. Sugeri-la para
              mãe e filha seria a tela desfazendo a distinção que o domínio faz. */}
          {outros.some((d) => d.grau !== 'MESMO_TELEFONE') && (
            <p className="cli-canal">Escolha um nome para juntar as duas fichas.</p>
          )}
        </>
      )}

      {mesclar.isError && <p className="cli-erro">Não foi possível juntar as fichas.</p>}
    </section>
  );
}

/**
 * Apagar os dados a pedido da titular (RT-CLI-005, LGPD art. 18, VI).
 *
 * Fica no fim, discreta e sem cor de alarme até ser procurada: é irreversível e
 * rara, e um botão vermelho no alto convidaria o clique errado. Esconder de quem
 * não tem `cliente:anonymize` é organização de tela, não segurança — o servidor
 * devolve 403 de qualquer forma (inviolável nº 6).
 */
function Anonimizar({ cliente, aoConcluir }: { cliente: Cliente; aoConcluir: () => void }) {
  const [confirmando, setConfirmando] = useState(false);
  const anonimizar = useAnonimizar();

  return (
    <section className="cli-secao">
      {confirmando ? (
        <div className="cli-aviso">
          <p>
            Apagar os dados de <strong>{comoChamar(cliente)}</strong>? Nome, telefone e e-mail
            somem para sempre. A ficha continua existindo sem eles, porque o histórico
            financeiro do salão depende dela.
          </p>
          <button
            type="button"
            className="cli-acao"
            disabled={anonimizar.isPending}
            onClick={() => anonimizar.mutate(cliente.id, { onSuccess: aoConcluir })}
          >
            {anonimizar.isPending ? 'Apagando…' : 'Apagar os dados'}
          </button>{' '}
          <button
            type="button"
            className="cli-acao-discreta"
            onClick={() => setConfirmando(false)}
          >
            Cancelar
          </button>
          {anonimizar.isError && (
            <p className="cli-erro">Não foi possível apagar. Tente de novo.</p>
          )}
        </div>
      ) : (
        <button
          type="button"
          className="cli-acao-discreta cli-acao-grave"
          onClick={() => setConfirmando(true)}
        >
          Apagar os dados a pedido dela
        </button>
      )}
    </section>
  );
}
