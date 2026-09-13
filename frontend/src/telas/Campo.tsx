import { useId, useState, type InputHTMLAttributes } from 'react';

/**
 * O campo das telas de entrada.
 *
 * <h2>Por que `autoComplete` importa mais que o estilo</h2>
 *
 * Sem `name`, `id` e `autoComplete`, nenhum gerenciador de senha preenche — e a recepção
 * digita e-mail e senha inteiros toda manhã, com o telefone no ombro. Não é acabamento
 * visual: é o item da tela que mais custa tempo por dia.
 *
 * <h2>Rótulo em caixa normal</h2>
 *
 * O rótulo em versalete de 11px sobre campo de 16px é o formulário de dez anos atrás:
 * hierarquia invertida, com a etiqueta gritando mais que o dado. Aqui o rótulo é
 * pequeno, escuro e discreto, e quem manda é o que está sendo digitado.
 */
export function Campo({ rotulo, dica, ...resto }: {
  rotulo: string;
  dica?: string;
} & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  const idDaDica = dica ? `${id}-dica` : undefined;
  const ehSenha = resto.type === 'password';
  const [revelada, definirRevelada] = useState(false);
  const [capsLigado, definirCapsLigado] = useState(false);

  return (
    <div className="auth-campo">
      <label htmlFor={id}>{rotulo}</label>
      <div className="auth-campo-caixa">
        <input
          {...resto}
          id={id}
          type={ehSenha && revelada ? 'text' : resto.type}
          aria-describedby={idDaDica}
          onKeyUp={(evento) => {
            // Só faz sentido na senha: é o único campo onde o erro fica invisível, e o
            // balcão é máquina compartilhada, onde a tecla fica ligada de ontem.
            if (ehSenha) definirCapsLigado(evento.getModifierState('CapsLock'));
            resto.onKeyUp?.(evento);
          }}
        />
        {/* Só aparece quando há senha digitada: um controle que não faz nada ainda é
            ruído no único lugar da tela onde a pessoa está olhando. */}
        {ehSenha && String(resto.value ?? '').length > 0 && (
          <button className="auth-revelar" type="button"
            aria-pressed={revelada}
            onClick={() => definirRevelada((atual) => !atual)}>
            {revelada ? 'Ocultar' : 'Mostrar'}
          </button>
        )}
      </div>
      {capsLigado && (
        <p className="auth-caps" role="status">Caps Lock está ligado.</p>
      )}
      {dica && <p id={idDaDica} className="auth-dica">{dica}</p>}
    </div>
  );
}
