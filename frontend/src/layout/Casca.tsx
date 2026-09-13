import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useCapacidades, type Capacidades } from '../api/capacidades';
import { api } from '../api/http';
import { useConectado } from '../pwa/conectado';
import { definirTokenAtual } from '../sessao/sessao';
import { HoraDoSalao } from './HoraDoSalao';
import { Icone } from './Icone';
import { Barreira } from './Barreira';
import { DestinoDasAcoes } from './destinoDasAcoes';
import { Paleta } from './Paleta';
import './casca.css';

const CHAVE_RECOLHIDA = 'salao:lateral-recolhida';

/**
 * A casca desenha exatamente o que `/me/capabilities` enviou.
 *
 * Grupos, ordem, rótulos, rotas e ícones vêm do servidor. Não há aqui nenhuma tabela
 * que decida onde um menu aparece — agrupar no front seria recriar no JavaScript a
 * regra que aquele endpoint existe para eliminar, e as duas cópias divergem na primeira
 * seção nova.
 *
 * O que é decisão do front, e só isto: a lateral estar recolhida ou aberta, que é
 * preferência de quem olha a tela e não tem nada a ver com permissão.
 */
export function Casca() {
  const { data: capacidades, isPending } = useCapacidades();
  const [menuAberto, setMenuAberto] = useState(false);
  const [recolhida, setRecolhida] = useState(
    () => localStorage.getItem(CHAVE_RECOLHIDA) === 'sim',
  );
  const [paletaAberta, setPaletaAberta] = useState(false);
  const [destinoDasAcoes, setDestinoDasAcoes] = useState<HTMLElement | null>(null);
  const [contaAberta, setContaAberta] = useState(false);
  const [saindo, setSaindo] = useState(false);
  const [erroAoSair, setErroAoSair] = useState(false);
  const conectado = useConectado();
  const local = useLocation();
  const navegar = useNavigate();
  const clienteDeConsultas = useQueryClient();
  const lateral = useRef<HTMLElement>(null);
  const conta = useRef<HTMLDivElement>(null);
  const botaoAbrir = useRef<HTMLButtonElement>(null);
  const botaoFechar = useRef<HTMLButtonElement>(null);
  const botaoConta = useRef<HTMLButtonElement>(null);
  const conteudo = useRef<HTMLElement>(null);
  const rolagem = useRef(new Map<string, number>());

  const fecharMenu = (devolverFoco = false) => {
    if (devolverFoco) botaoAbrir.current?.focus();
    setMenuAberto(false);
  };

  const alternarRecolhida = () => {
    setRecolhida((atual) => {
      localStorage.setItem(CHAVE_RECOLHIDA, atual ? 'nao' : 'sim');
      return !atual;
    });
  };

  const sair = async () => {
    if (!conectado || saindo) return;
    setSaindo(true);
    setErroAoSair(false);
    try {
      await api('/v1/auth/logout', { method: 'POST' });
      definirTokenAtual(null);
      clienteDeConsultas.clear();
      navegar('/entrar', { replace: true });
    } catch {
      setErroAoSair(true);
    } finally {
      setSaindo(false);
    }
  };

  /*
   * Rolagem por tela.
   *
   * O container que rola é `.casca-conteudo`, e ele NÃO é a janela: trocar de rota
   * mantinha o `scrollTop` de onde a pessoa estava, então sair do fim de uma lista de
   * clientes e abrir a agenda caía no meio da tarde. O navegador não resolve isso — para
   * ele a página nunca rolou.
   *
   * A limpeza guarda a posição da rota que está SAINDO (a chave é capturada no efeito
   * anterior, e o DOM ainda tem o valor antigo); o efeito seguinte devolve a da que
   * entrou, ou zero.
   */
  useEffect(() => {
    const area = conteudo.current;
    if (!area) return;
    const posicoes = rolagem.current;
    const deQuem = local.pathname;
    area.scrollTop = posicoes.get(deQuem) ?? 0;
    return () => { posicoes.set(deQuem, area.scrollTop); };
  }, [local.pathname]);

  // ⌘K abre a busca de qualquer lugar — inclusive com o foco dentro de um campo, que é
  // onde a recepção passa o dia. Só um `input` de texto engole o atalho, e nem isso:
  // o navegador entrega o evento antes.
  useEffect(() => {
    const aoTeclar = (evento: KeyboardEvent) => {
      if ((evento.metaKey || evento.ctrlKey) && evento.key.toLowerCase() === 'k') {
        evento.preventDefault();
        setPaletaAberta((atual) => !atual);
      }
    };
    document.addEventListener('keydown', aoTeclar);
    return () => document.removeEventListener('keydown', aoTeclar);
  }, []);

  // Fechar o menu da conta ao clicar fora e ao apertar Esc: um popover que só fecha no
  // próprio botão fica aberto atrás do que a pessoa foi fazer.
  useEffect(() => {
    if (!contaAberta) return;
    const aoClicar = (evento: MouseEvent) => {
      if (!conta.current?.contains(evento.target as Node)) setContaAberta(false);
    };
    const aoTeclar = (evento: KeyboardEvent) => {
      if (evento.key !== 'Escape') return;
      botaoConta.current?.focus();
      setContaAberta(false);
    };
    document.addEventListener('mousedown', aoClicar);
    document.addEventListener('keydown', aoTeclar);
    return () => {
      document.removeEventListener('mousedown', aoClicar);
      document.removeEventListener('keydown', aoTeclar);
    };
  }, [contaAberta]);

  useEffect(() => {
    if (!menuAberto) return;
    botaoFechar.current?.focus();

    const aoTeclar = (evento: KeyboardEvent) => {
      if (evento.key === 'Escape') {
        evento.preventDefault();
        botaoAbrir.current?.focus();
        setMenuAberto(false);
        return;
      }
      if (evento.key !== 'Tab' || !lateral.current) return;

      const focaveis = Array.from(lateral.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled])',
      )).filter((elemento) => elemento.offsetParent !== null);
      const primeiro = focaveis[0];
      const ultimo = focaveis.at(-1);
      if (!primeiro || !ultimo) return;

      if (evento.shiftKey && document.activeElement === primeiro) {
        evento.preventDefault();
        ultimo.focus();
      } else if (!evento.shiftKey && document.activeElement === ultimo) {
        evento.preventDefault();
        primeiro.focus();
      }
    };

    document.addEventListener('keydown', aoTeclar);
    return () => document.removeEventListener('keydown', aoTeclar);
  }, [menuAberto]);

  if (isPending) return <Carregando />;
  if (!capacidades) return null;

  const secoes = secoesDe(capacidades);
  const menuAtual = capacidades.menus.find((menu) => local.pathname.startsWith(menu.rota));
  const nomeDaSecao = menuAtual?.rotulo ?? 'Início';
  const iniciaisDoSalao = iniciais(capacidades.estabelecimento.nome);
  const inicialDoUsuario = capacidades.nome.trim().charAt(0).toLocaleUpperCase('pt-BR') || 'U';

  return (
    <div className={`casca${recolhida ? ' recolhida' : ''}`}>
      <a className="casca-pular" href="#conteudo-principal">Ir para o conteúdo</a>

      <aside ref={lateral} className={`casca-lateral${menuAberto ? ' aberta' : ''}`}
        aria-label="Navegação">
        <header className="casca-marca">
          {/*
            A placa. É o único lugar da casca onde a didone aparece, e é o que o sistema
            de design chama de "gesto editorial": o negócio é beleza, e a marca do salão
            merece um desenho — o resto da tela, não.
          */}
          <span className="casca-placa" aria-hidden="true">{iniciaisDoSalao}</span>
          <span className="casca-identidade">
            <strong title={capacidades.estabelecimento.nome}>
              {capacidades.estabelecimento.nome}
            </strong>
            <small>gestão do salão</small>
          </span>

          <button className="casca-recolher" type="button" onClick={alternarRecolhida}
            aria-label={recolhida ? 'Expandir menu' : 'Recolher menu'}
            title={recolhida ? 'Expandir menu' : 'Recolher menu'}>
            <Icone nome={recolhida ? 'expandir' : 'recolher'} tamanho={17} />
          </button>
          <button ref={botaoFechar} className="casca-fechar-menu" type="button"
            aria-label="Fechar menu" onClick={() => fecharMenu(true)}>
            <Icone nome="fechar" />
          </button>
        </header>

        <nav className="casca-nav" aria-label="Seções do sistema">
          {secoes.map((grupo) => {
            return (
              <div className="casca-grupo" key={grupo.id}>
                {grupo.rotulo && (
                  <h2 className="casca-grupo-rotulo">
                    <span>{grupo.rotulo}</span>
                  </h2>
                )}
                <ul className="casca-menu">
                  {grupo.itens.map((menu) => (
                    <li key={menu.id}>
                      <NavLink
                        to={menu.rota}
                        viewTransition
                        onClick={() => fecharMenu()}
                        title={recolhida ? menu.rotulo : undefined}
                        className={({ isActive }) => `casca-link${isActive ? ' ativo' : ''}`}
                      >
                        <span className="casca-link-icone"><Icone nome={menu.icone} /></span>
                        <span className="casca-link-rotulo">{menu.rotulo}</span>
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </nav>

        <div ref={conta} className="casca-conta">
          {contaAberta && (
            <div className="casca-conta-menu" role="menu" aria-label="Conta">
              <p className="casca-conta-email" title={capacidades.email}>{capacidades.email}</p>
              <button className="casca-conta-item" type="button" role="menuitem"
                onClick={() => { setContaAberta(false); navegar('/seguranca/segundo-fator'); }}>
                <Icone nome="escudo" tamanho={16} />
                <span>Segundo fator</span>
              </button>
              <button className="casca-conta-item perigo" type="button" role="menuitem"
                onClick={sair} disabled={!conectado || saindo}
                title={conectado ? undefined
                  : 'Reconecte à internet para encerrar a sessão com segurança'}>
                <Icone nome="sair" tamanho={16} />
                <span>{saindo ? 'Encerrando…' : 'Sair da conta'}</span>
              </button>
              {erroAoSair && (
                <p className="casca-conta-erro" role="alert">
                  Não foi possível sair. Tente novamente.
                </p>
              )}
            </div>
          )}

          <button ref={botaoConta} className="casca-conta-botao" type="button"
            aria-haspopup="menu" aria-expanded={contaAberta}
            onClick={() => setContaAberta((atual) => !atual)}>
            <span className="casca-avatar" aria-hidden="true">{inicialDoUsuario}</span>
            <span className="casca-conta-texto">
              <strong>{capacidades.nome}</strong>
              <small>{rotuloDoPerfil(capacidades.perfil)}</small>
            </span>
            <span className="casca-conta-seta" aria-hidden="true">
              <Icone nome="chevron" tamanho={16} />
            </span>
          </button>
        </div>
      </aside>

      {menuAberto && (
        <button className="casca-encosto" type="button" aria-label="Fechar navegação"
          onClick={() => fecharMenu(true)} />
      )}

      <div className="casca-coluna">
        <header className="casca-topo">
          <button ref={botaoAbrir} className="casca-abrir-menu" type="button"
            aria-label="Abrir menu"
            aria-expanded={menuAberto} onClick={() => setMenuAberto(true)}>
            <Icone nome="menu" />
          </button>

          {/*
            Este É o título da página. Cada tela tinha um `h1` próprio repetindo a mesma
            palavra logo abaixo daqui, comendo oitenta pixels de altura sem informar
            nada — e cada uma com um cabeçalho diferente, que é de onde vinha a sensação
            de cinco produtos.
          */}
          <div className="casca-trilha">
            <span>Salão</span>
            <h1>{nomeDaSecao}</h1>
          </div>

          {/*
            Um botão com cara de campo, e não um campo de verdade: ele abre a paleta, e
            um `input` que não aceita texto no lugar onde foi clicado é a pior forma de
            enganar quem digita rápido.
          */}
          <button className="casca-busca" type="button" onClick={() => setPaletaAberta(true)}>
            <Icone nome="busca" tamanho={16} />
            <span>Ir para…</span>
            <kbd>{atalhoDaBusca()}</kbd>
          </button>

          <div className="casca-topo-final">
            {/* As ações de cada tela entram aqui por portal (ver `Pagina`): um lugar só,
                aprendido uma vez, certo em todas as telas. */}
            <div ref={setDestinoDasAcoes} className="casca-acoes" />
            <HoraDoSalao fuso={capacidades.estabelecimento.timezone} />
          </div>
        </header>

        <main ref={conteudo} id="conteudo-principal" className="casca-conteudo" tabIndex={-1}>
          <DestinoDasAcoes value={destinoDasAcoes}>
            {/*
              `key` na rota: uma tela que falhou remonta a barreira ao navegar, então o
              erro limpa sozinho. Sem isso ela ficaria mostrando a falha para sempre,
              inclusive depois de voltar com o problema já resolvido.
            */}
            <Barreira key={local.pathname} variante="pagina">
              <Outlet />
            </Barreira>
          </DestinoDasAcoes>
        </main>
      </div>

      {/* Montada só quando aberta: é o que faz o termo digitado voltar ao zero sem
          nenhum efeito limpando estado. */}
      {paletaAberta && (
        <Paleta menus={capacidades.menus} aoFechar={() => setPaletaAberta(false)} />
      )}
    </div>
  );
}

function Carregando() {
  return (
    <div className="casca casca-carregando" aria-label="Carregando o sistema">
      <div className="casca-carregando-lateral" />
      <div className="casca-coluna">
        <div className="casca-carregando-topo" />
        <div className="casca-carregando-conteudo" />
      </div>
    </div>
  );
}

/**
 * As seções da lateral, tolerantes a um servidor de outra versão.
 *
 * <h2>Por que isto não é `capacidades.grupos.map(...)` e pronto</h2>
 *
 * Porque foi exatamente assim que a tela inteira ficou branca. Um backend anterior à
 * introdução de `grupos` devolve o campo ausente, `undefined.map` estoura, e o React
 * desmonta a árvore toda — o front morre por causa de um campo que ele só usa para
 * decidir a ORDEM de uma lista que já recebeu inteira.
 *
 * Front e back saem de versão o tempo todo: durante um deploy, com uma aba velha aberta,
 * com o service worker servindo um bundle de ontem. A casca não pode depender de estarem
 * casados para desenhar um menu.
 *
 * Sem `grupos`, a lista sai plana — que é como ela era até ontem. E o balde final recolhe
 * quem aponta para um grupo inexistente: um menu órfão sumiria em silêncio, e seção
 * inteira do sistema desaparecendo sem erro nenhum é pior do que seção fora de ordem.
 */
function secoesDe(capacidades: Capacidades) {
  const grupos = capacidades.grupos ?? [];
  const secoes = grupos
    .map((grupo) => ({
      id: grupo.id,
      rotulo: grupo.rotulo,
      itens: capacidades.menus.filter((menu) => menu.grupo === grupo.id),
    }))
    // Grupo sem item visível não vira título sozinho: quem filtra permissão é o servidor.
    .filter((secao) => secao.itens.length > 0);

  const agrupados = new Set(secoes.flatMap((secao) => secao.itens.map((menu) => menu.id)));
  const soltos = capacidades.menus.filter((menu) => !agrupados.has(menu.id));
  if (soltos.length > 0) {
    secoes.push({ id: '__sem-grupo', rotulo: '', itens: soltos });
  }
  return secoes;
}

function iniciais(nome: string): string {
  const partes = nome.trim().split(/\s+/).filter(Boolean);
  if (partes.length === 0) return 'S';
  // Uma palavra vira UMA letra: "Salão" abreviado em "SA" não abrevia nada, só trunca.
  if (partes.length === 1) return partes[0].charAt(0).toLocaleUpperCase('pt-BR');
  return `${partes[0][0]}${partes.at(-1)?.[0] ?? ''}`.toLocaleUpperCase('pt-BR');
}

function rotuloDoPerfil(perfil: string): string {
  const limpo = perfil.toLocaleLowerCase('pt-BR').replaceAll('_', ' ');
  return limpo.charAt(0).toLocaleUpperCase('pt-BR') + limpo.slice(1);
}

/** `⌘K` no Mac, `Ctrl K` no resto — mostrar o atalho errado é pior do que não mostrar. */
function atalhoDaBusca(): string {
  const ehApple = typeof navigator !== 'undefined'
    && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
  return ehApple ? '⌘K' : 'Ctrl K';
}

export type { Capacidades };
