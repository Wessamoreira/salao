package br.com.salao.conversacional.internal.application;

import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Bloco;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.ChamadaDeFerramenta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.FerramentaOferecida;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Mensagem;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Papel;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Pedido;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.Resposta;
import br.com.salao.conversacional.internal.application.ModeloDeLinguagem.ResultadoDeFerramenta;
import br.com.salao.iam.api.ErrosDoIam;
import br.com.salao.iam.api.UsuarioEfetivo;
import br.com.salao.shared.agente.Ferramenta;
import br.com.salao.shared.agente.FerramentaDeEscrita;
import br.com.salao.shared.agente.FerramentaDeLeitura;
import br.com.salao.shared.erro.ErroDeDominio;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * RT-CNV-004 / RT-CNV-005 — conduz uma conversa: modelo, ferramentas, custo.
 *
 * <h2>O modelo é tratado como comprometido — RN-CNV-010</h2>
 *
 * <p>Mensagem de cliente é entrada não confiável, e prompt injection existe. A pergunta de projeto
 * não é "como impedir o modelo de ser enganado"; é "o que acontece quando ele for". Resposta: nada
 * além do que o usuário efetivo já pode. Três trancas, e nenhuma está no prompt:
 *
 * <ol>
 *   <li>o modelo só VÊ as ferramentas permitidas ({@link CatalogoDeFerramentas#permitidasPara});
 *   <li>qualquer pedido fora delas é RECUSADO aqui — inexistente ou fora da lista —, e o resultado
 *       recusado volta ao modelo como erro, sem executar nada;
 *   <li>o que é executado passa pelo {@link ExecutorDeFerramenta}, que roda EM NOME DO usuário
 *       efetivo: o {@code @PreAuthorize} do caso de uso confere de novo, e recusa até a ferramenta
 *       que declarou permissão errada.
 * </ol>
 *
 * <p>Cada pedido fica registrado com o desfecho. Um ataque aparece no rastro como RECUSADO — e não
 * como silêncio.
 *
 * <h2>Escrita não sai daqui gravada — RN-CNV-014</h2>
 *
 * <p>Pedir uma ferramenta de escrita produz uma SIMULAÇÃO e uma intenção pendente; o que volta ao
 * modelo é o resumo e a informação de que nada foi gravado. Não é uma recusa num {@code if}: o
 * {@link ExecutorDeFerramenta} não tem método que grave. Gravar é
 * {@code FerramentaDeEscrita.confirmar}, e só a mensagem da própria pessoa com o código chega lá —
 * sem passar por esta classe nem pelo modelo.
 *
 * <h2>Custo reservado antes de cada chamada — RN-CNV-011</h2>
 *
 * <p>O pior caso de cada iteração é reservado antes; se não couber no teto do dia, a conversa para
 * ali. E o laço tem teto de iterações: um modelo que pede ferramenta sem fim não consome o dia do
 * salão.
 *
 * <p>Sem estado e sem transação: as chamadas ao modelo acontecem fora de qualquer conexão com o banco.
 */
public class Orquestrador {

    private static final Logger log = LoggerFactory.getLogger(Orquestrador.class);

    public static final int MAXIMO_DE_ITERACOES = 4;
    static final int MAX_TOKENS_DE_SAIDA = 1024;

    /** Conservador de propósito: menos caracteres por token superestima, e reserva de mais. */
    private static final int CARACTERES_POR_TOKEN = 3;

    static final String RESPOSTA_SEM_TEXTO = "Não consegui responder agora. Tente de novo em instantes.";
    static final String RESPOSTA_LIMITE =
            "Não consegui concluir isso por aqui. Se precisar, fale com a recepção.";

    private final ModeloDeLinguagem modelo;
    private final CatalogoDeFerramentas catalogo;
    private final PrecoDoModelo preco;
    private final ObjectMapper json;

    public Orquestrador(ModeloDeLinguagem modelo, CatalogoDeFerramentas catalogo,
                        PrecoDoModelo preco, ObjectMapper json) {
        this.modelo = modelo;
        this.catalogo = catalogo;
        this.preco = preco;
        this.json = json;
    }

    public record Troca(String entrada, String resposta) {
    }

    public record Conversa(UsuarioEfetivo usuario, String nomeDoSalao, String agoraNoSalao,
                           String entrada, List<Troca> historico) {
    }

    public enum Desfecho {
        RESPONDIDA,
        TETO_ATINGIDO,
        LIMITE_DE_ITERACOES
    }

    public enum DesfechoDaChamada {
        EXECUTADA,
        /** Escrita simulada e intenção registrada para a pessoa confirmar. Nada foi gravado. */
        PROPOSTA,
        INEXISTENTE,
        FORA_DA_LISTA,
        SEM_PERMISSAO,
        ERRO_DE_DOMINIO,
        FALHA
    }

    public record ChamadaRegistrada(String nome, JsonNode argumentos, DesfechoDaChamada desfecho) {
    }

    public record Resultado(Desfecho desfecho, String resposta, List<ChamadaRegistrada> chamadas,
                            int iteracoes, int tokensEntrada, int tokensSaida, BigDecimal custo,
                            String modelo) {
    }

    public Resultado conduzir(Conversa conversa, Orcamento orcamento, ExecutorDeFerramenta executor) {
        List<Ferramenta> permitidas = catalogo.permitidasPara(conversa.usuario());
        Set<String> nomesPermitidos = permitidas.stream().map(Ferramenta::nome)
                .collect(Collectors.toUnmodifiableSet());
        List<FerramentaOferecida> ofertas = permitidas.stream()
                .map(f -> new FerramentaOferecida(f.nome(), f.descricao(), f.esquemaDeEntrada()))
                .toList();
        boolean propoe = permitidas.stream().anyMatch(FerramentaDeEscrita.class::isInstance);

        var mensagens = new ArrayList<Mensagem>();
        for (Troca troca : conversa.historico()) {
            if (troca.resposta() != null && !troca.resposta().isBlank()) {
                mensagens.add(Mensagem.doUsuario(troca.entrada()));
                mensagens.add(Mensagem.doAssistente(troca.resposta()));
            }
        }
        mensagens.add(Mensagem.doUsuario(conversa.entrada()));

        var chamadas = new ArrayList<ChamadaRegistrada>();
        int tokensEntrada = 0;
        int tokensSaida = 0;
        BigDecimal custo = BigDecimal.ZERO;

        for (int iteracao = 1; iteracao <= MAXIMO_DE_ITERACOES; iteracao++) {
            Pedido pedido = new Pedido(sistema(conversa, propoe), List.copyOf(mensagens), ofertas,
                    MAX_TOKENS_DE_SAIDA);
            BigDecimal piorCaso = piorCaso(pedido);
            if (!orcamento.reservar(piorCaso)) {
                return new Resultado(Desfecho.TETO_ATINGIDO, null, chamadas, iteracao - 1,
                        tokensEntrada, tokensSaida, custo, modelo.nome());
            }

            Resposta resposta;
            try {
                resposta = modelo.responder(pedido);
            } catch (RuntimeException falha) {
                // A reserva não pode ficar presa: o modelo falhou, e não custou nada que se saiba.
                orcamento.liquidar(piorCaso, BigDecimal.ZERO, 0, 0);
                throw falha;
            }
            BigDecimal real = preco.custo(resposta.tokensEntrada(), resposta.tokensSaida());
            orcamento.liquidar(piorCaso, real, resposta.tokensEntrada(), resposta.tokensSaida());
            tokensEntrada += resposta.tokensEntrada();
            tokensSaida += resposta.tokensSaida();
            custo = custo.add(real);

            List<ChamadaDeFerramenta> pedidas = resposta.chamadas();
            if (pedidas.isEmpty()) {
                String texto = resposta.texto();
                return new Resultado(Desfecho.RESPONDIDA, texto.isBlank() ? RESPOSTA_SEM_TEXTO : texto,
                        chamadas, iteracao, tokensEntrada, tokensSaida, custo, modelo.nome());
            }

            mensagens.add(new Mensagem(Papel.ASSISTENTE, resposta.blocos()));
            var resultados = new ArrayList<Bloco>();
            for (ChamadaDeFerramenta pedida : pedidas) {
                resultados.add(avaliar(pedida, nomesPermitidos, executor, chamadas));
            }
            mensagens.add(new Mensagem(Papel.USUARIO, resultados));
        }
        return new Resultado(Desfecho.LIMITE_DE_ITERACOES, RESPOSTA_LIMITE, chamadas,
                MAXIMO_DE_ITERACOES, tokensEntrada, tokensSaida, custo, modelo.nome());
    }

    /**
     * Decide o que fazer com UM pedido de ferramenta.
     *
     * <p>Inexistente, depois fora da lista. Passou das duas: leitura executa, escrita propõe — e a
     * forma da ferramenta decide qual, não uma declaração que pudesse mentir.
     */
    private ResultadoDeFerramenta avaliar(ChamadaDeFerramenta pedida, Set<String> permitidas,
                                          ExecutorDeFerramenta executor,
                                          List<ChamadaRegistrada> registro) {
        var ferramenta = catalogo.porNome(pedida.nome());
        if (ferramenta.isEmpty()) {
            return recusar(pedida, DesfechoDaChamada.INEXISTENTE, "ferramenta inexistente", registro);
        }
        if (!permitidas.contains(pedida.nome())) {
            return recusar(pedida, DesfechoDaChamada.FORA_DA_LISTA,
                    "ferramenta não permitida para este usuário", registro);
        }
        JsonNode argumentos = pedida.argumentos() == null || pedida.argumentos().isNull()
                ? json.createObjectNode()
                : pedida.argumentos();
        try {
            return switch (ferramenta.get()) {
                case FerramentaDeLeitura leitura -> {
                    JsonNode saida = executor.executar(leitura, argumentos);
                    registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(),
                            DesfechoDaChamada.EXECUTADA));
                    yield new ResultadoDeFerramenta(pedida.id(), json.writeValueAsString(saida),
                            false);
                }
                case FerramentaDeEscrita escrita -> {
                    String resumo = executor.propor(escrita, argumentos);
                    registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(),
                            DesfechoDaChamada.PROPOSTA));
                    yield new ResultadoDeFerramenta(pedida.id(),
                            json.writeValueAsString(proposta(resumo)), false);
                }
            };
        } catch (AccessDeniedException negado) {
            // A segunda tranca: a declaração de permissão da ferramenta estava errada, e o
            // @PreAuthorize do caso de uso recusou.
            return recusar(pedida, DesfechoDaChamada.SEM_PERMISSAO, "sem permissão", registro);
        } catch (ErroDeDominio erro) {
            if (erro.codigo() == ErrosDoIam.CONTATO_NAO_IDENTIFICADO) {
                return recusar(pedida, DesfechoDaChamada.SEM_PERMISSAO, "sem permissão", registro);
            }
            registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(),
                    DesfechoDaChamada.ERRO_DE_DOMINIO));
            return new ResultadoDeFerramenta(pedida.id(), erro.getMessage(), true);
        } catch (RuntimeException falha) {
            // Nada de stack trace para o modelo: é saída para entrada não confiável. E no log só a
            // classe: a mensagem de uma exceção qualquer pode trazer dado pessoal, e o log sai do
            // perímetro. A causa se reproduz no teste.
            log.warn("Ferramenta {} falhou ({})", pedida.nome(), falha.getClass().getName());
            registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(),
                    DesfechoDaChamada.FALHA));
            return new ResultadoDeFerramenta(pedida.id(), "a ferramenta falhou", true);
        }
    }

    /**
     * O que o modelo fica sabendo de uma escrita: que nada foi gravado, e o que a pessoa vai ler.
     *
     * <p>O código de confirmação NÃO vai para o modelo. Não por ser segredo — vindo daqui ele não
     * confirmaria nada —, mas porque o modelo não tem o que fazer com ele, e um código repetido no
     * texto do modelo competiria com o pedido de confirmação do servidor.
     */
    private ObjectNode proposta(String resumo) {
        ObjectNode saida = json.createObjectNode();
        saida.put("situacao", "AGUARDANDO_CONFIRMACAO");
        saida.put("gravado", false);
        saida.put("resumo", resumo);
        saida.put("confirmacao", "o sistema envia este resumo à pessoa com um código; "
                + "só a resposta dela com o código grava");
        return saida;
    }

    private static ResultadoDeFerramenta recusar(ChamadaDeFerramenta pedida,
                                                 DesfechoDaChamada desfecho, String motivo,
                                                 List<ChamadaRegistrada> registro) {
        registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(), desfecho));
        return new ResultadoDeFerramenta(pedida.id(), motivo, true);
    }

    /**
     * O contexto do modelo. Sem instrução de segurança nenhuma — de propósito.
     *
     * <p>"Não execute ações perigosas" num prompt é uma frase que a mensagem seguinte pode revogar. A
     * segurança está no que o modelo PODE fazer, e isso é decidido fora dele.
     *
     * <p>O parágrafo sobre gravação é descrição, não tranca: sem ele o modelo diria "marquei" ao
     * receber o resumo. E só aparece para quem tem ferramenta de escrita — para os outros seria
     * falar de uma porta que não existe.
     */
    private static String sistema(Conversa conversa, boolean propoe) {
        String base = """
                Você é o assistente do salão %s e conversa pelo WhatsApp com %s, da equipe.
                Agora no salão: %s. Datas e horas são sempre as do salão.
                Responda em português do Brasil, em poucas frases, como numa conversa de WhatsApp.
                Use as ferramentas disponíveis para consultar informações; não invente dados."""
                .formatted(conversa.nomeDoSalao(), conversa.usuario().nome(), conversa.agoraNoSalao());
        if (!propoe) {
            return base;
        }
        return base + """

                Ferramentas que gravam só preparam o pedido: o sistema envia à pessoa um resumo com \
                um código, e nada é gravado até ela responder com o código.""";
    }

    private BigDecimal piorCaso(Pedido pedido) {
        long caracteres = pedido.sistema().length();
        for (Mensagem mensagem : pedido.mensagens()) {
            for (Bloco bloco : mensagem.blocos()) {
                caracteres += switch (bloco) {
                    case ModeloDeLinguagem.Texto t -> t.texto() == null ? 0 : t.texto().length();
                    case ChamadaDeFerramenta c -> c.nome().length()
                            + (c.argumentos() == null ? 0 : c.argumentos().toString().length());
                    case ResultadoDeFerramenta r -> r.conteudo() == null ? 0 : r.conteudo().length();
                };
            }
        }
        for (FerramentaOferecida oferta : pedido.ferramentas()) {
            caracteres += oferta.nome().length() + oferta.descricao().length()
                    + oferta.esquema().toString().length();
        }
        long tokensEstimados = caracteres / CARACTERES_POR_TOKEN + 1;
        return preco.custo(tokensEstimados, pedido.maxTokens());
    }
}
