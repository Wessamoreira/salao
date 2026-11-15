import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { Cliente } from '../../api/clientes';
import { useBusca, useCliente } from '../../api/clientes';
import { Icone } from '../../layout/Icone';
import { Pagina } from '../../layout/Pagina';
import { useTermoComPausa } from '../../layout/pausa';
import { CadastroRapido } from './CadastroRapido';
import { Ficha } from './Ficha';
import { Isolado } from './Isolado';
import { Resultados } from './Resultados';
import './clientes.css';

/**
 * RT-CLI-006 — a tela de clientes.
 *
 * <h2>A busca É a tela</h2>
 *
 * O layout de CRUD — tabela no meio, campo de busca no canto — diria que o assunto
 * aqui é a lista. Não é, e o backend concorda: não existe endpoint que devolva todos
 * os clientes (RT-CLI-002). Sem termo, a resposta é vazia.
 *
 * Esta tela existe porque o telefone está tocando (R-UX-03). O campo de busca ocupa
 * o alto, em corpo grande, com o foco já nele quando a tela abre.
 */
export function Clientes() {
  const [termo, setTermo] = useState('');
  const [parametros, definirParametros] = useSearchParams();
  const campo = useRef<HTMLInputElement>(null);

  const termoBuscado = useTermoComPausa(termo);
  const { data: resultado, isFetching } = useBusca(termoBuscado);

  /*
   * A ficha aberta é endereço, e não estado.
   *
   * Porque ela passou a ter uma porta de fora: a busca da casca (⌘K) acha a cliente de
   * qualquer tela e abre a ficha dela aqui. Com a escolha guardada num `useState`, chegar
   * de lá não abriria nada — a tela já está montada, e um estado inicial só corre uma vez.
   *
   * O TERMO continua sendo estado: digitar não é ir a lugar nenhum, e escrever cada letra
   * no endereço faria o "voltar" do navegador percorrer a palavra de trás para frente.
   */
  const selecionadoId = parametros.get('cliente');
  const naLista = resultado?.itens.find((cliente) => cliente.id === selecionadoId);
  // Já veio na busca? A ficha abre na hora e se corrige sozinha; vindo da paleta, chega do
  // servidor — é o mesmo caminho que a tela já usava para não guardar uma segunda cópia.
  const { data: selecionado } = useCliente(selecionadoId, naLista);

  /*
   * Abrir uma ficha ENTRA no histórico — ao contrário das setas da agenda, que usam
   * `replace`. Andar de dia é percorrer a mesma tela; abrir uma pessoa é ir a outro lugar,
   * e o "voltar" do navegador (ou o gesto, no tablet do balcão) tem de fechar a ficha em
   * vez de largar a busca inteira.
   */
  const abrirFicha = (cliente: Cliente | null) => {
    const proximos = new URLSearchParams(parametros);
    if (cliente) proximos.set('cliente', cliente.id);
    else proximos.delete('cliente');
    definirParametros(proximos);
  };

  // O foco começa no campo: quem abre esta tela vai digitar, sempre.
  useEffect(() => { campo.current?.focus(); }, []);

  const naoAchou = termo.trim().length > 0 && resultado?.itens.length === 0 && !resultado.aviso;
  const esperando = termo.trim().length === 0;
  /*
   * Duas formas, e a tela troca de uma para a outra num clique.
   *
   * PROCURANDO: só existe a busca, então ela é a página — uma coluna centrada, com o campo,
   * a leitura do termo e os resultados na mesma largura. Antes, a coluna da ficha ficava
   * reservada e vazia desde o primeiro instante: setecentos pixels de nada ao lado de um campo
   * encolhido no canto, que é o que faz uma tela parecer inacabada em vez de focada.
   *
   * LENDO: abrir alguém muda o assunto da tela. Aí sim a ficha ocupa o espaço e a busca
   * encolhe para a coluna da esquerda, continuando à mão — outra cliente pode ligar no meio.
   */
  const lendo = selecionado != null;

  return (
    <Pagina>
    {/*
      RT-CLI-008 — duas colunas FIXAS: a lista à esquerda, a ficha à direita. Antes a busca
      saía do centro para a esquerda no clique, e a tela inteira mudava de lugar no momento em
      que a pessoa escolhia. Agora só o conteúdo da direita troca.
    */}
    <div className={`pagina-cheia cli-tela${lendo ? ' lendo' : ''}${esperando ? ' esperando' : ''}`}>
      <header className="cli-cabecalho">
        {/* A lupa dentro do campo diz "digite para procurar" sem gastar uma palavra — e é o
            mesmo desenho da busca da casca, para as duas serem reconhecidas como a mesma
            coisa. */}
        <div className="cli-busca-caixa">
          <Icone nome="busca" tamanho={20} />
          <input
            ref={campo}
            className="cli-busca"
            type="search"
            aria-label="Buscar cliente"
            placeholder="Nome, apelido ou telefone"
            value={termo}
            onChange={(e) => setTermo(e.target.value)}
            onKeyDown={(e) => {
              // Esc limpa e devolve a tela ao estado de espera, sem tirar a mão do
              // teclado (R-UX-02).
              if (e.key === 'Escape') { setTermo(''); abrirFicha(null); }
            }}
          />
        </div>
        <Leitura resultado={resultado} temTermo={termo.trim().length > 0} />
      </header>

      <div className="cli-painel cli-painel-lista">
        <Resultados
          resultado={resultado}
          termo={termo}
          carregando={isFetching}
          selecionadoId={selecionado?.id ?? null}
          aoSelecionar={abrirFicha}
        />
      </div>

      <div className="cli-painel cli-painel-ficha">
        {naoAchou ? (
          // Sem resultado, a coluna da ficha É o cadastro: ao lado do aviso, e não embaixo
          // dele, a uma rolagem de distância.
          <div className="cli-ficha">
            <CadastroRapido
              termoInicial={termo.trim()}
              ehTelefone={resultado?.interpretacao === 'TELEFONE'}
              aoCadastrar={() => setTermo(termo)}
            />
          </div>
        ) : selecionado ? (
          <Isolado key={selecionado.id}>
            <Ficha cliente={selecionado} aoSair={() => abrirFicha(null)} />
          </Isolado>
        ) : (
          <div className="cli-sem-ficha">
            <Icone nome="pessoas" tamanho={22} />
            <p>
              {esperando
                ? 'A ficha de quem você procurar aparece aqui.'
                : 'Escolha alguém na lista para ver a ficha.'}
            </p>
          </div>
        )}
      </div>
    </div>
    </Pagina>
  );
}

