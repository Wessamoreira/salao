package br.com.salao.notificacao.internal.application;

import br.com.salao.notificacao.internal.domain.Modelo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * RT-NOT-001 — os modelos que o salão pode mandar.
 *
 * <p>Em código e não em tabela, por enquanto. Um modelo editável pelo salão é uma boa ideia e traz
 * junto uma pergunta que ninguém respondeu: quem revisa o texto que vai para a cliente? WhatsApp
 * exige modelos aprovados previamente, e um campo de texto livre na tela de configuração produziria
 * mensagens que o provedor recusa — falha que só aparece no envio, uma por cliente.
 *
 * <p>Registrado como pendência. Enquanto isso, mudar um texto é uma linha aqui, revisada como
 * código.
 */
public class CatalogoDeModelos {

    private final Map<String, Modelo> porNome = new LinkedHashMap<>();

    public CatalogoDeModelos() {
        registrar(new Modelo("lembrete-de-agendamento",
                "Oi {nome}! Passando para lembrar do seu horário no {salao} "
                        + "{quando}, com {profissional}. Até lá!"));
        registrar(new Modelo("agendamento-confirmado",
                "{nome}, seu horário no {salao} está confirmado para {quando}. Até lá!"));
        registrar(new Modelo("agendamento-cancelado",
                "{nome}, seu horário no {salao} em {quando} foi cancelado. "
                        + "Qualquer coisa, é só chamar."));
        registrar(new Modelo("vaga-disponivel",
                "{nome}, abriu um horário no {salao} {quando}. Ainda tem interesse?"));
    }

    private void registrar(Modelo modelo) {
        porNome.put(modelo.nome(), modelo);
    }

    public Optional<Modelo> porNome(String nome) {
        return Optional.ofNullable(porNome.get(nome));
    }
}
