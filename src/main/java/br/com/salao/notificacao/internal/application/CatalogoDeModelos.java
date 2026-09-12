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
        /*
         * RT-FIN-008 — o comprovante.
         *
         * O aviso de "não é documento fiscal" está AQUI também, e não só no papel: R-UX-16 exige
         * que o comprovante diga o que não é, e um recibo que avisa na bobina mas não na mensagem
         * avisa só para metade das clientes.
         *
         * Sem a lista de itens, de propósito: ela vai no papel, que fica na mão da cliente. A
         * mensagem atravessa provedor de terceiro e fica no histórico do aparelho — mandar o que
         * ela faz no cabelo é expor isso a quem pegar o telefone, e não é necessário para
         * confirmar valor e data (minimização, LGPD art. 6, III).
         */
        registrar(new Modelo("comprovante-de-atendimento",
                "{nome}, seu comprovante do atendimento no {salao} em {data}: "
                        + "comanda #{numero}, total de R$ {total}. "
                        + "Não é documento fiscal. Precisando do detalhe, é só pedir na recepção."));
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
