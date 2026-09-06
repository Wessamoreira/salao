package br.com.salao.atendimento.internal.application;

import br.com.salao.agenda.api.AgendaApi;
import br.com.salao.agenda.api.AgendamentoResumo;
import br.com.salao.agenda.api.StatusAgendamento;
import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.OrigemDaComissao;
import br.com.salao.atendimento.api.PapelDoExecutante;
import br.com.salao.atendimento.api.StatusDaComanda;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.catalogo.api.ServicoApi;
import br.com.salao.catalogo.api.ServicoResumo;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.dinheiro.Money;
import br.com.salao.shared.erro.CodigoDeErro;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-002 — lançar um serviço na comanda.
 *
 * <h2>O que é gravado é snapshot, não referência</h2>
 *
 * <p>Descrição e preço são copiados do catálogo <strong>agora</strong> (RN-ATD-010). Uma linha que
 * apontasse para o serviço daria resposta diferente depois que o preço mudasse — e reprocessar um
 * fechamento de três meses atrás produziria número diferente do repasse já pago, sem que ninguém
 * conseguisse explicar a diferença ao profissional.
 *
 * <p>O mesmo vale para o percentual de comissão do executante, e por isso {@code regra_origem}
 * também é gravada: a pergunta que chega depois não é "quanto eu ganho", é
 * <em>"por que a minha comissão foi 35% e não 40%"</em>.
 *
 * <h2>Serviço sem preço é recusado, não vale zero</h2>
 *
 * <p>{@code ServicoApi.precoEm} devolve vazio quando não há preço definido, e o contrato dele já
 * diz por quê: zero é um preço, e um serviço sem preço num relatório financeiro precisa aparecer
 * como pendência de cadastro, não como brinde. Lançar zero aqui transformaria a falha de cadastro
 * em faturamento perdido silencioso.
 */
public class AdicionarItemDeServicoUseCase {

    private static final Logger log =
            LoggerFactory.getLogger(AdicionarItemDeServicoUseCase.class);

    private static final BigDecimal CEM = new BigDecimal("100");

    public enum Erros implements CodigoDeErro {
        COMANDA_NAO_ENCONTRADA("ER-ATD-COMANDA_NAO_ENCONTRADA", HttpStatus.NOT_FOUND,
                "Comanda não encontrada"),
        COMANDA_NAO_ESTA_ABERTA("ER-ATD-COMANDA_NAO_ESTA_ABERTA", HttpStatus.CONFLICT,
                "Esta comanda não está aberta"),
        SERVICO_NAO_ENCONTRADO("ER-ATD-SERVICO_NAO_ENCONTRADO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Serviço não encontrado"),
        SERVICO_FORA_DO_CARDAPIO("ER-ATD-SERVICO_FORA_DO_CARDAPIO",
                HttpStatus.UNPROCESSABLE_ENTITY, "Este serviço não está mais no cardápio"),
        SERVICO_SEM_PRECO("ER-ATD-SERVICO_SEM_PRECO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Este serviço não tem preço definido"),
        PROFISSIONAL_NAO_ENCONTRADO("ER-ATD-PROFISSIONAL_NAO_ENCONTRADO",
                HttpStatus.UNPROCESSABLE_ENTITY, "Profissional não encontrado ou inativo"),
        NAO_EXECUTA_O_SERVICO("ER-ATD-NAO_EXECUTA_O_SERVICO", HttpStatus.UNPROCESSABLE_ENTITY,
                "Este profissional não executa este serviço"),
        QUANTIDADE_INVALIDA("ER-ATD-QUANTIDADE_INVALIDA", HttpStatus.UNPROCESSABLE_ENTITY,
                "A quantidade precisa ser maior que zero"),
        AGENDAMENTO_NAO_ENCONTRADO("ER-ATD-AGENDAMENTO_NAO_ENCONTRADO", HttpStatus.NOT_FOUND,
                "Agendamento não encontrado"),
        AGENDAMENTO_DE_OUTRA_CLIENTE("ER-ATD-AGENDAMENTO_DE_OUTRA_CLIENTE",
                HttpStatus.UNPROCESSABLE_ENTITY, "Este horário é de outra cliente");

        private final String codigo;
        private final HttpStatus status;
        private final String titulo;

        Erros(String codigo, HttpStatus status, String titulo) {
            this.codigo = codigo;
            this.status = status;
            this.titulo = titulo;
        }

        @Override public String codigo() { return codigo; }
        @Override public HttpStatus status() { return status; }
        @Override public String titulo() { return titulo; }
    }

    private final ComandasJdbc comandas;
    private final ServicoApi servicos;
    private final ProfissionalApi profissionais;
    private final AgendaApi agenda;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;
    private final Relogio relogio;

