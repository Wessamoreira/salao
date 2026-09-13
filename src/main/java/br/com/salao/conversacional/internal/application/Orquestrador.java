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
import br.com.salao.shared.erro.ErroDeDominio;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RT-CNV-004 — conduz uma conversa: modelo, ferramentas, custo.
 *
 * <h2>O modelo é tratado como comprometido — RN-CNV-010</h2>
 *
 * <p>Mensagem de cliente é entrada não confiável, e prompt injection existe. A pergunta de projeto
 * não é "como impedir o modelo de ser enganado"; é "o que acontece quando ele for". Resposta: nada
 * além do que o usuário efetivo já pode. Três trancas, e nenhuma está no prompt:
 *
 * <ol>
 *   <li>o modelo só VÊ as ferramentas permitidas ({@link CatalogoDeFerramentas#permitidasPara});
 *   <li>qualquer pedido fora delas é RECUSADO aqui — inexistente, fora da lista ou de escrita —, e o
 *       resultado recusado volta ao modelo como erro, sem executar nada;
 *   <li>o que é executado passa pelo {@link ExecutorDeFerramenta}, que roda EM NOME DO usuário
 *       efetivo: o {@code @PreAuthorize} do caso de uso confere de novo, e recusa até a ferramenta
 *       que declarou permissão errada.
 * </ol>
 *
 * <p>Cada pedido fica registrado com o desfecho. Um ataque aparece no rastro como RECUSADO — e não
 * como silêncio.
 *
 * <h2>Escrita nunca é executada aqui</h2>
 *
 * <p>Nem se o usuário tiver permissão. Escrita passa por simular → confirmação humana explícita →
 * confirmar (RT-CNV-005/006). O LLM nunca escreve direto no domínio.
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
        INEXISTENTE,
        ESCRITA_EXIGE_CONFIRMACAO,
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
            Pedido pedido = new Pedido(sistema(conversa), List.copyOf(mensagens), ofertas,
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
     * <p>A ordem das recusas é para o diagnóstico: inexistente, depois escrita, depois fora da lista.
     * Uma escrita nunca está na lista — mas dizer "escrita exige confirmação" explica melhor o que o
     * modelo tentou do que "fora da lista".
     */
    private ResultadoDeFerramenta avaliar(ChamadaDeFerramenta pedida, Set<String> permitidas,
                                          ExecutorDeFerramenta executor,
                                          List<ChamadaRegistrada> registro) {
        var ferramenta = catalogo.porNome(pedida.nome());
        if (ferramenta.isEmpty()) {
            return recusar(pedida, DesfechoDaChamada.INEXISTENTE, "ferramenta inexistente", registro);
        }
        if (ferramenta.get().natureza() == Ferramenta.Natureza.ESCRITA) {
            return recusar(pedida, DesfechoDaChamada.ESCRITA_EXIGE_CONFIRMACAO,
                    "esta ação grava dados e exige confirmação humana; não pode ser executada daqui",
                    registro);
        }
        if (!permitidas.contains(pedida.nome())) {
            return recusar(pedida, DesfechoDaChamada.FORA_DA_LISTA,
                    "ferramenta não permitida para este usuário", registro);
        }
        try {
            JsonNode saida = executor.executar(ferramenta.get(), pedida.argumentos());
            registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(),
                    DesfechoDaChamada.EXECUTADA));
            return new ResultadoDeFerramenta(pedida.id(), json.writeValueAsString(saida), false);
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
            // Nada de stack trace para o modelo: é saída para entrada não confiável.
            registro.add(new ChamadaRegistrada(pedida.nome(), pedida.argumentos(),
                    DesfechoDaChamada.FALHA));
            return new ResultadoDeFerramenta(pedida.id(), "a ferramenta falhou", true);
        }
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
     */
    private static String sistema(Conversa conversa) {
        return """
                Você é o assistente do salão %s e conversa pelo WhatsApp com %s, da equipe.
                Agora no salão: %s.
                Responda em português do Brasil, em poucas frases, como numa conversa de WhatsApp.
                Use as ferramentas disponíveis para consultar informações; não invente dados.
                Marcar, remarcar ou cancelar ainda não está disponível por aqui."""
                .formatted(conversa.nomeDoSalao(), conversa.usuario().nome(), conversa.agoraNoSalao());
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
