import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Menu } from '../api/capacidades';
import { formatarTelefone, useBusca, type Cliente } from '../api/clientes';
import { Icone } from './Icone';
import { useTermoComPausa } from './pausa';
import './paleta.css';

/**
 * A busca da casca — `⌘K` / `Ctrl+K`.
 *
 * <h2>Ela navega, e agora acha gente</h2>
 *
 * Até 23/09/2026 ela ia só a seções do sistema, e o motivo escrito aqui era que não havia
 * endpoint de busca de cliente. Havia: é o mesmo que a tela de clientes usa desde a
 * RT-CLI-006, e é ele que entende "os quatro últimos dígitos" (RN-CLI-009). A promessa que
 * ficou registrada naquele comentário — *"quando a busca de dado existir, ela entra AQUI,
 * como uma segunda seção"* — é o que este arquivo faz agora.
 *
 * Por que importa: o telefone toca, e quem atende está na tela de comandas. O caminho era
 * ir ao menu, abrir clientes, digitar. Agora são duas teclas e o nome.
 *
 * Comanda continua fora: não existe busca de comanda por nome de cliente no servidor, e
 * uma caixa que promete achar e não acha é pior do que uma que diz o que faz.
 *
 * <h2>Ela só procura o que a pessoa pode abrir</h2>
 *
 * Quem não tem a tela de clientes no menu não busca cliente — quem decide é o servidor
 * (inviolável nº 6), e uma busca que devolvesse fichas para quem não pode vê-las seria a
 * mesma porta com outro nome. Seções e clientes vêm ambos do que o servidor mandou.
 *
 * <h2>Ela só existe enquanto está aberta</h2>
 *
 * A casca monta este componente ao abrir e desmonta ao fechar, em vez de mantê-lo vivo
 * com um `aberta={false}`. Assim o termo digitado e a opção marcada voltam ao zero
 * sozinhos — sem efeito nenhum limpando estado, que é de onde vêm as renderizações em
 * cascata.
 */
