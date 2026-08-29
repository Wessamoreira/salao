package br.com.salao.iam.api;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * RT-IAM-001 — contrato público do módulo {@code iam} para os demais módulos.
 *
 * <p>É por aqui que {@code agenda}, {@code atendimento} e {@code financeiro} descobrem o fuso e a
 * política de comissão do tenant. Nenhum deles consulta a tabela {@code estabelecimento}
 * diretamente: join entre módulos é proibido, e essa é justamente a fronteira que torna a
 * extração futura barata.
 */
public interface EstabelecimentoApi {

    Optional<ConfiguracaoDoEstabelecimento> configuracao(UUID estabelecimentoId);

    /**
     * O fuso do estabelecimento. Atalho porque é a consulta mais frequente — toda conversão de
     * borda precisa dele.
     *
     * @throws EstabelecimentoNaoEncontradoException se não existir ou estiver fora do escopo
     */
    ZoneId fusoDe(UUID estabelecimentoId);
}
