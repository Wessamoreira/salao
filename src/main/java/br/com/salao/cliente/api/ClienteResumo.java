package br.com.salao.cliente.api;

import java.util.UUID;

/**
 * RT-CLI-001 — o cliente como os outros módulos precisam vê-lo.
 *
 * <p>É deliberadamente <strong>magro</strong>: {@code agenda} e {@code atendimento} precisam de
 * quem é e como falar com a pessoa, não da ficha inteira. A ficha técnica (química) é dado de
 * saúde e exige {@code cliente:ficha:read} — expô-la aqui a quem só precisa montar um agendamento
 * seria vazamento por conveniência.
 *
 * @param telefone em E.164 sem {@code +}, só dígitos. Formatar é trabalho do front.
 * @param versao   contador de alterações, devolvido para ser reapresentado na edição
 *                 (RT-CLI-007). Sem ele, duas pessoas corrigindo a mesma ficha ao mesmo
 *                 tempo fariam a segunda apagar a correção da primeira, em silêncio.
 */
public record ClienteResumo(
        UUID id,
        String nome,
        String apelido,
        String telefone,
        String email,
        int versao) {

    /** Como chamar a pessoa na tela: o apelido é o que a recepção usa de fato. */
    public String comoChamar() {
        return apelido == null || apelido.isBlank() ? nome : apelido;
    }
}
