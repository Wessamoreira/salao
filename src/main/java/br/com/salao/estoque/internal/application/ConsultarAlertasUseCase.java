package br.com.salao.estoque.internal.application;

import br.com.salao.estoque.api.AlertasDeEstoque;
import br.com.salao.estoque.internal.infra.LotesJdbc;
import br.com.salao.iam.api.Permissao;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-EST-008 — o que precisa de atenção no estoque.
 *
 * <h2>Projeção, e não notificação — e isso é uma lacuna nomeada</h2>
 *
 * <p>O plano põe {@code RT-NOT-001} como dependência, o que sugere empurrar o alerta. Só que
 * {@code NotificacaoApi.enfileirar} exige um <strong>{@code clienteId}</strong>: aquele módulo foi
 * construído para falar com a cliente — consentimento, base legal, {@code ClienteApi}. Um alerta
 * de estoque vai para o <strong>dono</strong>.
 *
 * <p>E o sistema não tem como alcançar a própria equipe: {@code usuario} não tem telefone, e
 * inventar uma "cliente" que representa o salão para atravessar a fila de mensagens seria
 * contornar a LGPD pela porta de trás, num módulo cujo trabalho é justamente respeitá-la.
 *
 * <p>Então esta rotina entrega a <strong>consulta</strong>, que é o que a tela de estoque lê, e
 * registra a lacuna. Ela não é desta rotina: é a mesma família do {@code usuario} sem
 * {@code profissional_id}, e vai reaparecer em todo requisito de "avisar o salão" — a fila de
 * espera, o alerta de segurança, o repasse pronto.
 *
 * <h2>Leitura, não escrita</h2>
 *
 * <p>{@code estoque:read}: olhar o que está acabando não é mexer no estoque, e a recepção precisa
 * poder avisar o dono.
 */
public class ConsultarAlertasUseCase {

    /**
     * Trinta dias.
     *
     * <p>É o prazo em que dá para usar ou devolver ao fornecedor. Sete seria tarde para devolver e
     * noventa encheria a lista com o que ainda não é problema — e lista cheia não é lida.
     */
    public static final int DIAS_DE_AVISO_PADRAO = 30;

    private final LotesJdbc lotes;

    public ConsultarAlertasUseCase(LotesJdbc lotes) {
        this.lotes = lotes;
    }

    @PreAuthorize("hasAuthority('" + Permissao.ESTOQUE_LER + "')")
    @Transactional(readOnly = true)
    public AlertasDeEstoque executar(Integer diasDeAviso) {
        int dias = diasDeAviso == null || diasDeAviso < 0 ? DIAS_DE_AVISO_PADRAO : diasDeAviso;
        return new AlertasDeEstoque(dias, lotes.abaixoDoMinimo(), lotes.vencendoEm(dias));
    }
}
