import { useState } from 'react';
import type { Cliente } from '../../api/clientes';
import { formatarTelefone, useAtualizar } from '../../api/clientes';
import { ErroDaApi } from '../../api/http';

/**
 * RT-CLI-007 — corrigir a ficha.
 *
 * O formulário nasce com o telefone FORMATADO, não em E.164. Mostrar
 * `5511987654321` para quem vai corrigir um dígito obrigaria a decifrar o número
 * antes de editá-lo — e o domínio aceita máscara na entrada de qualquer jeito
 * (RN-CLI-001).
 */
export function EditarFicha({
  cliente,
  aoSalvar,
  aoCancelar,
}: {
  cliente: Cliente;
  aoSalvar: () => void;
  aoCancelar: () => void;
}) {
  const [nome, setNome] = useState(cliente.nome);
  const [apelido, setApelido] = useState(cliente.apelido ?? '');
  const [telefone, setTelefone] = useState(formatarTelefone(cliente.telefone));
  const [email, setEmail] = useState(cliente.email ?? '');
  const atualizar = useAtualizar(cliente.id);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        atualizar.mutate(
          {
            nome,
            apelido: apelido.trim() || null,
            telefone: telefone.trim() || null,
            email: email.trim() || null,
            versao: cliente.versao,
          },
          { onSuccess: () => aoSalvar() },
        );
      }}
    >
      <h3 className="secao-titulo">Corrigir ficha</h3>

      <label className="campo">
        <span>Nome</span>
        <input value={nome} onChange={(e) => setNome(e.target.value)} autoFocus required />
      </label>

      <label className="campo">
        <span>Como é chamada (opcional)</span>
        <input value={apelido} onChange={(e) => setApelido(e.target.value)} />
      </label>

      <label className="campo">
        <span>Telefone (opcional)</span>
        <input value={telefone} onChange={(e) => setTelefone(e.target.value)} inputMode="tel" />
      </label>

      <label className="campo">
        <span>E-mail (opcional)</span>
        <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" />
      </label>

      <button type="submit" className="acao" disabled={atualizar.isPending || !nome.trim()}>
        {atualizar.isPending ? 'Salvando…' : 'Salvar'}
      </button>{' '}
      <button type="button" className="acao-discreta" onClick={aoCancelar}>
        Cancelar
      </button>

      {atualizar.isError && <p className="erro">{mensagem(atualizar.error)}</p>}
    </form>
  );
}

function mensagem(erro: unknown): string {
  if (!(erro instanceof ErroDaApi)) return 'Não foi possível salvar. Tente de novo.';
  // O conflito de versão precisa dizer o que fazer, não só o que houve: quem está com a
  // ficha aberta não sabe que outra pessoa salvou, e "conflito" sozinho não orienta.
  if (erro.codigo === 'ER-INF-VERSAO_DESATUALIZADA') {
    return 'Outra pessoa salvou esta ficha enquanto você editava. Feche e abra de novo para '
      + 'ver o que mudou antes de corrigir.';
  }
  return erro.message;
}
