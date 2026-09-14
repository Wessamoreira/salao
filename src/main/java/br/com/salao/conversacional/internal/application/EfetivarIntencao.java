package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.api.ErrosDoConversacional;
import br.com.salao.conversacional.internal.domain.BotaoDeAceite;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc;
import br.com.salao.conversacional.internal.infra.IntencoesJdbc.Intencao;
import br.com.salao.conversacional.internal.infra.MensagensParaEquipeJdbc;
import br.com.salao.iam.api.EstabelecimentoApi;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.iam.api.UsuarioEfetivoApi;
import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.agente.FerramentaDeEscrita.AceiteRegistrado;
import br.com.salao.shared.erro.ErroDeDominio;
import br.com.salao.shared.tempo.Relogio;
import br.com.salao.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/**
 * RT-CNV-005 / RT-CNV-006 — executar o que foi confirmado, e só isso. A única classe que chama
 * {@code FerramentaDeEscrita.confirmar} ({@code ArquiteturaTest}).
 *
 * <h2>Um commit para as duas coisas — RN-CNV-016</h2>
 *
 * <p>A intenção muda de estado na MESMA transação em que a ferramenta grava. É o que dá "no máximo uma
 * vez" sem chave de idempotência: se o processo cair entre a gravação e a marcação, não houve gravação;
 * e duas decisões da mesma intenção se enfileiram na trava da linha — a segunda encontra o estado novo e
 * não grava de novo.
 *
 * <h2>As trancas de novo, na hora de gravar — RN-CNV-017</h2>
 *
 * <p>Nada do momento da proposta autoriza a gravação. O usuário efetivo é resolvido AGORA
 * ({@code emNomeDe}), precisa ser o mesmo que propôs, precisa ter as permissões que a ferramenta
 * declara, e o {@code @PreAuthorize} do caso de uso confere por último.
 *
 * <h2>Quando quem vai atender precisa aceitar — RN-CNV-018</h2>
 *
 * <p>O SIM de quem pediu não grava: passa a intenção a {@code AGUARDANDO_ACEITE} e enfileira, no mesmo
 * commit, o pedido com botões a quem vai atender. O toque "Aceitar" grava — em nome de QUEM PEDIU,
 * resolvido de novo, porque aceitar o horário não dá a ninguém permissão de marcar. E a gravação só força
 * a jornada com a prova do aceite.
 *
 * <p>Recusa sai como exceção e desfaz a transação inteira. Registrá-la é feito numa transação própria
 * ({@link #registrarFalhaDoAceite}, ou por {@link ConfirmarPeloCodigo}) — registrada aqui dentro, seria
 * desfeita junto (a lição de RN-ARQ-003).
 */
public class EfetivarIntencao {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final IntencoesJdbc intencoes;
    private final CatalogoDeFerramentas catalogo;
    private final UsuarioEfetivoApi usuarios;
    private final MensagensParaEquipeJdbc saida;
    private final EstabelecimentoApi estabelecimentos;
    private final Relogio relogio;
    private final Duration validadeDoAceite;

    public EfetivarIntencao(IntencoesJdbc intencoes, CatalogoDeFerramentas catalogo,
                            UsuarioEfetivoApi usuarios, MensagensParaEquipeJdbc saida,
                            EstabelecimentoApi estabelecimentos, Relogio relogio,
                            Duration validadeDoAceite) {
        if (validadeDoAceite == null || validadeDoAceite.isNegative() || validadeDoAceite.isZero()) {
            throw new IllegalArgumentException("a validade do aceite precisa ser positiva");
        }
        this.intencoes = intencoes;
        this.catalogo = catalogo;
        this.usuarios = usuarios;
        this.saida = saida;
        this.estabelecimentos = estabelecimentos;
        this.relogio = relogio;
        this.validadeDoAceite = validadeDoAceite;
    }

    public sealed interface Decisao {

        record Confirmada(String resposta) implements Decisao {
        }

        /** RT-CNV-006 — confirmada por quem pediu; agora falta quem vai atender. */
        record AguardandoAceite(String resposta) implements Decisao {
        }

        record JaDecidida(IntencoesJdbc.Status status) implements Decisao {
        }

        record Vencida() implements Decisao {
        }

        record NaoEncontrada() implements Decisao {
        }
    }

    public enum DesfechoDoAceite {
        ACEITA,
        RECUSADA,
        VENCIDA,
        JA_RESPONDIDA,
        NAO_ENCONTRADO,
        /** Aceita, e a gravação recusou: horário tomado, permissão retirada. */
        FALHOU
    }

    /**
     * @param resposta         o que quem tocou no botão recebe
     * @param usuarioEfetivoId em nome de quem a intenção foi pedida, quando ela foi encontrada
     */
    public record DecisaoDoAceite(DesfechoDoAceite desfecho, String resposta,
                                  UUID usuarioEfetivoId) {
    }

    // ------------------------------------------------------------------ o SIM de quem pediu