export function Paleta({ menus, podeBuscarClientes = false, aoFechar }: {
  menus: Menu[];
  podeBuscarClientes?: boolean;
  aoFechar: () => void;
}) {
  const navegar = useNavigate();
  const [termo, definirTermo] = useState('');
  const [indice, definirIndice] = useState(0);
  const campo = useRef<HTMLInputElement>(null);
  const lista = useRef<HTMLDivElement>(null);

  // A mesma pausa da tela de clientes, pelo mesmo motivo: sem ela, "Maria" dispara cinco
  // consultas e a quinta pode chegar antes da terceira.
  const termoBuscado = useTermoComPausa(podeBuscarClientes ? termo.trim() : '');
  const { data: busca, isFetching } = useBusca(termoBuscado);

  const secoes = useMemo(() => {
    const limpo = semAcento(termo.trim());
    if (!limpo) return menus;
    return menus.filter((menu) => semAcento(menu.rotulo).includes(limpo));
  }, [menus, termo]);

  const clientes = busca?.itens ?? [];

  /*
   * Uma lista só para o teclado, duas na tela.
   *
   * A seta para baixo atravessa as seções e entra nos clientes sem tropeço porque o índice
   * percorre ESTA lista — agrupar de verdade (um índice por grupo) seria reinventar o
   * comportamento que todo mundo já tem no dedo.
   */
  const opcoes: Opcao[] = [
    ...secoes.map((menu) => ({ tipo: 'secao' as const, id: `secao-${menu.id}`, menu })),
    ...clientes.map((cliente) => ({ tipo: 'cliente' as const, id: `cliente-${cliente.id}`,
      cliente })),
  ];
  const marcada = opcoes[Math.min(indice, opcoes.length - 1)];

  // Mantém a opção marcada visível quando a navegação é por teclado: sem isto, a seta
  // para baixo percorre uma lista que não rola, e a seleção some da vista.
  useEffect(() => {
    lista.current?.querySelector('[aria-selected="true"]')
      ?.scrollIntoView({ block: 'nearest' });
  }, [indice, opcoes.length]);

  const ir = (opcao: Opcao | undefined) => {
    if (!opcao) return;
    // A ficha da cliente é endereço na tela de clientes — é o que permite abri-la daqui.
    navegar(opcao.tipo === 'secao' ? opcao.menu.rota : `/clientes?cliente=${opcao.cliente.id}`);
    aoFechar();
  };

  const aoTeclar = (evento: React.KeyboardEvent) => {
    if (evento.key === 'ArrowDown') {
      evento.preventDefault();
      definirIndice((atual) => (atual + 1) % Math.max(opcoes.length, 1));
    } else if (evento.key === 'ArrowUp') {
      evento.preventDefault();
      definirIndice((atual) => (atual - 1 + opcoes.length) % Math.max(opcoes.length, 1));
    } else if (evento.key === 'Enter') {
      evento.preventDefault();
      ir(marcada);
    } else if (evento.key === 'Escape') {
      evento.preventDefault();
      aoFechar();
    }
  };

  return (
    <div className="paleta-encosto" onMouseDown={aoFechar}>
      <div className="paleta" role="dialog" aria-modal="true" aria-label="Ir para"
        onMouseDown={(e) => e.stopPropagation()}>
        <div className="paleta-campo">
          <Icone nome="busca" />
          <input
            ref={campo}
            autoFocus
            type="text"
            value={termo}
            placeholder={podeBuscarClientes ? 'Ir para… ou procurar uma cliente' : 'Ir para…'}
            aria-label="Ir para"
            aria-controls="paleta-lista"
            aria-activedescendant={marcada ? `paleta-${marcada.id}` : undefined}
            role="combobox"
            aria-expanded="true"
            autoComplete="off"
            onChange={(e) => { definirTermo(e.target.value); definirIndice(0); }}
            onKeyDown={aoTeclar}
          />
          <kbd>esc</kbd>
        </div>

        {opcoes.length === 0 ? (
          <p className="paleta-vazio">{semNada(termo, podeBuscarClientes, busca?.aviso, isFetching)}</p>
        ) : (
          <div ref={lista} id="paleta-lista" className="paleta-lista" role="listbox"
            aria-label="Resultados">
            {/* Grupo sem item não vira título sozinho: um "Seções" órfão em cima da lista
                de clientes é ruído que promete uma lista que não existe. */}
            {secoes.length > 0 && (
              <Grupo rotulo="Seções" mostrarRotulo={clientes.length > 0}>
                {opcoes.filter((o) => o.tipo === 'secao').map((opcao) => (
                  <Item key={opcao.id} opcao={opcao} marcada={opcao.id === marcada?.id}
                    posicao={opcoes.indexOf(opcao)} aoMarcar={definirIndice} aoEscolher={ir} />
                ))}
              </Grupo>
            )}
            {clientes.length > 0 && (
              <Grupo rotulo={rotuloDeClientes(busca?.interpretacao)} mostrarRotulo>
                {opcoes.filter((o) => o.tipo === 'cliente').map((opcao) => (
                  <Item key={opcao.id} opcao={opcao} marcada={opcao.id === marcada?.id}
                    posicao={opcoes.indexOf(opcao)} aoMarcar={definirIndice} aoEscolher={ir} />
                ))}
              </Grupo>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

type Opcao =
  | { tipo: 'secao'; id: string; menu: Menu }
  | { tipo: 'cliente'; id: string; cliente: Cliente };

function Grupo({ rotulo, mostrarRotulo, children }: {
  rotulo: string; mostrarRotulo: boolean; children: React.ReactNode;
}) {
  return (
    <div role="group" aria-label={rotulo}>
      {/* O título só aparece quando há duas listas: com uma só, ele nomeia o óbvio. */}
      {mostrarRotulo && <p className="paleta-grupo" aria-hidden="true">{rotulo}</p>}
      {children}
    </div>
  );
}

function Item({ opcao, marcada, posicao, aoMarcar, aoEscolher }: {
  opcao: Opcao; marcada: boolean; posicao: number;
  aoMarcar: (posicao: number) => void; aoEscolher: (opcao: Opcao) => void;
}) {
  const ehSecao = opcao.tipo === 'secao';
  return (
    <button
      id={`paleta-${opcao.id}`}
      type="button"
      role="option"
      aria-selected={marcada}
      className={`paleta-item${marcada ? ' marcado' : ''}`}
      onMouseEnter={() => aoMarcar(posicao)}
      onClick={() => aoEscolher(opcao)}
    >
      <span className="paleta-item-icone">
        <Icone nome={ehSecao ? opcao.menu.icone : 'pessoas'} />
      </span>
      <span className="paleta-item-rotulo">
        {ehSecao ? opcao.menu.rotulo : nomeDaCliente(opcao.cliente)}
      </span>
      {/*
        RT-INF-017 — o endereço técnico (`/agenda`) saiu: é jargão de quem programa, e não
        ajuda ninguém a escolher. O telefone da cliente fica: é o que separa duas Marias.
      */}
      {!ehSecao && (
        <span className="paleta-item-detalhe">{formatarTelefone(opcao.cliente.telefone)}</span>
      )}
      {/* O que a pessoa pode fazer com a linha marcada: Enter. */}
      {marcada && <kbd className="paleta-item-enter" aria-hidden="true">↵</kbd>}
    </button>
  );
}

function nomeDaCliente(cliente: Cliente): string {
  // O apelido é como o salão chama a pessoa, e é por ele que a recepção reconhece — mas
  // sozinho ele não identifica ficha nenhuma.
  return cliente.apelido ? `${cliente.nome} · ${cliente.apelido}` : cliente.nome;
}

/** O servidor é quem diz como leu o termo (RN-CLI-009); aqui só se escolhe a palavra. */
function rotuloDeClientes(interpretacao: string | undefined): string {
  return interpretacao === 'TELEFONE' ? 'Clientes · pelo telefone' : 'Clientes';
}

/**
 * O vazio explica o alcance da busca.
 *
 * Sem isso, quem procura uma comanda pelo nome da cliente conclui que a busca não funciona
 * — e não que ela ainda não vai ali.
 */
function semNada(termo: string, podeBuscarClientes: boolean, aviso: string | null | undefined,
                 procurando: boolean): string {
  if (procurando) return 'Procurando…';
  // "Informe ao menos 2 letras" é texto do domínio: vem do servidor, não daqui.
  if (aviso) return aviso;
  if (!termo.trim()) return 'Digite para achar uma seção do sistema ou uma cliente.';
  return podeBuscarClientes
    ? 'Nada com esse nome, nem em seções nem em clientes. Comanda ainda se procura '
      + 'dentro da própria tela.'
    : 'Nada com esse nome. A busca alcança as seções do sistema.';
}

/**
 * Busca sem acento e sem caixa.
 *
 * Quem digita "configuracao" com o telefone no ombro precisa achar "Configurações" —
 * exigir o acento é transformar a busca numa prova de digitação.
 */
function semAcento(texto: string): string {
  return texto.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLocaleLowerCase('pt-BR');
}
