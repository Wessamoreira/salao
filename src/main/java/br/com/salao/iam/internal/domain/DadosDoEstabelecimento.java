package br.com.salao.iam.internal.domain;

import br.com.salao.iam.api.BaseDeComissao;
import br.com.salao.iam.api.PeriodicidadeDeFechamento;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;

/**
 * RT-IAM-001 / RT-CFG-001 — os dados de um estabelecimento, já validados.
 *
 * <p>Chamava-se {@code NovoEstabelecimento} enquanto só existia provisionamento. Passou a servir
 * também a edição da configuração, e um nome que diz "novo" num objeto usado para alterar é a
 * espécie de mentira que faz alguém duplicar a validação em vez de reaproveitá-la.
 *
 * <p>Sem dependência de framework: é domínio puro, e {@code ArquiteturaTest} reprova o build se
 * alguém importar Spring aqui.
 *
 * <p>A validação acontece na construção, não em anotação de DTO. Um estabelecimento com fuso
 * inválido não deve existir nem por um instante — e a validação de borda protege só a borda,
 * enquanto o provisionamento também é chamado por script de operação e por teste.
 */
public record DadosDoEstabelecimento(
        String nome,
        String documento,
        ZoneId fuso,
        String moeda,
        BaseDeComissao baseComissao,
        boolean descontoAfetaComissao,
        PeriodicidadeDeFechamento periodicidadeDeFechamento,
        String dddPadrao) {

    public DadosDoEstabelecimento {
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
        // Normalizar ANTES de validar, e aqui: quem preenche escreve "(11)" ou "011", e a
        // limpeza morava no command do provisionamento — então a edição (RT-CFG-001), que
        // constrói este objeto direto, recusava "(11)". Regra de domínio no objeto de domínio.
        dddPadrao = dddPadrao == null ? null : dddPadrao.replaceAll("\\D", "");
        dddPadrao = dddPadrao == null || dddPadrao.isEmpty() ? null : dddPadrao;
        if (dddPadrao != null && !dddPadrao.matches("\\d{2}")) {
            // DDD brasileiro tem exatamente dois dígitos. Guardar "011" faria o telefone
            // normalizado nascer com um zero a mais e nunca completar a chamada.
            throw new DadosDoEstabelecimentoInvalidosException(
                    "DDD padrão deve ter 2 dígitos: " + dddPadrao);
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
    public static DadosDoEstabelecimento comFuso(String nome, String documento, String fusoIana,
                                              String moeda, BaseDeComissao baseComissao,
                                              boolean descontoAfetaComissao,
                                              PeriodicidadeDeFechamento periodicidade,
                                              String dddPadrao) {
        return new DadosDoEstabelecimento(nome, documento, interpretarFuso(fusoIana), moeda,
                baseComissao, descontoAfetaComissao, periodicidade, dddPadrao);
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
