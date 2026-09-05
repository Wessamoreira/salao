package br.com.salao.cliente.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-CLI-001 — contrato público do módulo {@code cliente}.
 *
 * <p>É por aqui que {@code agenda} e {@code atendimento} resolvem "quem é esta pessoa". Nenhum
 * deles consulta a tabela {@code cliente} diretamente: join entre módulos é proibido, e essa
 * fronteira é o que torna a extração futura barata.
 */
public interface ClienteApi {

    Optional<ClienteResumo> porId(UUID clienteId);

    /**
     * Confirma que o cliente existe e está ativo no tenant atual.
     *
     * <p>Existe separado de {@link #porId} porque agendar precisa validar o vínculo sem carregar
     * dado pessoal que não vai usar — e o que não é carregado não vaza em log.
     */
    boolean existe(UUID clienteId);

    /**
     * RT-CLI-004 — se o salão pode falar com esta pessoa para esta finalidade.
     *
     * <p>É a pergunta que o módulo {@code notificacao} faz antes de qualquer envio, e a única que
     * ele deve fazer: quem conhece base legal, consentimento e revogação é este módulo. Um
     * {@code if} sobre um campo booleano do outro lado seria a regra da LGPD escrita duas vezes,
     * e a segunda cópia é a que continuaria mandando promoção depois da revogação.
     *
     * <p>Falha fechada: cliente inexistente devolve {@code false}.
     */
    boolean podeReceber(UUID clienteId, Finalidade finalidade);

    /** O que vale hoje para cada finalidade — inclusive as que nunca foram perguntadas. */
    List<EstadoDoConsentimento> consentimentos(UUID clienteId);

    /**
     * RT-AGD-011 — os nomes de um punhado de clientes, numa consulta.
     *
     * <p>Existe para a grade da agenda, que escreve o nome na tarja de cada atendimento: buscar um
     * a um seria uma consulta por bloco para desenhar um dia.
     *
     * <p><strong>Só o nome</strong>, e é a diferença para {@link #porId}. A grade precisa escrever
     * algo na tarja e nada mais — telefone, e-mail e observação não têm o que fazer numa tela que
     * mostra trinta pessoas de uma vez, e o que não é carregado não vaza em log.
     *
     * <p>Cliente anonimizado devolve o rótulo de anonimizado, como em qualquer outra leitura: a
     * grade de dois meses atrás não é exceção à LGPD.
     */
    Map<UUID, String> nomesDe(Collection<UUID> clienteIds);
}
