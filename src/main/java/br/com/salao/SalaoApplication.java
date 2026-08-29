package br.com.salao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * RT-INF-001 — Bootstrap do projeto.
 *
 * <p>Monólito modular: cada subpacote direto de {@code br.com.salao} é um módulo do Spring
 * Modulith, com fronteira verificada em {@code ArquiteturaTest}. {@code shared} é declarado
 * como módulo compartilhado porque todos podem depender dele — e só dele.
 */
@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class SalaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalaoApplication.class, args);
    }
}
