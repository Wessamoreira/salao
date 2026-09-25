package br.com.salao.equipe.internal.application;

import br.com.salao.equipe.api.ErrosDaEquipe;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.shared.erro.CampoInvalido;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.telefone.Telefone;
import br.com.salao.shared.telefone.TelefoneInvalidoException;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Os campos comuns da ficha do profissional, validados num lugar só.
 *
 * <h2>Por que não é um caso de uso</h2>
 *
 * Porque não é um ato do negócio: é o que cadastrar (RT-EQP-001) e editar (RT-EQP-005) fazem
 * igual. A primeira versão da edição copiou os três métodos do cadastro e já saiu divergindo —
 * a mesma recusa de comissão com dois códigos de campo diferentes, {@code FORA_DA_FAIXA} e
 * {@code FORA_DO_INTERVALO}, que é o front tendo de conhecer dois nomes para a mesma coisa.
 */
class CamposDaFicha {

    private final EstabelecimentoApi estabelecimentos;

    CamposDaFicha(EstabelecimentoApi estabelecimentos) {
        this.estabelecimentos = estabelecimentos;
    }

    /** Espaços das pontas e duplicados no meio fora; vazio vira nulo. */
    String texto(String bruto) {
        if (bruto == null) {
            return null;
        }
        String limpo = bruto.strip().replaceAll("\\s{2,}", " ");
        return limpo.isEmpty() ? null : limpo;
    }

    String nomeObrigatorio(String bruto) {
        String nome = texto(bruto);
        if (nome == null) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS, "o nome é obrigatório",
                    Map.of(), List.of(new CampoInvalido("nome", "OBRIGATORIO", "Informe o nome")));
        }
        return nome;
    }

    /**
     * Zero é uma comissão — a de quem trabalha por salário fixo. "Ninguém configurou" é outra
     * coisa, e é nulo: quem lê decide usar a cascata do serviço (RT-FIN-002).
     */
    BigDecimal comissao(BigDecimal informada) {
        if (informada == null) {
            return null;
        }
        if (informada.signum() < 0 || informada.compareTo(new BigDecimal("100")) > 0) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS,
                    "a comissão precisa estar entre 0 e 100", Map.of(),
                    List.of(new CampoInvalido("comissaoPercentual", "FORA_DA_FAIXA",
                            "Informe um percentual entre 0 e 100")));
        }
        return informada;
    }

    /** RN-EQP-016 — guardado em E.164, com o país e o DDD padrão do salão preenchendo o resto. */
    String telefone(String informado) {
        String telefone = texto(informado);
        if (telefone == null) {
            return null;
        }
        var configuracao = estabelecimentos.configuracao(TenantContext.obrigatorio()).orElseThrow();
        try {
            return Telefone.de(telefone, configuracao.paisPadrao(), configuracao.dddPadrao()).e164();
        } catch (TelefoneInvalidoException e) {
            throw new ErroDeDominio(ErrosDaEquipe.DADOS_INVALIDOS, "telefone inválido", Map.of(),
                    List.of(new CampoInvalido("telefone", "INVALIDO", "Telefone inválido")));
        }
    }
}
