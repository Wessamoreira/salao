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
 * <p>{@code paisPadrao} e {@code dddPadrao} existem para o cadastro de cliente (R-UX-15): quem
 * atende o telefone anota "98765-4321" e não o DDD, porque ele é óbvio para quem está ali.
 * Obrigar a digitá-lo é fricção que faz a recepção inventar dado.
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
        String paisPadrao,
        String dddPadrao,
        /**
         * RT-AGD-001 — de quantos em quantos minutos a agenda oferece horário.
         *
         * <p>É do salão porque uns marcam de 15 em 15, outros de 30, e barbearia costuma
         * trabalhar de 10. Um número fixo no código estaria errado para metade deles — e o
         * efeito não seria um erro, seria a agenda oferecendo horários que aquele salão não usa.
         */
        int granularidadeAgendaMinutos,
        boolean ativo) {
}
