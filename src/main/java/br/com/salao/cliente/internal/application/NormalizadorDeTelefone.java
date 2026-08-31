package br.com.salao.cliente.internal.application;

import br.com.salao.cliente.api.ErrosDoCliente;
import br.com.salao.cliente.internal.domain.Telefone;
import br.com.salao.cliente.internal.domain.TelefoneInvalidoException;
import br.com.salao.iam.api.ConfiguracaoDoEstabelecimento;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RT-CLI-007 — a normalização de telefone que cadastro e edição compartilham.
 *
 * <p>Extraída de {@code CadastrarClienteUseCase} quando a edição precisou da mesma coisa. Duas
 * cópias divergiriam na primeira mudança de regra — e a divergência apareceria como um número que
 * entra certo pelo cadastro e errado pela correção, ou o contrário.
 */
class NormalizadorDeTelefone {

    private final EstabelecimentoApi estabelecimentos;

    NormalizadorDeTelefone(EstabelecimentoApi estabelecimentos) {
        this.estabelecimentos = estabelecimentos;
    }

    /**
     * A configuração vem por {@link EstabelecimentoApi}, nunca por join: é a fronteira de módulo
     * que torna a extração futura barata.
     */
    String normalizar(String informado, UUID tenant) {
        if (informado == null || informado.isBlank()) {
            return null;
        }
        ConfiguracaoDoEstabelecimento config = estabelecimentos.configuracao(tenant).orElseThrow();
        try {
            return Telefone.de(informado, config.paisPadrao(), config.dddPadrao()).e164();
        } catch (TelefoneInvalidoException e) {
            // O detalhe do domínio explica o caso concreto e vai para o campo, que é onde o front
            // consegue apontar o erro sem adivinhar.
            throw new ErroDeDominio(ErrosDoCliente.TELEFONE_INVALIDO, e.getMessage(), Map.of(),
                    List.of(new CampoInvalido("telefone", "INVALIDO", e.getMessage())));
        }
    }
}
