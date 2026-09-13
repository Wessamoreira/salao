import { useContext, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { DestinoDasAcoes } from './destinoDasAcoes';
import { Icone } from './Icone';
import './pagina.css';

/**
 * A moldura de toda tela do sistema.
 *
 * <h2>O título NÃO se repete aqui</h2>
 *
 * A trilha da barra de topo já diz onde a pessoa está, e ela É o `h1` da página. Um
 * segundo título logo abaixo, dizendo a mesma palavra, come oitenta pixels de altura em
 * toda tela e não informa nada — era o que cada uma destas telas fazia, cada uma com um
 * cabeçalho próprio, e é de onde vinha a sensação de cinco produtos diferentes.
 *
 * As ações principais sobem para o topo, ao lado do relógio, num lugar só: quem procura
 * "onde eu clico para criar" aprende uma vez e acerta em todas as telas.
 *
 * @param acoes  ações principais da tela. Vão para a barra de topo, por portal
 * @param barra  filtros, busca e abas da tela — a faixa logo abaixo do topo. Sem barra,
 *               sem faixa: tela que não filtra nada não paga por uma linha vazia
 */
export function Pagina({ acoes, barra, children }: {
  acoes?: ReactNode;
  barra?: ReactNode;
  children: ReactNode;
}) {
  const destino = useContext(DestinoDasAcoes);

  return (
    <div className="pagina">
      {/*
        Sem destino, as ações ficam AQUI, numa faixa própria.
        Não é um caso teórico: foi o que os testes pegaram. Uma tela renderizada fora da
        casca perdia a ação principal por completo — o botão de salvar simplesmente não
        existia, e nada acusava. Um portal que some em silêncio quando o destino falta é
        pior que nenhum portal.
      */}
      {acoes && (destino
        ? createPortal(acoes, destino)
        : <div className="pagina-acoes-soltas">{acoes}</div>)}
      {barra && <div className="pagina-barra">{barra}</div>}
      <div className="pagina-corpo">{children}</div>
    </div>
  );
}

/**
 * Tela sem nada para mostrar.
 *
 * <h2>Vazio é convite, não aviso</h2>
 *
 * "Nenhum resultado" encerra a conversa. O que a pessoa precisa é saber o que fazer
 * agora — e quando há uma ação óbvia, ela fica aqui, no meio da tela onde o olho já
 * está, e não escondida no canto.
 */
export function Vazio({ icone = 'busca', titulo, children, acao }: {
  icone?: string;
  titulo: string;
  children?: ReactNode;
  acao?: ReactNode;
}) {
  return (
    <div className="vazio">
      <span className="vazio-icone" aria-hidden="true"><Icone nome={icone} tamanho={22} /></span>
      <h2 className="vazio-titulo">{titulo}</h2>
      {children && <p className="vazio-texto">{children}</p>}
      {acao && <div className="vazio-acao">{acao}</div>}
    </div>
  );
}

/**
 * O esqueleto da espera.
 *
 * <h2>Ele tem a FORMA do que vem</h2>
 *
 * As telas diziam "Carregando…" em texto. Quando o dado chega, o parágrafo de uma linha
 * é substituído por uma lista de vinte, tudo salta de lugar, e é isso que se sente como
 * travada — não a demora, o pulo. Um esqueleto com a altura e o ritmo do conteúdo real
 * faz a chegada ser uma troca de cor, não um rearranjo.
 *
 * @param linhas quantas linhas o conteúdo real costuma ter nesta tela
 */
export function Esqueleto({ linhas = 6, altura = 44 }: { linhas?: number; altura?: number }) {
  return (
    <div className="esqueleto" aria-hidden="true">
      {Array.from({ length: linhas }, (_, i) => (
        <div key={i} className="esqueleto-linha" style={{ height: altura }} />
      ))}
    </div>
  );
}

/**
 * Falha de carregamento com saída.
 *
 * <p>Diz o que houve e oferece o próximo passo. Sem "Ops", sem desculpa: erro informa
 * estado e ação — quem está no balcão precisa decidir se tenta de novo ou se liga para
 * alguém.
 */
export function Falha({ titulo = 'Não foi possível carregar', children, aoTentar }: {
  titulo?: string;
  children?: ReactNode;
  aoTentar?: () => void;
}) {
  return (
    <div className="falha" role="alert">
      <h2 className="falha-titulo">{titulo}</h2>
      {children && <p className="falha-texto">{children}</p>}
      {aoTentar && (
        <button className="falha-acao" type="button" onClick={aoTentar}>Tentar de novo</button>
      )}
    </div>
  );
}

/**
 * A ação principal de uma tela, no topo.
 *
 * <p>Uma por tela, no máximo duas. Três botões no topo significam que a tela não decidiu
 * qual é o trabalho dela.
 */
export function Acao({ tipo = 'principal', children, ...resto }: {
  tipo?: 'principal' | 'discreta';
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className={`pagina-acao ${tipo}`} type="button" {...resto}>{children}</button>
  );
}
