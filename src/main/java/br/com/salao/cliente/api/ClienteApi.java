package br.com.salao.cliente.api;

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
}
