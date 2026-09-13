import { Esqueleto, Vazio } from '../../layout/Pagina';
import type { Cliente, ResultadoDaBusca } from '../../api/clientes';
import { comoChamar } from '../../api/clientes';
import { Telefone } from './Telefone';

interface Props {
  resultado: ResultadoDaBusca | undefined;
  termo: string;
  carregando: boolean;
  selecionadoId: string | null;
  aoSelecionar: (cliente: Cliente) => void;
}

export function Resultados({ resultado, termo, carregando, selecionadoId,
                             aoSelecionar }: Props) {
  if (!termo.trim()) return <ComoBuscar />;

  /*
   * Esqueleto na PRIMEIRA busca, e nada nas seguintes.
   *
   * `useBusca` mantém o resultado anterior enquanto o novo chega, então entre teclas a
   * lista não pisca — e trocá-la por um esqueleto a cada letra seria piorar o que já
   * está bom. Mas na primeira busca não há anterior: aqui ficava o vazio absoluto, e é
   * ele que se sente como travada.
   */
  if (!resultado) {
    return carregando ? <Esqueleto linhas={4} altura={58} /> : null;
  }

  if (resultado.itens.length === 0) {
    return (
      <Vazio
        icone="busca"
        titulo={resultado.aviso ?? 'Ninguém com esse nome'}
      >
        {/* O texto do que falta vem do servidor: "informe ao menos 2 letras" é regra
            de domínio, e reescrevê-la aqui criaria uma segunda versão dela. */}
        {resultado.aviso
          ? 'Complete o termo para a busca valer.'
          : 'Confira a grafia — ou cadastre agora, ao lado: é só nome e telefone.'}
      </Vazio>
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
 * <h2>Dica de campo não é estado vazio</h2>
 *
 * A primeira versão desta revisão trocou isto pelo `Vazio` compartilhado — centralizado,
 * com ícone em círculo. Ficou pior, e a razão é útil: `Vazio` responde "não há nada
 * aqui", e pertence ao meio da área de trabalho. Isto responde "digite aqui", e pertence
 * DEBAIXO DO CAMPO, alinhado com ele. Centralizado, o texto se soltava do campo que
 * ele explica e passava a parecer o resultado de uma busca que ninguém fez.
 *
 * Consistência é usar a mesma peça para a mesma coisa — não a mesma peça para coisas
 * diferentes.
 *
 * Não existe endpoint que liste todos os clientes (RT-CLI-002: sem termo, lista
 * vazia), então esta é a primeira coisa que a recepção vê. Ela ensina a única
 * regra que precisa saber.
 */
function ComoBuscar() {
  return (
    <div className="cli-dica">
      <p className="cli-dica-titulo">Procure quem está ligando.</p>
      <p>
        Digite o nome, o apelido, ou os últimos dígitos do telefone. Com o telefone na
        mão, os quatro últimos costumam bastar.
      </p>
    </div>
  );
}
