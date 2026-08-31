import { useState } from 'react';
import { useCadastrar, useDuplicados } from '../../api/clientes';
import { ErroDaApi } from '../../api/http';
import { AvisoDeDuplicado } from './AvisoDeDuplicado';

/**
 * O cadastro rápido de dois campos (R-UX-04).
 *
 * Nasce preenchido com o que a recepção já digitou na busca. Ela procurou "Maria",
 * não achou, e vai cadastrar Maria — pedir que digite de novo é a fricção que faz o
 * sistema virar caderno.
 *
 * Qual campo recebe o termo vem da interpretação do SERVIDOR, não de um palpite
 * daqui: é a mesma regra que decidiu como buscar (RN-CLI-009).
 */
export function CadastroRapido({
  termoInicial,
  ehTelefone,
  aoCadastrar,
}: {
  termoInicial: string;
  ehTelefone: boolean;
  aoCadastrar: () => void;
}) {
  const [nome, setNome] = useState(ehTelefone ? '' : termoInicial);
  const [telefone, setTelefone] = useState(ehTelefone ? termoInicial : '');
  const cadastrar = useCadastrar();

  // Enquanto se digita, e não ao enviar: descobrir o duplicado depois de criar o
  // duplicado não ajuda ninguém (R-UX-15).
  const { data: duplicados } = useDuplicados(nome, telefone);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        cadastrar.mutate(
          { nome, telefone: telefone.trim() || undefined },
          { onSuccess: aoCadastrar },
        );
      }}
    >
      <h3 className="cli-secao-titulo">Cadastrar</h3>

      <label className="cli-campo">
        <span>Nome</span>
        <input
          value={nome}
          onChange={(e) => setNome(e.target.value)}
          autoFocus={!ehTelefone}
          required
        />
      </label>

      <label className="cli-campo">
        {/* O rótulo diz que é opcional. Cliente que passa e pergunta o preço, criança
            que vem com a mãe, a senhora sem celular: existem e precisam de ficha. */}
        <span>Telefone (opcional)</span>
        <input
          value={telefone}
          onChange={(e) => setTelefone(e.target.value)}
          autoFocus={ehTelefone}
          inputMode="tel"
          placeholder="(11) 98765-4321"
        />
      </label>

      {duplicados && <AvisoDeDuplicado duplicados={duplicados} />}

      <button type="submit" className="cli-acao" disabled={cadastrar.isPending || !nome.trim()}>
        {cadastrar.isPending ? 'Cadastrando…' : 'Cadastrar'}
      </button>

      {cadastrar.isError && (
        <p className="cli-erro">
          {mensagem(cadastrar.error)}
        </p>
      )}
    </form>
  );
}

function mensagem(erro: unknown): string {
  // O detalhe do servidor explica o caso concreto — "número com 4 dígitos não é um
  // telefone válido" —, e é ele que ajuda quem digita com a cliente na frente. Uma
  // mensagem genérica mandaria tentar de novo às cegas.
  return erro instanceof ErroDaApi ? erro.message : 'Não foi possível cadastrar. Tente de novo.';
}
