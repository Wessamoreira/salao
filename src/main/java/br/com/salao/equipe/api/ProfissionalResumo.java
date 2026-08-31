package br.com.salao.equipe.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * RT-EQP-001 — o profissional como os outros módulos precisam vê-lo.
 *
 * <p>{@code comissaoPercentual} vem em pontos percentuais ({@code 45.00} é 45%) e em
 * {@code BigDecimal}: meio ponto percentual sobre o faturamento de um ano é dinheiro de verdade.
 *
 * @param documento só dígitos; formatar é trabalho de quem exibe
 * @param contratoPendente {@code true} quando o vínculo exige contrato escrito e ele não foi
 *                         registrado — ver {@link TipoDeVinculo#PARCEIRO}
 */
public record ProfissionalResumo(
        UUID id,
        String nome,
        String apelido,
        String documento,
        TipoDeVinculo vinculo,
        String telefone,
        String email,
        BigDecimal comissaoPercentual,
        LocalDate contratoInicio,
        LocalDate contratoFim,
        LocalDate contratoAssinadoEm,
        boolean ativo,
        int versao) {

    /** Como a pessoa é chamada na agenda: o apelido cabe na coluna da grade, o nome nem sempre. */
    public String comoChamar() {
        return apelido == null || apelido.isBlank() ? nome : apelido;
    }

    /**
     * O risco jurídico que o sistema sabe apontar e o salão costuma não ver.
     *
     * <p>Parceria sem contrato escrito pode ser reclassificada como emprego, com encargos
     * retroativos. É a falha mais cara do modelo da Lei 13.352/2016, e a única pista que o
     * sistema tem é esta data em branco.
     */
    public boolean contratoPendente() {
        return vinculo.exigeContratoEscrito() && contratoAssinadoEm == null;
    }
}
