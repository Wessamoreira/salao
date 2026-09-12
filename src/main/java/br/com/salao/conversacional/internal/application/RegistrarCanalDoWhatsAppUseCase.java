package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.ErrosDoConversacional;
import br.com.salao.conversacional.internal.infra.CanaisJdbc;
import br.com.salao.iam.api.AuditoriaApi;
import br.com.salao.iam.api.Permissao;
import br.com.salao.iam.api.RegistroDeAuditoria;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tenant.TenantContext;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-001 — liga o número do salão ao webhook.
 *
 * <h2>Por que isto existe nesta rotina</h2>
 *
 * <p>Sem o cadastro, o webhook não tem como rotear nada: toda mensagem chegaria "sem dono" e
 * seria descartada. Entregar a validação de assinatura sem o cadastro seria entregar uma porta
 * sem endereço — e obrigaria quem implanta a escrever {@code INSERT} à mão no banco de produção.
 *
 * <h2>O que NÃO fica aqui</h2>
 *
 * <p>O segredo do app e o token de verificação. Eles são do <strong>app da Meta</strong>, não do
 * salão: um app serve todos os números, e a assinatura é a mesma para todos. Guardá-los por
 * estabelecimento sugeriria que cada salão tem o seu, e o primeiro salão a trocar o valor
 * quebraria o webhook de todos. Vivem em variável de ambiente, fora do banco.
 */
public class RegistrarCanalDoWhatsAppUseCase {

    /** Os ids da Cloud API são numéricos e longos; o limite é generoso de propósito. */
    private static final int MAXIMO = 64;

    /**
     * E.164: 8 a 15 dígitos, com {@code +} opcional.
     *
     * <p>O número guardado é E.164 <strong>sem formatação</strong>, como {@code cliente.telefone}
     * e como o {@code display_phone_number} que a própria Meta manda. Quem formata é a tela — o
     * front já tem componente para isso.
     *
     * <p>Aceitar "+55 11 99999-0000" aqui pareceria gentileza e criaria dois formatos do mesmo
     * dado no banco, com a comparação entre eles falhando em silêncio no dia em que alguém
     * precisar casar este número com o de um cliente.
     */
    private static final Pattern E164 = Pattern.compile("\\+?[1-9][0-9]{7,14}");

    private final CanaisJdbc canais;
    private final AuditoriaApi auditoria;

    public RegistrarCanalDoWhatsAppUseCase(CanaisJdbc canais, AuditoriaApi auditoria) {
        this.canais = canais;
        this.auditoria = auditoria;
    }

    /**
     * @param phoneNumberId  o id do número na Cloud API — a chave que chega no webhook
     * @param numeroExibicao o número em E.164 ({@code 5511999990000}), só para a tela
     * @param ativo          {@code false} desliga o canal sem apagar o cadastro
     */
    @PreAuthorize("hasAuthority('" + Permissao.CONFIG_GERENCIAR + "')")
    @Transactional
    public void executar(String phoneNumberId, String numeroExibicao, boolean ativo) {
        String numero = exigir(phoneNumberId, "o phoneNumberId");
        String exibicao = exigirE164(numeroExibicao);

        canais.registrar(TenantContext.obrigatorio(), numero, exibicao, ativo);

        // Sem `entidadeId`: a chave do canal é texto, não UUID. O que importa na trilha é qual
        // número passou a receber por este salão, e ele vai em `depois`.
        auditoria.registrar(RegistroDeAuditoria.alteracao("CANAL_WHATSAPP_REGISTRADO",
                "canal_whatsapp", null, null,
                Map.of("phoneNumberId", numero, "numero", exibicao, "ativo", ativo)));
    }

    /**
     * Apara e recusa vazio e espaço no meio.
     *
     * <p>Espaço no meio de um {@code phone_number_id} é sempre cola torta, e o efeito seria um
     * cadastro que nunca casa com bipe nenhum do webhook — indistinguível a olho do certo. Mesmo
     * raciocínio de RN-EST-026 para o código de barras.
     */
    private String exigir(String valor, String qual) {
        String limpo = valor == null ? "" : valor.strip();
        if (limpo.isEmpty() || limpo.length() > MAXIMO || limpo.matches(".*\\s.*")) {
            throw new ErroDeDominio(ErrosDoConversacional.DADOS_INVALIDOS,
                    qual + " precisa vir sem espaços e com até " + MAXIMO + " caracteres");
        }
        return limpo;
    }

    private String exigirE164(String valor) {
        String limpo = valor == null ? "" : valor.strip();
        if (!E164.matcher(limpo).matches()) {
            throw new ErroDeDominio(ErrosDoConversacional.DADOS_INVALIDOS,
                    "o número precisa estar em E.164, só dígitos, como 5511999990000; veio \""
                            + limpo + "\"");
        }
        return limpo;
    }
}
