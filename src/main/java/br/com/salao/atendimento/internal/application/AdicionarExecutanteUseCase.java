package br.com.salao.atendimento.internal.application;

import br.com.salao.atendimento.api.ErrosDoAtendimento;
import br.com.salao.atendimento.api.ComandaDetalhada;
import br.com.salao.atendimento.api.ComandaResumo;
import br.com.salao.atendimento.api.ExecutanteDoItem;
import br.com.salao.atendimento.api.ItemDaComanda;
import br.com.salao.financeiro.api.OrigemDaComissao;
import br.com.salao.atendimento.api.PapelDoExecutante;
import br.com.salao.atendimento.api.TipoDeItem;
import br.com.salao.atendimento.internal.infra.ComandasJdbc;
import br.com.salao.equipe.api.ProfissionalApi;
import br.com.salao.equipe.api.ProfissionalResumo;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-ATD-005 — o assistente que lava o cabelo entra na conta.
 *
 * <h2>Uma linha a mais no item, não um item a mais</h2>
 *
 * <p>É o que a pergunta 6 de {@code 13-perguntas-em-aberto} pedia, e o modelo já previa. A
 * alternativa — lançar "lavagem" como serviço separado — inventaria faturamento que não existe: a
 * cliente pagou uma coloração, não uma coloração e uma lavagem.
 *
 * <h2>O assistente NÃO precisa da habilidade</h2>
 *
 * <p>RN-ATD-007 exige que o executante principal execute o serviço. Aqui não: quem lava o cabelo
 * não está executando a coloração — está ajudando quem executa. Exigir a habilidade obrigaria o
 * salão a cadastrar toda assistente como colorista, o que faria a agenda passar a oferecê-la para
 * marcar coloração sozinha.
 *
 * <p>É a mesma pergunta com respostas diferentes porque os papéis são diferentes, e é exatamente
 * disso que o campo {@code papel} serve.
 *
 * <h2>O percentual é digitado, e a origem diz isso</h2>
 *
 * <p>Não sai da ficha do assistente: quem lava também pode ser cabeleireira, e o percentual dela
 * como cabeleireira não tem nada a ver com o que ela ganha por lavar. E não sai de configuração
 * porque a pergunta 6 continua sem resposta do dono — <em>qual é o percentual usual?</em>
 *
 * <p>Enquanto ela não tiver, quem responde é quem está no balcão, e {@code regra_origem} grava
 * {@code INFORMADA_NO_ITEM} para dizer isso em voz alta. Gravar {@code PROFISSIONAL} seria mentir
 * que o número veio de um cadastro.
 */
public class AdicionarExecutanteUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdicionarExecutanteUseCase.class);

    private static final BigDecimal CEM = new BigDecimal("100");

    private final ComandasJdbc comandas;
    private final ProfissionalApi profissionais;
    private final EstabelecimentoApi estabelecimentos;
    private final AuditoriaApi auditoria;

    public AdicionarExecutanteUseCase(ComandasJdbc comandas, ProfissionalApi profissionais,
                                      EstabelecimentoApi estabelecimentos,
                                      AuditoriaApi auditoria) {
        this.comandas = comandas;
        this.profissionais = profissionais;
        this.estabelecimentos = estabelecimentos;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAuthority('" + Permissao.COMANDA_ABRIR + "')")
    @Transactional
    public ComandaDetalhada executar(UUID comandaId, UUID itemId, UUID profissionalId,
                                     BigDecimal percentual) {
        UUID tenant = TenantContext.obrigatorio();
        ComandaResumo comanda = ComandaEmEdicao.exigirAberta(comandas, comandaId);
        ItemDaComanda item = itemDeServico(comandaId, itemId);
        percentualValido(percentual);
        cabeNoItem(item, percentual);

        ProfissionalResumo assistente = profissionais.porId(profissionalId)
                .filter(ProfissionalResumo::ativo)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoAtendimento.PROFISSIONAL_NAO_ENCONTRADO,
                        "profissional " + profissionalId + " não encontrado ou inativo"));
        if (item.executantes().stream().anyMatch(e -> e.profissionalId().equals(profissionalId))) {
            // O índice único também barraria, com uma mensagem de constraint que não diz nada a
            // quem chamou — e o caso comum é clicar duas vezes, não fraude.
            throw new ErroDeDominio(ErrosDoAtendimento.JA_ESTA_NO_ITEM,
                    assistente.comoChamar() + " já está neste item");
        }

        comandas.inserirExecutante(tenant, itemId, profissionalId, PapelDoExecutante.ASSISTENTE,
                percentual, OrigemDaComissao.INFORMADA_NO_ITEM);
        comandas.recalcularComissoesDoItem(itemId, estabelecimentos.configuracao(tenant)
                .orElseThrow().descontoAfetaComissao());

        auditoria.registrar(RegistroDeAuditoria.criacao("executante", itemId,
                Map.of("itemId", itemId.toString(), "profissionalId", profissionalId.toString(),
                        "papel", PapelDoExecutante.ASSISTENTE.name(),
                        "percentual", percentual.toPlainString())));
        log.info("Assistente {} ({}%) no item {} da comanda {}", profissionalId, percentual,
                itemId, comanda.numero());
        return new ComandaDetalhada(comandas.porId(comandaId).orElseThrow(),
                comandas.itensDe(comandaId));
    }

    private ItemDaComanda itemDeServico(UUID comandaId, UUID itemId) {
        ItemDaComanda item = ComandaEmEdicao.exigirItem(comandas, comandaId, itemId);
        if (item.tipo() != TipoDeItem.SERVICO) {
            throw new ErroDeDominio(ErrosDoAtendimento.ITEM_NAO_E_SERVICO,
                    "produto não tem quem o execute");
        }
        return item;
    }

    private void percentualValido(BigDecimal percentual) {
        if (percentual == null || percentual.signum() < 0
                || percentual.compareTo(CEM) > 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.PERCENTUAL_INVALIDO,
                    "percentual precisa estar entre 0 e 100; veio " + percentual);
        }
    }

    /**
     * A soma das comissões do item não passa de 100%.
     *
     * <p>Não é preciosismo contábil: acima disso o item paga mais em comissão do que rendeu, e o
     * jeito de chegar lá é digitar 90 onde se queria 9. O erro não aparece na comanda — ela fecha
     * normalmente — e só vira problema no fechamento do mês, quando o repasse não bate.
     */
    private void cabeNoItem(ItemDaComanda item, BigDecimal percentual) {
        BigDecimal jaComprometido = item.executantes().stream()
                .map(ExecutanteDoItem::percentualComissao)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (jaComprometido.add(percentual).compareTo(CEM) > 0) {
            throw new ErroDeDominio(ErrosDoAtendimento.COMISSAO_PASSA_DE_CEM,
                    "o item já compromete " + jaComprometido + "%; somando "
                            + percentual + "% passaria de 100%");
        }
    }
}
