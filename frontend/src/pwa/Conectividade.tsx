import { useEffect, useRef, useState } from 'react';
import { useEstadoDaConexao } from './conectado';
import './conectividade.css';

/** Quanto tempo o "voltou" fica na tela. O suficiente para ser lido, e nada além. */
const AVISO_DE_VOLTA = 5_000;

/*
 * O diagnóstico muda; a consequência, não.
 *
 * Com a rede caída ou com o servidor mudo, o que está na tela continua na tela e nada é
 * salvo — é a mesma frase porque é a mesma verdade (R-UX-14). O que muda é de quem é o
 * problema, e é isso que decide se a pessoa vai mexer no roteador ou esperar.
 */
const TITULO = {
  'sem-internet': 'Sem internet',
  'servidor-fora': 'Sistema fora do ar',
} as const;

const CONSEQUENCIA = 'Dá para consultar; salvar, não.';

/**
 * O estado da conexão — do sistema, não da tela.
 *
 * <h2>Por que ele saiu do canto flutuante</h2>
 *
 * Era um retângulo fixo no canto inferior direito, por fora da casca, cobrindo o conteúdo:
 * a forma exata do aviso de cookie, que é a coisa que todo mundo aprendeu a ignorar (C5 do
 * checklist do front). Estado do sistema pertence à moldura do sistema — dentro da barra
 * de topo, ao lado do relógio, onde já se olha.
 *
 * Fora da casca (a porta do sistema, onde ainda não há moldura) ele continua flutuando,
 * porque ali não existe barra onde morar.
 *
 * <h2>Duas quedas, duas frases</h2>
 *
 * "Sem conexão" dito para quem está com o WhatsApp aberto ensina a não acreditar no aviso.
 * Internet caída e servidor fora do ar são problemas diferentes, com saídas diferentes, e
 * a tela agora sabe qual é qual.
 *
 * <h2>E o aviso de que voltou</h2>
 *
 * Sem ele, quem viu "sem internet" fica sem saber quando pode salvar de novo — e o jeito
 * de descobrir era tentar e ver se dava errado. Ele aparece por cinco segundos e some:
 * "está tudo bem" não é estado que mereça espaço permanente.
 */
export function Conectividade({ variante = 'flutuante', aoTentar }: {
  variante?: 'barra' | 'flutuante';
  /** Refazer as consultas da tela. Sem isso, não há botão — e não deve haver. */
  aoTentar?: () => void;
}) {
  const estado = useEstadoDaConexao();
  const [voltou, definirVoltou] = useState(false);
  const caiu = useRef(false);

  useEffect(() => {
    // Caindo, não há o que desligar: enquanto a conexão está ruim o render mostra a queda e
    // ignora este estado, e o aviso de volta que estivesse na tela já teve o prazo limpo pela
    // faxina do efeito anterior.
    if (estado !== 'conectado') {
      caiu.current = true;
      return;
    }
    // Só anuncia a volta para quem viu a queda: quem abriu o sistema com tudo funcionando
    // não precisa ser avisado de que está funcionando.
    if (!caiu.current) return;
    caiu.current = false;
    definirVoltou(true);
    const prazo = setTimeout(() => definirVoltou(false), AVISO_DE_VOLTA);
    return () => clearTimeout(prazo);
  }, [estado]);

  if (estado === 'conectado' && !voltou) return null;

  const classe = `conectividade ${variante}`;

  if (estado === 'conectado') {
    return (
      <div className={`${classe} voltou`} role="status" aria-live="polite">
        <strong className="conectividade-titulo">Conexão restabelecida</strong>
        <span className="conectividade-consequencia">Já dá para salvar de novo.</span>
      </div>
    );
  }

  return (
    <div className={classe} role="status" aria-live="polite">
      <span className="conectividade-ponto" aria-hidden="true" />
      <strong className="conectividade-titulo">{TITULO[estado]}</strong>
      <span className="conectividade-consequencia">{CONSEQUENCIA}</span>
      {/*
        Só quando o problema é o servidor, e só onde há o que refazer.
        Internet voltando, o navegador avisa sozinho e a tela se corrige; servidor voltando,
        ninguém avisa — o estado só muda quando alguma requisição der certo, e sem o botão a
        saída era recarregar a página. Na porta do sistema não há consulta nenhuma para
        refazer, então quem monta o aviso ali não passa `aoTentar`.
      */}
      {estado === 'servidor-fora' && aoTentar && (
        <button type="button" onClick={aoTentar}>Tentar de novo</button>
      )}
    </div>
  );
}
