package br.com.salao.notificacao.internal.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RT-NOT-001 — o texto da mensagem, com os buracos por preencher.
 *
 * <h2>Por que o modelo não guarda o dado</h2>
 *
 * <p>"Oi {nome}, seu horário é {quando}" é texto do salão; "Oi Marilda, seu horário é amanhã às 14h"
 * é dado pessoal. Separá-los deixa o modelo ser versionado, revisado e traduzido sem que ninguém
 * precise olhar mensagem de cliente nenhuma.
 *
 * <h2>Faltar parâmetro é erro, não buraco</h2>
 *
 * <p>Um modelo com {@code {nome}} não preenchido produziria "Oi {nome}, seu horário é amanhã" — que
 * sai, é entregue, e faz o salão parecer quebrado para a cliente. É o tipo de defeito que ninguém
 * reporta e todo mundo vê.
 */
public record Modelo(String nome, String texto) {

    private static final Pattern PARAMETRO = Pattern.compile("\\{(\\w+)}");

    public Modelo {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("o modelo precisa de um nome");
        }
        if (texto == null || texto.isBlank()) {
            throw new IllegalArgumentException("o modelo precisa de um texto");
        }
    }

    /** Os nomes que o texto espera receber. */
    public Set<String> parametros() {
        var nomes = new LinkedHashSet<String>();
        Matcher busca = PARAMETRO.matcher(texto);
        while (busca.find()) {
            nomes.add(busca.group(1));
        }
        return nomes;
    }

    /**
     * O texto pronto.
     *
     * @throws IllegalArgumentException se faltar algum parâmetro, ou se sobrar — sobrar significa
     *                                  que quem chamou acha que o modelo diz algo que ele não diz,
     *                                  e o texto que sai não é o que a pessoa pensou que ia sair
     */
    public String preencher(Map<String, String> valores) {
        Set<String> esperados = parametros();
        var faltando = new LinkedHashSet<>(esperados);
        faltando.removeAll(valores.keySet());
        if (!faltando.isEmpty()) {
            throw new IllegalArgumentException(
                    "faltam parâmetros no modelo " + nome + ": " + String.join(", ", faltando));
        }
        var sobrando = new LinkedHashSet<>(valores.keySet());
        sobrando.removeAll(esperados);
        if (!sobrando.isEmpty()) {
            throw new IllegalArgumentException(
                    "parâmetros que o modelo " + nome + " não usa: "
                            + String.join(", ", sobrando));
        }

        String pronto = texto;
        for (String parametro : esperados) {
            // `String.replace` é substituição LITERAL, não regex: nada a escapar. A primeira
            // versão usava `Matcher.quoteReplacement` "por segurança" e produzia `R\$ 80,00` —
            // proteção contra um problema que só existe em `replaceAll`, criando um que não
            // existia. O teste com valor em dinheiro pegou.
            pronto = pronto.replace("{" + parametro + "}", valores.get(parametro));
        }
        return pronto;
    }
}
