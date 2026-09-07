import type { ComandaEmAberto } from '../../api/comandas';
import { horasAberta, reais } from '../../api/comandas';

/**
 * RT-ATD-008 — a barra de comandas abertas (R-UX-07).
 *
 * *"Comanda aberta esquecida é dinheiro que sai pela porta."* Ela fica sempre à vista,
 * da mais antiga para a mais nova — a que está lá desde as nove da manhã é a que
 * interessa, e é por isso que ela vem primeiro em vez de por ordem de número.
 */
export function BarraDeAbertas({ comandas, selecionadaId, aoSelecionar }: {
  comandas: ComandaEmAberto[];
  selecionadaId: string | null;
  aoSelecionar: (id: string) => void;
}) {
  return (
    <aside className="cmd-barra" aria-label="Comandas abertas">
      <header className="cmd-barra-topo">
        <h2 className="cmd-barra-titulo">Comandas abertas</h2>
        <span className="cmd-barra-contador">{comandas.length}</span>
      </header>

      {comandas.length === 0 ? (
        <p className="cmd-barra-vazia">
          Nenhuma comanda aberta. O balcão está em dia.
        </p>
      ) : (
        <ul className="cmd-barra-lista">
          {comandas.map(({ comanda, clienteNome }) => {
            const horas = horasAberta(comanda.abertaEm);
            const esquecida = horas >= 3;
            return (
              <li key={comanda.id}>
                <button
                  type="button"
                  className={
                    'cmd-linha'
                    + (comanda.id === selecionadaId ? ' selecionada' : '')
                    + (esquecida ? ' esquecida' : '')
                  }
                  aria-current={comanda.id === selecionadaId ? 'true' : undefined}
                  onClick={() => aoSelecionar(comanda.id)}
                >
                  <span className="cmd-linha-numero">#{comanda.numero}</span>
                  <span className="cmd-linha-nome">{clienteNome ?? 'Sem nome'}</span>
                  <span className="cmd-linha-valor">{reais(comanda.valorLiquido)}</span>
                  {/*
                      O aviso é texto, não só cor: quem não distingue as duas cores
                      continua vendo que esta comanda está aberta há muito tempo.
                    */}
                  {esquecida && (
                    <span className="cmd-linha-aviso">
                      aberta há {Math.floor(horas)}h
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </aside>
  );
}