/**
 * O que o servidor entendeu do termo, escrito na tela.
 *
 * É a peça que ensina a única regra que a recepção precisa saber — que dígito
 * significa telefone — sem manual e sem seletor. E o texto vem da resposta, nunca
 * de um palpite daqui: reimplementar RN-CLI-009 em TypeScript criaria uma cópia
 * que um dia diria uma coisa enquanto a consulta fazia outra.
 */
function Leitura({
  resultado,
  temTermo,
}: {
  resultado: { interpretacao: string; aviso: string | null; itens: unknown[] } | undefined;
  temTermo: boolean;
}) {
  if (!temTermo || !resultado) return <p className="cli-leitura" />;

  if (resultado.interpretacao === 'INSUFICIENTE') {
    return <p className="cli-leitura">{resultado.aviso}</p>;
  }

  const comoBuscou =
    resultado.interpretacao === 'TELEFONE'
      ? 'pelos últimos dígitos do telefone'
      : 'por nome e apelido';

  return (
    <p className="cli-leitura">
      <span className="cli-leitura-chave">
        {resultado.interpretacao === 'TELEFONE' ? 'telefone' : 'nome'}
      </span>
      <span>procurando {comoBuscou}</span>
      <span className="cli-leitura-sep">·</span>
      <span>{contar(resultado.itens.length)}</span>
    </p>
  );
}

function contar(quantos: number): string {
  if (quantos === 0) return 'nenhuma ficha';
  return quantos === 1 ? '1 ficha' : `${quantos} fichas`;
}

