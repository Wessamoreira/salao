import type { Grau, PossivelDuplicado } from '../../api/clientes';
import { comoChamar } from '../../api/clientes';
import { Telefone } from './Telefone';

/**
 * O grau muda o que a tela DIZ, não o quanto ela grita.
 *
 * `MESMO_TELEFONE` é quase sempre mãe e filha dividindo o número (RN-CLI-005), e
 * não pode parecer erro: tratar família como problema treina a recepção a ignorar
 * o aviso — e aí ele para de servir para o caso em que está certo.
 */
const TEXTO: Record<Grau, string> = {
  PROVAVEL: 'Já existe uma ficha que parece ser a mesma pessoa:',
  POSSIVEL: 'Nome parecido com o de quem já está cadastrado:',
  MESMO_TELEFONE: 'Este telefone já está com:',
};

export function AvisoDeDuplicado({
  duplicados,
  aoEscolher,
}: {
  duplicados: PossivelDuplicado[];
  aoEscolher?: (id: string) => void;
}) {
  if (duplicados.length === 0) return null;

  // O primeiro grau da lista manda no tom: o backend já ordena do mais forte para
  // o mais fraco, então basta olhar o começo.
  const grau = duplicados[0].grau;
  const forte = grau === 'PROVAVEL';

  return (
    <div className={forte ? 'cli-aviso cli-aviso-forte' : 'cli-aviso'} role="status">
      <p>{TEXTO[grau]}</p>
      <ul>
        {duplicados.map(({ cliente, grau: dela }) => {
          // Só quem pode ser a MESMA pessoa é oferecido para juntar. Mãe e filha
          // dividindo o telefone aparecem como contexto e nada mais: um botão
          // "juntar" ali convidaria a fundir duas pessoas diferentes — o estrago
          // que o domínio inteiro foi desenhado para evitar, oferecido pela tela.
          const podeJuntar = aoEscolher && dela !== 'MESMO_TELEFONE';
          return (
            <li key={cliente.id}>
              {podeJuntar ? (
                <button
                  type="button"
                  className="acao-discreta"
                  onClick={() => aoEscolher(cliente.id)}
                >
                  {comoChamar(cliente)}
                </button>
              ) : (
                <span>{comoChamar(cliente)}</span>
              )}
              <span className="cli-aviso-fone">
                <Telefone e164={cliente.telefone} />
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
