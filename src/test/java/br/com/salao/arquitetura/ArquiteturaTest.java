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

    private static DescribedPredicate<JavaClass> foraDaListaDeTrancadosPelaIdentidade() {
        var permitidas = Set.of(TRANCADOS_PELA_IDENTIDADE);
        return DescribedPredicate.describe("fora da lista dos trancados pela identidade",
                classe -> !permitidas.contains(classe.getFullName()));
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
