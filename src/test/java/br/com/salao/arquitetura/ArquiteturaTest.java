package br.com.salao.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import br.com.salao.SalaoApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Test
    void dinheiro_nunca_e_double_ou_float() {
        noFields().that().areDeclaredInClassesThat().resideInAPackage("br.com.salao..")
                .should().haveRawType(double.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Float.class)
                .because("dinheiro é BigDecimal/numeric(19,4); ponto flutuante em dinheiro é "
                        + "erro de centavo composto (ADR-0009)")
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
