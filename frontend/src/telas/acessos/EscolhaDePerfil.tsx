import { usePerfis, type Perfil } from '../../api/acessos';
import { Esqueleto } from '../../layout/Pagina';

/**
 * Escolher perfil lendo o que ele faz, em vez de decorando o nome.
 *
 * <h2>"GERENTE" não diz se vê o dinheiro do salão</h2>
 *
 * E é essa a pergunta de quem está escolhendo. Cada opção traz a frase do servidor, a marca de
 * quem exige segundo fator, e — sob demanda — a lista do que o perfil concede.
 *
 * <h2>A lista completa fica fechada</h2>
 *
 * Vinte e quatro linhas para o administrador, abertas de saída, afogariam a única decisão que a
 * pessoa veio tomar. Elas existem para a dúvida ("posso deixar a Júlia como gerente?"), e é na
 * dúvida que se abrem.
 */
export function EscolhaDePerfil({ grupo, valor, aoEscolher }: {
  /** O `name` do grupo de rádios; precisa ser único quando há duas fichas abertas. */
  grupo: string;
  valor: Perfil;
  aoEscolher: (perfil: Perfil) => void;
}) {
  const perfis = usePerfis();

  if (perfis.isPending) return <Esqueleto linhas={3} altura={48} />;

  return (
    <ul className="acs-perfis">
      {perfis.data?.map((opcao) => {
        const escolhido = opcao.perfil === valor;
        return (
          <li key={opcao.perfil} className={escolhido ? 'escolhido' : undefined}>
            <label className="acs-perfil-opcao">
              <input type="radio" name={grupo} checked={escolhido}
                onChange={() => aoEscolher(opcao.perfil)} />
              <span className="acs-perfil-nome">
                {opcao.rotulo}
                {opcao.exigeMfa && (
                  <span className="acs-exige-2fa">exige segundo fator</span>
                )}
              </span>
              <span className="acs-perfil-resumo">{opcao.resumo}</span>
            </label>

            {escolhido && (
              <details className="acs-pode-fazer">
                <summary>O que {opcao.rotulo.toLowerCase()} pode fazer</summary>
                <ul>
                  {opcao.podeFazer.map((frase) => <li key={frase}>{frase}</li>)}
                </ul>
              </details>
            )}
          </li>
        );
      })}
    </ul>
  );
}
