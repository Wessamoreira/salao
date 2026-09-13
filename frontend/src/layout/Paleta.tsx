import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Menu } from '../api/capacidades';
import { Icone } from './Icone';
import './paleta.css';

/**
 * A busca da casca — `⌘K` / `Ctrl+K`.
 *
 * <h2>Ela navega; não procura dado</h2>
 *
 * Procurar cliente e comanda pelo nome exige um endpoint de busca que não existe, e uma
 * caixa que promete achar a Maria e devolve só "Clientes" é pior do que uma caixa que
 * diz o que faz. O rótulo é "Ir para", e é exatamente isso que ela faz.
 *
 * Quando a busca de dado existir, ela entra AQUI, como uma segunda seção — não como uma
 * segunda caixa noutro canto da tela.
 *
 * <h2>Ela só existe enquanto está aberta</h2>
 *
 * A casca monta este componente ao abrir e desmonta ao fechar, em vez de mantê-lo vivo
 * com um `aberta={false}`. Assim o termo digitado e a opção marcada voltam ao zero
 * sozinhos — sem efeito nenhum limpando estado, que é de onde vêm as renderizações em
 * cascata.
 *
 * <h2>Ela desenha o que o servidor mandou</h2>
 *
 * A lista é `capacidades.menus`, a mesma da lateral. Uma seção que o usuário não pode
 * ver não está na lista, então não há o que esconder aqui.
 */
export function Paleta({ menus, aoFechar }: {
  menus: Menu[];
  aoFechar: () => void;
}) {
  const navegar = useNavigate();
  const [termo, definirTermo] = useState('');
  const [indice, definirIndice] = useState(0);
  const campo = useRef<HTMLInputElement>(null);
  const lista = useRef<HTMLUListElement>(null);

  const achados = useMemo(() => {
    const limpo = semAcento(termo.trim());
    if (!limpo) return menus;
    return menus.filter((menu) => semAcento(menu.rotulo).includes(limpo));
  }, [menus, termo]);

  // Mantém a opção marcada visível quando a navegação é por teclado: sem isto, a seta
  // para baixo percorre uma lista que não rola, e a seleção some da vista.
  useEffect(() => {
    lista.current?.querySelector('[aria-selected="true"]')
      ?.scrollIntoView({ block: 'nearest' });
  }, [indice, achados.length]);

  const ir = (menu: Menu | undefined) => {
    if (!menu) return;
    navegar(menu.rota);
    aoFechar();
  };

  const aoTeclar = (evento: React.KeyboardEvent) => {
    if (evento.key === 'ArrowDown') {
      evento.preventDefault();
      definirIndice((atual) => (atual + 1) % Math.max(achados.length, 1));
    } else if (evento.key === 'ArrowUp') {
      evento.preventDefault();
      definirIndice((atual) => (atual - 1 + achados.length) % Math.max(achados.length, 1));
    } else if (evento.key === 'Enter') {
      evento.preventDefault();
      ir(achados[indice]);
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
            placeholder="Ir para…"
            aria-label="Ir para"
            aria-controls="paleta-lista"
            aria-activedescendant={achados[indice] ? `paleta-${achados[indice].id}` : undefined}
            role="combobox"
            aria-expanded="true"
            autoComplete="off"
            onChange={(e) => { definirTermo(e.target.value); definirIndice(0); }}
            onKeyDown={aoTeclar}
          />
          <kbd>esc</kbd>
        </div>

        {achados.length === 0 ? (
          <p className="paleta-vazio">
            Nada com esse nome. A busca alcança as seções do sistema — cliente e comanda
            ainda se procuram dentro da própria tela.
          </p>
        ) : (
          <ul ref={lista} id="paleta-lista" className="paleta-lista" role="listbox"
            aria-label="Seções">
            {achados.map((menu, posicao) => (
              <li key={menu.id}>
                <button
                  id={`paleta-${menu.id}`}
                  type="button"
                  role="option"
                  aria-selected={posicao === indice}
                  className={`paleta-item${posicao === indice ? ' marcado' : ''}`}
                  onMouseEnter={() => definirIndice(posicao)}
                  onClick={() => ir(menu)}
                >
                  <span className="paleta-item-icone"><Icone nome={menu.icone} /></span>
                  <span className="paleta-item-rotulo">{menu.rotulo}</span>
                  <span className="paleta-item-rota">{menu.rota}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
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
