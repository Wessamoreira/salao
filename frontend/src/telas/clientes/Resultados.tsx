import type { Cliente, ResultadoDaBusca } from '../../api/clientes';
import { comoChamar } from '../../api/clientes';
import { Telefone } from './Telefone';

interface Props {
  resultado: ResultadoDaBusca | undefined;
  termo: string;
  selecionadoId: string | null;
  aoSelecionar: (cliente: Cliente) => void;
}

export function Resultados({ resultado, termo, selecionadoId, aoSelecionar }: Props) {
  if (!termo.trim()) return <ComoBuscar />;
  if (!resultado) return null;

  if (resultado.itens.length === 0) {
    return (
      <div className="cli-vazio">
        {/* O texto do que falta vem do servidor: "informe ao menos 2 letras" é regra
            de domínio, e reescrevê-la aqui criaria uma segunda versão dela. */}
        <p className="cli-vazio-titulo">
          {resultado.aviso ?? 'Ninguém encontrado com esse termo.'}
        </p>
        {!resultado.aviso && <p>Confira a grafia, ou cadastre agora — é só nome e telefone.</p>}
      </div>
    );
  }

  const porTelefone = resultado.interpretacao === 'TELEFONE';

  return (
    <ul className="cli-lista">
      {resultado.itens.map((cliente) => {
        const chamado = comoChamar(cliente);
        return (
          <li key={cliente.id}>
            <button
              type="button"
              className="cli-item"
              aria-current={cliente.id === selecionadoId}
              onClick={() => aoSelecionar(cliente)}
            >
              <div className="cli-item-nome">{chamado}</div>
              {/* O nome de documento só aparece quando difere do apelido: repetir
                  a mesma linha duas vezes é ruído numa lista que se lê de relance. */}
              {chamado !== cliente.nome && (
                <div className="cli-item-formal">{cliente.nome}</div>
              )}
              {cliente.telefone && (
                <div className="cli-item-fone">
                  <Telefone e164={cliente.telefone} destacar={porTelefone ? termo : undefined} />
                </div>
              )}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * O estado padrão da tela, não uma exceção.
 *
 * Não existe endpoint que liste todos os clientes (RT-CLI-002: sem termo, lista
 * vazia), então esta é a primeira coisa que a recepção vê. Ela ensina a única
 * regra que precisa saber.
 */
function ComoBuscar() {
  return (
    <div className="cli-vazio">
      <p className="cli-vazio-titulo">Procure quem está ligando.</p>
      <p>
        Digite o nome, o apelido, ou os últimos dígitos do telefone. Com o telefone na
        mão, os quatro últimos costumam bastar.
      </p>
    </div>
  );
}
