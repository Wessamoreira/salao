import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import './estoque.css';

/**
 * RT-EST-009 — o leitor de código de barras.
 *
 * <h2>Um leitor HID é um teclado</h2>
 *
 * Ele não tem driver nem API: digita os caracteres do código muito rápido e termina com Enter.
 * Não há nada a "integrar" — há um campo que precisa estar **com o foco** no instante em que a
 * pessoa aperta o gatilho. Se não estiver, os caracteres vão para outro lugar: para um `input`
 * vizinho, para o atalho de uma tecla, ou para o nada. Ela bipa, nada acontece, e bipa de novo
 * mais forte. É o modo de falha real de conferência com leitor.
 *
 * <h2>Segurar o foco é opção de quem monta, não do componente</h2>
 *
 * Numa tela dedicada a bipar — inventário, entrada de nota — o campo deve reconquistar o foco
 * sozinho: perdê-lo é perder o bipe. Numa tela COMPARTILHADA, onde há selects e outros campos,
 * o mesmo comportamento tornaria a tela inutilizável: cada clique voltaria para cá.
 *
 * Daí `segurarFoco`, com padrão `false` — o padrão é o seguro. E o botão de pausar só existe
 * quando ele é `true`, porque só aí há foco sendo tomado de alguém.
 *
 * <h2>Sem detecção de velocidade de digitação</h2>
 *
 * O truque comum é medir o intervalo entre teclas para distinguir leitor de humano. Não serve:
 * este campo é dedicado a códigos, então quem digita à mão e aperta Enter quer exatamente o
 * mesmo efeito — e um limiar de milissegundos rejeitaria o teclado de quem tem o leitor
 * quebrado, justamente no dia em que ele quebrou.
 *
 * @param aoLer    recebe o código já aparado. O campo se limpa sozinho para o próximo bipe
 * @param ocupado  bloqueia enquanto a leitura anterior é resolvida, para não enfileirar bipes
 */
export function LeitorDeCodigo({
  aoLer,
  rotulo = 'Código de barras',
  ocupado = false,
  segurarFoco = false,
}: {
  aoLer: (codigo: string) => void;
  rotulo?: string;
  ocupado?: boolean;
  segurarFoco?: boolean;
}) {
  const [armado, setArmado] = useState(segurarFoco);
  const campo = useRef<HTMLInputElement>(null);
  // `useId` e não um id fixo: duas leitoras na mesma tela — bipar produto e bipar lote —
  // colidiriam, e o `label` de uma passaria a apontar para o campo da outra.
  const id = useId();

  // Enquanto armado o campo volta a ter o foco, inclusive quando `ocupado` acaba de liberá-lo.
  useEffect(() => {
    if (armado && !ocupado) campo.current?.focus();
  }, [armado, ocupado]);

  const aoTeclar = (evento: KeyboardEvent<HTMLInputElement>) => {
    if (evento.key !== 'Enter') return;
    // Impede o Enter de submeter o formulário em volta: numa tela compartilhada o leitor mora
    // dentro de um `form` que lançaria o item errado.
    evento.preventDefault();
    const codigo = evento.currentTarget.value.trim();
    // Enter com o campo vazio não é leitura. Muitos leitores emitem o terminador duas vezes.
    if (codigo.length === 0) return;
    evento.currentTarget.value = '';
    aoLer(codigo);
  };

  return (
    <div className={segurarFoco
      ? `est-leitor est-leitor-dedicado${armado ? ' est-leitor-armado' : ''}`
      : 'est-leitor'}>
      <label className="est-rotulo" htmlFor={id}>{rotulo}</label>
      <div className="est-leitor-linha">
        <input
          ref={campo}
          id={id}
          className="est-leitor-campo"
          type="text"
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          disabled={ocupado}
          placeholder={armado ? 'aguardando o bipe…' : 'bipe ou digite e aperte Enter'}
          onKeyDown={aoTeclar}
          onBlur={() => {
            // No quadro seguinte, não neste: devolver o foco de imediato impediria a pessoa de
            // clicar em qualquer outro controle da tela — inclusive no botão de pausar.
            if (armado) requestAnimationFrame(() => campo.current?.focus());
          }}
        />
        {segurarFoco && (
          <button className="est-leitor-pausa" type="button" aria-pressed={!armado}
            onClick={() => setArmado((estava) => !estava)}>
            {armado ? 'Pausar' : 'Retomar'}
          </button>
        )}
      </div>
      {/*
        O estado é dito em texto, e não só pela cor da borda: para quem bipou, "não aconteceu
        nada" e "não estava escutando" são a mesma experiência, e só a palavra os separa. Vale
        duas vezes para quem não distingue a cor.
      */}
      <p className="est-leitor-estado" role="status">
        {ocupado
          ? 'Procurando…'
          : armado
            ? 'Escutando o leitor. Bipe o produto.'
            : segurarFoco
              ? 'Pausado — use o teclado normalmente e retome quando quiser bipar.'
              : 'Clique aqui antes de bipar.'}
      </p>
    </div>
  );
}
