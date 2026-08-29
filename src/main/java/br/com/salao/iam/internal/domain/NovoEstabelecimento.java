package br.com.salao.iam.internal.domain;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;

/**
 * RT-IAM-001 — os dados de um estabelecimento a provisionar, já validados.
 *
 * <p>Sem dependência de framework: é domínio puro, e {@code ArquiteturaTest} reprova o build se
 * alguém importar Spring aqui.
 *
 * <p>A validação acontece na construção, não em anotação de DTO. Um estabelecimento com fuso
 * inválido não deve existir nem por um instante — e a validação de borda protege só a borda,
 * enquanto o provisionamento também é chamado por script de operação e por teste.
 */
public record NovoEstabelecimento(
        String nome,
        String documento,
        ZoneId fuso,
        String moeda,
        BaseDeComissao baseComissao,
        boolean descontoAfetaComissao,
        PeriodicidadeDeFechamento periodicidadeDeFechamento) {

    public NovoEstabelecimento {
        if (nome == null || nome.isBlank()) {
            throw new DadosDoEstabelecimentoInvalidosException("nome é obrigatório");
        }
        if (nome.length() > 200) {
            throw new DadosDoEstabelecimentoInvalidosException("nome acima de 200 caracteres");
        }
        if (fuso == null) {
            throw new DadosDoEstabelecimentoInvalidosException("fuso é obrigatório");
        }
        if (moeda == null || moeda.length() != 3) {
            throw new DadosDoEstabelecimentoInvalidosException("moeda deve ter 3 letras (ISO 4217)");
        }
        try {
            Currency.getInstance(moeda);
        } catch (IllegalArgumentException e) {
            throw new DadosDoEstabelecimentoInvalidosException("moeda desconhecida: " + moeda);
        }
        if (baseComissao == null || periodicidadeDeFechamento == null) {
            throw new DadosDoEstabelecimentoInvalidosException(
                    "base de comissão e periodicidade são obrigatórias");
        }
    }

    /**
     * Constrói a partir do nome IANA do fuso.
     *
     * <p>Aceita só identificador IANA ({@code America/Sao_Paulo}), nunca offset fixo
     * ({@code -03:00}): offset não conhece horário de verão, e se o Brasil voltar a adotá-lo toda
     * a agenda desloca uma hora sem ninguém perceber.
     */
    public static NovoEstabelecimento comFuso(String nome, String documento, String fusoIana,
                                              String moeda, BaseDeComissao baseComissao,
                                              boolean descontoAfetaComissao,
                                              PeriodicidadeDeFechamento periodicidade) {
        return new NovoEstabelecimento(nome, documento, interpretarFuso(fusoIana), moeda,
                baseComissao, descontoAfetaComissao, periodicidade);
    }

    private static ZoneId interpretarFuso(String fusoIana) {
        if (fusoIana == null || fusoIana.isBlank()) {
            throw new DadosDoEstabelecimentoInvalidosException("fuso é obrigatório");
        }
        if (!fusoIana.contains("/")) {
            throw new DadosDoEstabelecimentoInvalidosException(
                    "informe um fuso IANA como America/Sao_Paulo, não um offset fixo: " + fusoIana);
        }
        try {
            return ZoneId.of(fusoIana);
        } catch (DateTimeException e) {
            throw new DadosDoEstabelecimentoInvalidosException("fuso desconhecido: " + fusoIana);
        }
    }
}