    @Transactional
    public Decisao executar(UUID intencaoId, String telefone, UUID usuarioId, UUID execucaoId) {
        var travada = intencoes.travar(intencaoId);
        if (travada.isEmpty() || !travada.get().usuarioEfetivoId().equals(usuarioId)) {
            return new Decisao.NaoEncontrada();
        }
        var intencao = travada.get();
        if (intencao.status() != IntencoesJdbc.Status.PENDENTE) {
            return new Decisao.JaDecidida(intencao.status());
        }
        Instant agora = relogio.agora();
        if (!agora.isBefore(intencao.expiraEm())) {
            intencoes.expirar(intencao.id(), execucaoId, agora);
            return new Decisao.Vencida();
        }
        FerramentaDeEscrita ferramenta = escritaDe(intencao);

        if (intencao.exigeAceite()) {
            // Nada a gravar ainda. As trancas conferem agora que a pessoa pode pedir — e conferem de
            // novo quando quem vai atender aceitar.
            String quemPediu = usuarios.emNomeDe(telefone, usuario -> {
                exigirQuemPropos(usuario, intencao, ferramenta);
                return usuario.nome();
            });
            return pedirAceite(intencao, quemPediu, execucaoId, agora);
        }

        var efetivacao = usuarios.emNomeDe(telefone, usuario -> {
            exigirQuemPropos(usuario, intencao, ferramenta);
            return ferramenta.confirmar(intencao.id(), intencao.comando(), Optional.empty());
        });
        if (intencoes.confirmar(intencao.id(), execucaoId, efetivacao.mensagem(),
                efetivacao.registro(), agora) != 1) {
            throw new IllegalStateException(
                    "a intenção " + intencao.id() + " deixou de estar pendente sob trava");
        }
        return new Decisao.Confirmada(efetivacao.mensagem());
    }

