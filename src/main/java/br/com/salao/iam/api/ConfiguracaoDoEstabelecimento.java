package br.com.salao.iam.api;

import java.time.ZoneId;
import java.util.UUID;

/**
 * RT-IAM-001 — a configuração que todo módulo precisa conhecer do tenant em que está operando.
 *
 * <p>{@code fuso} é o campo mais consultado: converter instante em dia civil sem ele significa
 * usar o fuso da JVM, que roda em UTC (ADR-0009). "Agenda de hoje" calculada em UTC mostra o dia
 * errado depois das 21h no horário de Brasília.
 *
 * <p>{@code baseComissao} e {@code descontoAfetaComissao} respondem, por configuração, às
 * perguntas 1 e 2 de {@code 13-perguntas-em-aberto} — foi assim que elas deixaram de bloquear a
 * Fase 2.
 */
public record ConfiguracaoDoEstabelecimento(
        UUID id,
        String nome,
        ZoneId fuso,
        String moeda,
        BaseDeComissao baseComissao,
        boolean descontoAfetaComissao,
        PeriodicidadeDeFechamento periodicidadeDeFechamento,
        boolean ativo) {
}
