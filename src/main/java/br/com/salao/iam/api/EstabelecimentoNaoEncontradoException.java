package br.com.salao.iam.api;

import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.erro.ErrosDaInfra;
import java.util.UUID;

/**
 * RT-IAM-001. Usa {@code ER-INF-NAO_ENCONTRADO} de propósito: "não existe" e "existe em outro
 * estabelecimento" precisam ser indistinguíveis para quem chama.
 */
public class EstabelecimentoNaoEncontradoException extends ErroDeDominio {

    public EstabelecimentoNaoEncontradoException(UUID id) {
        super(ErrosDaInfra.NAO_ENCONTRADO, "Estabelecimento não encontrado: " + id);
    }
}