    private Decisao pedirAceite(Intencao intencao, String quemPediu, UUID execucaoId,
                                Instant agora) {
        var aceite = intencao.aceite();
        Instant expira = agora.plus(validadeDoAceite);
        String ate = HORA.format(expira.atZone(estabelecimentos.fusoDe(TenantContext.obrigatorio())));
        String resposta = "Pedi a %s para aceitar (%s). Aviso aqui assim que houver resposta, até %s."
                .formatted(aceite.nome(), aceite.motivo(), ate);
        if (intencoes.pedirAceite(intencao.id(), execucaoId, resposta, agora, expira) != 1) {
            throw new IllegalStateException(
                    "a intenção " + intencao.id() + " deixou de estar pendente sob trava");
        }
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.PEDIDO_DE_ACEITE, intencao.id(),
                aceite.telefone(),
                "%s pediu um horário %s:\n%s\nVocê aceita? A resposta vale até %s."
                        .formatted(quemPediu, aceite.motivo(), intencao.resumo(), ate),
                List.of(new EnviadorDoWhatsApp.Botao(
                                new BotaoDeAceite(intencao.id(), aceite.token(), true).idDoBotao(),
                                BotaoDeAceite.ACEITAR),
                        new EnviadorDoWhatsApp.Botao(
                                new BotaoDeAceite(intencao.id(), aceite.token(), false).idDoBotao(),
                                BotaoDeAceite.RECUSAR)),
                agora);
        return new Decisao.AguardandoAceite(resposta);
    }

    // ------------------------------------------------------------------ o toque de quem vai atender

    /**
     * RT-CNV-006 — quem vai atender tocou num botão.
     *
     * <p>Vale só com o token do botão E vindo do número para onde o pedido foi mandado (RN-CNV-019).
     * Qualquer outra combinação é "não encontrei" — sem dizer se a intenção existe.
     */
    @Transactional
    public DecisaoDoAceite decidirAceite(BotaoDeAceite botao, String telefone, UUID execucaoId) {
        var travada = intencoes.travar(botao.intencaoId())
                .filter(intencao -> valeParaEsteBotao(intencao, botao, telefone));
        if (travada.isEmpty()) {
            return new DecisaoDoAceite(DesfechoDoAceite.NAO_ENCONTRADO,
                    "Não encontrei esse pedido.", null);
        }
        var intencao = travada.get();
        var aceite = intencao.aceite();
        if (intencao.status() != IntencoesJdbc.Status.AGUARDANDO_ACEITE) {
            return new DecisaoDoAceite(DesfechoDoAceite.JA_RESPONDIDA,
                    "Esse pedido já foi respondido.", intencao.usuarioEfetivoId());
        }
        Instant agora = relogio.agora();
        if (!agora.isBefore(aceite.expiraEm())) {
            intencoes.expirarAceite(intencao.id(), execucaoId, agora);
            avisarQuemPediu(intencao, "%s não respondeu a tempo: %s. Nada foi gravado."
                    .formatted(aceite.nome(), intencao.resumo()), agora);
            return new DecisaoDoAceite(DesfechoDoAceite.VENCIDA,
                    "Esse pedido já tinha vencido, e nada foi gravado.",
                    intencao.usuarioEfetivoId());
        }
        if (!botao.aceita()) {
            String aviso = "%s recusou o horário %s: %s. Nada foi gravado."
                    .formatted(aceite.nome(), aceite.motivo(), intencao.resumo());
            intencoes.recusarNoAceite(intencao.id(), execucaoId, "RECUSADO_POR_QUEM_ATENDE", aviso,
                    agora);
            avisarQuemPediu(intencao, aviso, agora);
            return new DecisaoDoAceite(DesfechoDoAceite.RECUSADA, "Tudo bem. Avisei quem pediu.",
                    intencao.usuarioEfetivoId());
        }

        FerramentaDeEscrita ferramenta = escritaDe(intencao);
        // Em nome de quem PEDIU, resolvido agora: quem vai atender aceita o horário — não ganha
        // permissão de marcar (RN-CNV-017).
        var efetivacao = usuarios.emNomeDe(intencao.deTelefone(), usuario -> {
            exigirQuemPropos(usuario, intencao, ferramenta);
            return ferramenta.confirmar(intencao.id(), intencao.comando(),
                    Optional.of(new AceiteRegistrado(aceite.nome(), aceite.telefone(), agora)));
        });
        String aviso = "%s aceitou. %s".formatted(aceite.nome(), efetivacao.mensagem());
        if (intencoes.confirmarAceite(intencao.id(), execucaoId, aviso, efetivacao.registro(),
                agora) != 1) {
            throw new IllegalStateException(
                    "a intenção " + intencao.id() + " deixou de aguardar aceite sob trava");
        }
        avisarQuemPediu(intencao, aviso, agora);
        return new DecisaoDoAceite(DesfechoDoAceite.ACEITA,
                "Obrigado! Ficou marcado: %s.".formatted(intencao.resumo()),
                intencao.usuarioEfetivoId());
    }

    /**
     * A recusa da GRAVAÇÃO no aceite — horário tomado, permissão retirada —, registrada fora da
     * transação que a gravação desfez, com o aviso a quem pediu no mesmo commit.
     */
    @Transactional
    public DecisaoDoAceite registrarFalhaDoAceite(BotaoDeAceite botao, String telefone,
                                                  UUID execucaoId, String motivo, String detalhe) {
        var travada = intencoes.travar(botao.intencaoId())
                .filter(intencao -> valeParaEsteBotao(intencao, botao, telefone))
                .filter(intencao -> intencao.status() == IntencoesJdbc.Status.AGUARDANDO_ACEITE);
        if (travada.isEmpty()) {
            return new DecisaoDoAceite(DesfechoDoAceite.JA_RESPONDIDA,
                    "Esse pedido já foi respondido.", null);
        }
        var intencao = travada.get();
        Instant agora = relogio.agora();
        String aviso = "%s aceitou, mas não deu para marcar: %s Nada foi gravado."
                .formatted(intencao.aceite().nome(), detalhe);
        intencoes.recusarNoAceite(intencao.id(), execucaoId, motivo, aviso, agora);
        avisarQuemPediu(intencao, aviso, agora);
        return new DecisaoDoAceite(DesfechoDoAceite.FALHOU,
                "Obrigado. Não deu para marcar: %s Avisei quem pediu.".formatted(detalhe),
                intencao.usuarioEfetivoId());
    }

    // ------------------------------------------------------------------ comum

    /** Token em tempo constante, e o número exato para onde o pedido foi. */
    private static boolean valeParaEsteBotao(Intencao intencao, BotaoDeAceite botao,
                                             String telefone) {
        var aceite = intencao.aceite();
        return aceite != null
                && aceite.telefone().equals(telefone)
                && MessageDigest.isEqual(aceite.token().getBytes(StandardCharsets.US_ASCII),
                        botao.token().getBytes(StandardCharsets.US_ASCII));
    }

    private void avisarQuemPediu(Intencao intencao, String texto, Instant agora) {
        saida.enfileirar(MensagensParaEquipeJdbc.Motivo.DESFECHO_DO_ACEITE, intencao.id(),
                intencao.deTelefone(), texto, List.of(), agora);
    }

    /**
     * Pelo nome guardado, e só se ainda for escrita: executar "o que tiver o mesmo nome" depois de uma
     * troca de versão seria executar outra coisa.
     */
    private FerramentaDeEscrita escritaDe(Intencao intencao) {
        return catalogo.porNome(intencao.ferramenta())
                .filter(FerramentaDeEscrita.class::isInstance)
                .map(FerramentaDeEscrita.class::cast)
                .orElseThrow(() -> new ErroDeDominio(ErrosDoConversacional.ACAO_INDISPONIVEL,
                        "esta ação não está mais disponível por aqui."));
    }

    private static void exigirQuemPropos(UsuarioEfetivo usuario, Intencao intencao,
                                         FerramentaDeEscrita ferramenta) {
        if (!usuario.usuarioId().equals(intencao.usuarioEfetivoId())) {
            throw new AccessDeniedException("o telefone agora é de outra pessoa");
        }
        if (!CatalogoDeFerramentas.podeUsar(usuario, ferramenta)) {
            throw new AccessDeniedException("sem a permissão que a ferramenta declara");
        }
    }
}