    public AdicionarItemDeServicoUseCase(ComandasJdbc comandas, ServicoApi servicos,
                                         ProfissionalApi profissionais, AgendaApi agenda,
                                         EstabelecimentoApi estabelecimentos,
                                         AuditoriaApi auditoria, Relogio relogio) {
        this.comandas = comandas;
        this.servicos = servicos;
        this.profissionais = profissionais;
        this.agenda = agenda;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /**
     * @param quantidade    nula vira 1 — o caso normal é um serviço, uma vez
     * @param agendamentoId opcional; quando informado, precisa ser da mesma cliente da comanda
     */
    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, UUID servicoId, UUID profissionalId,
                                     BigDecimal quantidade, UUID agendamentoId) {
        UUID tenant = TenantContext.obrigatorio();
        ComandaResumo comanda = comandaAberta(comandaId);
        BigDecimal quantas = quantidadeValida(quantidade);

        ServicoResumo servico = servicos.porId(servicoId)
                .orElseThrow(() -> new ErroDeDominio(Erros.SERVICO_NAO_ENCONTRADO,
                        "serviço " + servicoId + " não encontrado"));
        if (!servico.ativo()) {
            // Fora do cardápio é decisão do salão de não vender mais aquilo. Aceitar aqui faria a
            // desativação valer só para quem marca horário, e não para quem cobra.
            throw new ErroDeDominio(Erros.SERVICO_FORA_DO_CARDAPIO,
                    "o serviço " + servico.nome() + " está desativado");
        }

        Money precoUnitario = servicos
                .precoEm(servicoId, relogio.hojeEm(estabelecimentos.fusoDe(tenant)))
                .orElseThrow(() -> new ErroDeDominio(Erros.SERVICO_SEM_PRECO,
                        "o serviço " + servico.nome() + " não tem preço definido para hoje"));

        ProfissionalResumo executante = profissionalQueExecuta(profissionalId, servicoId,
                servico.nome());
        UUID horario = horarioDaMesmaCliente(agendamentoId, comanda);

        Money valorTotal = precoUnitario.multiplicar(quantas);
        UUID itemId = comandas.inserirItemDeServico(tenant, comandaId, servicoId, horario,
                servico.nome(), quantas, precoUnitario, valorTotal, relogio.agora());

        BigDecimal percentual = executante.comissaoPercentual();
        comandas.inserirExecutante(tenant, itemId, profissionalId, PapelDoExecutante.PRINCIPAL,
                percentual, OrigemDaComissao.PROFISSIONAL, valorTotal,
                valorTotal.percentual(percentual.divide(CEM)));
        comandas.recalcularTotais(comandaId);

        auditoria.registrar(RegistroDeAuditoria.criacao("item_comanda", itemId,
                Map.of("comandaId", comandaId.toString(), "descricao", servico.nome(),
                        "valorTotal", valorTotal.toString(),
                        "profissionalId", profissionalId.toString())));
        log.info("Item {} ({}) lançado na comanda {} por {}", itemId, servico.nome(), comandaId,
                profissionalId);
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }

    private ComandaResumo comandaAberta(UUID comandaId) {
        ComandaResumo comanda = comandas.porId(comandaId)
                .orElseThrow(() -> new ErroDeDominio(Erros.COMANDA_NAO_ENCONTRADA,
                        "comanda " + comandaId + " não encontrada"));
        if (comanda.status() != StatusDaComanda.ABERTA) {
            throw new ErroDeDominio(Erros.COMANDA_NAO_ESTA_ABERTA,
                    "a comanda " + comanda.numero() + " está " + comanda.status());
        }
        return comanda;
    }

    private BigDecimal quantidadeValida(BigDecimal quantidade) {
        BigDecimal quantas = quantidade == null ? BigDecimal.ONE : quantidade;
        if (quantas.signum() <= 0) {
            throw new ErroDeDominio(Erros.QUANTIDADE_INVALIDA,
                    "quantidade informada: " + quantas);
        }
        return quantas;
    }

    /**
     * Quem executa precisa ter a habilidade — a mesma exigência de RN-AGD-001.
     *
     * <p>Deixar passar produziria comissão para alguém cuja regra para aquele serviço nunca foi
     * configurada, e o número apareceria certo na tela e errado no repasse. Quando o salão põe
     * alguém para substituir, a habilidade é o cadastro que precisava mesmo ser corrigido.
     */
    private ProfissionalResumo profissionalQueExecuta(UUID profissionalId, UUID servicoId,
                                                      String nomeDoServico) {
        ProfissionalResumo profissional = profissionais.porId(profissionalId)
                .filter(ProfissionalResumo::ativo)
                .orElseThrow(() -> new ErroDeDominio(Erros.PROFISSIONAL_NAO_ENCONTRADO,
                        "profissional " + profissionalId + " não encontrado ou inativo"));
        if (!profissionais.executa(profissionalId, servicoId)) {
            throw new ErroDeDominio(Erros.NAO_EXECUTA_O_SERVICO,
                    profissional.comoChamar() + " não executa " + nomeDoServico);
        }
        return profissional;
    }

    /**
     * O horário de onde veio o item, conferido contra a cliente da comanda.
     *
     * <p>Sem a conferência, o item da Maria poderia apontar para o horário da Joana — e o relatório
     * de "quanto rende cada horário marcado" ficaria errado sem nada acusar.
     */
    private UUID horarioDaMesmaCliente(UUID agendamentoId, ComandaResumo comanda) {
        if (agendamentoId == null) {
            return null;
        }
        AgendamentoResumo horario = agenda.porId(agendamentoId)
                .orElseThrow(() -> new ErroDeDominio(Erros.AGENDAMENTO_NAO_ENCONTRADO,
                        "agendamento " + agendamentoId + " não encontrado"));
        if (!horario.clienteId().equals(comanda.clienteId())) {
            throw new ErroDeDominio(Erros.AGENDAMENTO_DE_OUTRA_CLIENTE,
                    "o horário é de outra cliente");
        }
        if (horario.status() == StatusAgendamento.CANCELADO) {
            throw new ErroDeDominio(Erros.AGENDAMENTO_NAO_ENCONTRADO,
                    "o horário foi cancelado");
        }
        return agendamentoId;
    }
}
