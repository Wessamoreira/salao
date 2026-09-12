package br.com.salao.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import br.com.salao.SalaoApplication;
import br.com.salao.shared.evento.EventoDeDominio;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.sql.ResultSet;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * RT-INF-001 — as fronteiras do projeto.
 *
 * <p>Instaladas na Fase 0, quando passam trivialmente. É o único momento em que dá para
 * instalá-las: depois de trinta telas, a primeira violação já está lá e ninguém sabe qual é.
 * Se a fronteira não tem teste, ela é só uma intenção.
 */
class ArquiteturaTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("br.com.salao");

    @Test
    void modulos_respeitam_fronteiras() {
        ApplicationModules.of(SalaoApplication.class).verify();
    }

    @Test
    void dominio_nao_depende_de_spring() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("o domínio precisa ser testável sem subir contexto")
                .check(CLASSES);
    }

    @Test
    void controller_nao_chama_repository() {
        noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAPackage("..infra..")
                .because("web fala com application; pular a camada é como regra de negócio vaza "
                        + "para o controller")
                .check(CLASSES);
    }

    /**
     * Onde ponto flutuante é legítimo, com o motivo. A lista é curta de propósito.
     *
     * <p>A regra proíbe {@code double} em todo lugar, e não só onde há dinheiro, porque nomear
     * "os campos que são dinheiro" exigiria acertar a intenção de quem escreveu — e é justamente
     * quem não percebeu que aquilo era dinheiro que a regra precisa pegar.
     *
     * <p>O preço é este: toda exceção legítima passa por aqui. É o preço certo. Acrescentar um
     * nome nesta lista é um gesto deliberado, que aparece na revisão e obriga a escrever por que
     * aquele número não é dinheiro — o oposto de um {@code double} que entra despercebido no meio
     * de um cálculo de comissão.
     */
    private static final String[] NAO_SAO_DINHEIRO = {
        // RT-CLI-003: escore de similaridade de trigrama, de 0 a 1. É medida de parecença entre
        // nomes; erro na terceira casa muda no máximo a ordem de dois avisos na tela.
        "br.com.salao.cliente.internal.domain.Semelhanca",
        "br.com.salao.cliente.internal.infra.ClientesJdbc$CandidatoADuplicado",
    };

    private static DescribedPredicate<JavaClass> foraDaListaDoQueNaoEDinheiro() {
        var permitidas = Set.of(NAO_SAO_DINHEIRO);
        return DescribedPredicate.describe("fora da lista do que não é dinheiro",
                classe -> !permitidas.contains(classe.getFullName()));
    }

    @Test
    void dinheiro_nunca_e_double_ou_float() {
        noFields().that().areDeclaredInClassesThat().resideInAPackage("br.com.salao..")
                .and().areDeclaredInClassesThat(foraDaListaDoQueNaoEDinheiro())
                .should().haveRawType(double.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Float.class)
                .because("dinheiro é BigDecimal/numeric(19,4); ponto flutuante em dinheiro é "
                        + "erro de centavo composto (ADR-0009)")
                .check(CLASSES);
    }

    @Test
    void coluna_anulavel_nao_e_lida_na_mao() {
        noClasses().that().doNotHaveFullyQualifiedName("br.com.salao.shared.jdbc.Colunas")
                .should().callMethod(ResultSet.class, "wasNull")
                .because("getInt + wasNull na mão já deu errado duas vezes neste projeto — "
                        + "RT-CAT-004 e RT-ARQ-001 —, porque wasNull se refere à ÚLTIMA coluna "
                        + "lida e os argumentos de um construtor são avaliados da esquerda para a "
                        + "direita: a segunda leitura apaga a resposta da primeira. Use Colunas, "
                        + "que não tem como ser usada errado")
                .check(CLASSES);
    }

    @Test
    void listener_assincrono_so_recebe_evento_de_dominio() {
        // RN-INF-009. Um listener assíncrono abre transação ANTES do corpo do método, e a
        // transação exige tenant. No caminho normal o PropagadorDeTenant resolve; no reenvio de
        // pendências não há thread de origem, e o tenant só pode vir do payload. Evento sem
        // EventoDeDominio fica pendente para sempre, em silêncio — por isso o build reprova aqui,
        // e não um comentário pedindo atenção.
        methods().that().areAnnotatedWith(
                        org.springframework.modulith.events.ApplicationModuleListener.class)
                .should(new ArchCondition<JavaMethod>(
                        "receber um único parâmetro que implemente EventoDeDominio") {
                    @Override
                    public void check(JavaMethod metodo, ConditionEvents eventos) {
                        var parametros = metodo.getRawParameterTypes();
                        boolean valido = parametros.size() == 1
                                && parametros.get(0).isAssignableTo(EventoDeDominio.class);
                        if (!valido) {
                            eventos.add(SimpleConditionEvent.violated(metodo,
                                    metodo.getFullName() + " precisa receber um único evento que "
                                            + "implemente EventoDeDominio, senão nunca poderá ser "
                                            + "reenviado se falhar"));
                        }
                    }
                })
                .check(CLASSES);
    }

    /**
     * Todo método público de caso de uso passa por {@code @PreAuthorize}.
     *
     * <h2>Por que esta regra existe</h2>
     *
     * <p>Escrevendo RT-CAT-004 acrescentei um <em>overload</em> de conveniência a
     * {@code DefinirHabilidadesUseCase} — três argumentos em vez de quatro, delegando ao método
     * anotado. Sem anotação nenhuma, porque "quem valida é o outro".
     *
     * <p>Não é: a chamada de dentro da própria classe <strong>não passa pelo proxy do Spring</strong>.
     * O overload virou um caminho de entrada sem transação, sem tenant e <strong>sem
     * autorização</strong> — um endpoint que ignorasse a permissão sem que nada no código
     * parecesse errado.
     *
     * <p>Quem pegou foi um teste de permissão que já existia por outro motivo. Esta regra é para
     * não depender de sorte na próxima vez: o método sem anotação reprova o build, e não uma
     * revisão que pode não acontecer.
     */
    /**
     * Casos de uso cuja tranca é a IDENTIDADE, não uma permissão. A lista é curta de propósito, e
     * todos os itens caem em duas famílias:
     *
     * <ul>
     *   <li><strong>Antes de existir usuário autenticado</strong> — login, refresh, MFA no meio do
     *       login, provisionamento do salão. Exigir permissão aqui seria pedir credencial para
     *       quem está tentando obter credencial.</li>
     *   <li><strong>Autosserviço sobre a própria conta</strong> — trocar a própria senha, encerrar
     *       as próprias sessões, gerir o próprio segundo fator, ler as próprias capacidades. Quem
     *       protege é o dono da conta ser o autor, verificado dentro do caso de uso.</li>
     * </ul>
     *
     * <p>Acrescentar um nome aqui é um gesto deliberado, que aparece na revisão e obriga a
     * escrever por que aquela porta não tem tranca de permissão.
     */
    private static final String[] TRANCADOS_PELA_IDENTIDADE = {
        // Antes de existir usuário autenticado.
        "br.com.salao.iam.internal.application.AutenticarUseCase",
        "br.com.salao.iam.internal.application.RenovarAcessoUseCase",
        "br.com.salao.iam.internal.application.ProvisionarEstabelecimentoUseCase",
        // MFA: `concluirLogin` acontece no meio do login; o resto é sobre a própria conta.
        "br.com.salao.iam.internal.application.SegundoFatorUseCase",
        // Autosserviço sobre a própria conta.
        "br.com.salao.iam.internal.application.TrocarSenhaUseCase",
        "br.com.salao.iam.internal.application.EncerrarSessaoUseCase",
        "br.com.salao.iam.internal.application.ConsultarCapacidadesUseCase",
    };

    /**
     * Casos de uso que <strong>não são porta</strong>: nenhuma requisição os alcança.
     *
     * <p>Categoria descoberta em RT-NOT-001, quando a regra pegou
     * {@code EnfileirarMensagemUseCase} e estava certa em perguntar. A resposta é que ele é API de
     * módulo: quem o chama é {@code agenda} pedindo um lembrete, já dentro de uma operação
     * autorizada — e a permissão certa é a de quem chamou, não uma inventada aqui.
     *
     * <p>Exigir {@code @PreAuthorize} nele seria pior do que não exigir: qualquer permissão que se
     * escolhesse estaria errada para algum chamador, e o resultado seria uma tranca que o próprio
     * sistema teria de contornar.
     *
     * <p><strong>A condição para entrar aqui é dura:</strong> não pode existir controller que
     * chegue nesta classe. No dia em que "mandar mensagem avulsa" virar tela, ela sai desta lista
     * e ganha permissão de verdade.
     */
    private static final String[] NAO_SAO_PORTA = {
        // Chamado por `agenda` (lembrete, cancelamento) e por `atendimento` (pesquisa), sempre
        // dentro de uma operação que já checou a sua própria permissão.
        "br.com.salao.notificacao.internal.application.EnfileirarMensagemUseCase",
    };

    /**
     * Casos de uso autenticados pelo <strong>provedor</strong>, e não por um principal.
     *
     * <p>Categoria descoberta em RT-CNV-001, e ela é genuinamente diferente das duas de cima. O
     * webhook do WhatsApp é chamado pela Meta: não há usuário, não há token nosso, não há tenant —
     * o tenant é justamente o que aquele caso de uso descobre. Qualquer {@code @PreAuthorize} ali
     * recusaria 100% das chamadas, porque não existe {@code Authentication} nenhum no contexto.
     *
     * <p>Não cabe em {@code TRANCADOS_PELA_IDENTIDADE}: lá a tranca é verificada
     * <em>dentro</em> do caso de uso (o autor é o dono da conta). Aqui a tranca é o HMAC
     * {@code X-Hub-Signature-256}, verificado na <strong>borda</strong> — e o projeto desconfia de
     * tranca na borda exatamente porque outro chamador pode aparecer e contorná-la.
     *
     * <p>Nem em {@code NAO_SAO_PORTA}: a condição de lá é que nenhum controller alcance a classe,
     * e um controller alcança esta.
     *
     * <p><strong>A condição desta lista, então, é outra e é estreita:</strong> só o controller do
     * webhook pode alcançá-las — o que {@link #o_que_o_provedor_autentica_so_e_alcancado_pelo_webhook}
     * verifica. Sem isso, um caso de uso que aceita {@code estabelecimentoId} como parâmetro e não
     * checa permissão ficaria a uma tela de distância de injetar mensagem falsa na caixa de
     * entrada de qualquer salão.
     */
    private static final String[] AUTENTICADOS_PELO_PROVEDOR = {
        "br.com.salao.conversacional.internal.application.ReceberLoteDoWhatsAppUseCase",
        "br.com.salao.conversacional.internal.application.GravarMensagemRecebidaUseCase",
    };

    private static final String WEBHOOK_DO_WHATSAPP =
            "br.com.salao.conversacional.internal.web.WebhookDoWhatsAppController";

    private static DescribedPredicate<JavaClass> foraDaListaDeTrancadosPelaIdentidade() {
        var permitidas = new java.util.HashSet<String>();
        permitidas.addAll(Set.of(TRANCADOS_PELA_IDENTIDADE));
        permitidas.addAll(Set.of(NAO_SAO_PORTA));
        permitidas.addAll(Set.of(AUTENTICADOS_PELO_PROVEDOR));
        return DescribedPredicate.describe("fora das listas de exceção",
                classe -> !permitidas.contains(classe.getFullName()));
    }

    /**
     * O que sustenta {@code AUTENTICADOS_PELO_PROVEDOR}: só o webhook chega lá.
     *
     * <p>No dia em que "reprocessar mensagem" ou "simular recebimento" virar tela, o build reprova
     * e obriga a decidir a permissão — que é o momento em que a decisão precisa ser tomada.
     */
    @Test
    void o_que_o_provedor_autentica_so_e_alcancado_pelo_webhook() {
        for (String classe : AUTENTICADOS_PELO_PROVEDOR) {
            noClasses().that().resideInAPackage("..web..")
                    .and().doNotHaveFullyQualifiedName(WEBHOOK_DO_WHATSAPP)
                    .should().dependOnClassesThat().haveFullyQualifiedName(classe)
                    .because(classe + " dispensa @PreAuthorize porque quem a autentica é o HMAC "
                            + "do provedor, verificado no webhook; outro controller chamando-a "
                            + "quebra essa premissa")
                    .check(CLASSES);
        }
    }

    /**
     * O que sustenta a lista acima: nenhum controller alcança um caso de uso que não é porta.
     *
     * <p>Sem este teste, {@code NAO_SAO_PORTA} seria uma afirmação sobre o passado. Com ele, a
     * primeira tela que expuser um desses reprova o build e obriga a decidir a permissão — que é
     * exatamente o momento em que a decisão precisa ser tomada.
     */
    @Test
    void o_que_nao_e_porta_continua_nao_sendo() {
        for (String classe : NAO_SAO_PORTA) {
            noClasses().that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(classe)
                    .because(classe + " está na lista de \"não é porta\" e por isso dispensa "
                            + "@PreAuthorize; um controller chamando-o quebra essa premissa")
                    .check(CLASSES);
        }
    }

    @Test
    void caso_de_uso_nao_tem_porta_sem_autorizacao() {
        methods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("UseCase")
                .and().areDeclaredInClassesThat(foraDaListaDeTrancadosPelaIdentidade())
                .and().arePublic()
                .and().areNotStatic()
                .should().beAnnotatedWith(PreAuthorize.class)
                .because("método público de caso de uso sem @PreAuthorize é porta sem tranca: "
                        + "chamado de dentro da própria classe ele nem passa pelo proxy, e a "
                        + "permissão do método vizinho não vale para ele")
                .check(CLASSES);
    }

    @Test
    void instante_nunca_vem_de_now() {
        noClasses().that().resideOutsideOfPackage("..shared.tempo..")
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .because("RN-INF-006: use o port Relogio, senão regra de tempo só é testável "
                        + "com Thread.sleep")
                .check(CLASSES);
    }
}
