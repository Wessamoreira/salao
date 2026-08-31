package br.com.salao.equipe.api;

/**
 * RT-EQP-001 — como o profissional se liga ao salão.
 *
 * <p>Não é detalhe burocrático: o vínculo decide qual documento é exigido, se há contrato escrito
 * a guardar, e — na Fase 2 — se o pagamento é comissão ou salário.
 */
public enum TipoDeVinculo {

    /**
     * Profissional-parceiro da Lei 13.352/2016.
     *
     * <p>É o arranjo mais comum em salão hoje. A lei exige que o parceiro seja pessoa jurídica —
     * daí o CNPJ obrigatório — e que exista <strong>contrato escrito</strong>, homologado pelo
     * sindicato ou assinado diante de duas testemunhas.
     *
     * <p>Operar a parceria sem esse contrato é o maior risco jurídico do modelo: sem ele, a
     * relação pode ser reclassificada como emprego, com todos os encargos retroativos.
     */
    PARCEIRO(true),

    /** Contratado por CLT. Documento é CPF; comissão, quando existe, é sobre o salário. */
    EMPREGADO(false),

    /**
     * Autônomo sem constituir empresa.
     *
     * <p>Existe porque é a realidade de muita gente que trabalha em salão, e fingir que não
     * existe empurraria o cadastro para o vínculo errado — que é pior que registrar o certo.
     */
    AUTONOMO(false);

    private final boolean exigePessoaJuridica;

    TipoDeVinculo(boolean exigePessoaJuridica) {
        this.exigePessoaJuridica = exigePessoaJuridica;
    }

    /** {@code true} quando o documento tem de ser CNPJ. */
    public boolean exigePessoaJuridica() {
        return exigePessoaJuridica;
    }

    /** Só a parceria depende de contrato escrito para se sustentar juridicamente. */
    public boolean exigeContratoEscrito() {
        return this == PARCEIRO;
    }
}
