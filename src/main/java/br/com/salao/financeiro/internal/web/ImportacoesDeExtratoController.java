package br.com.salao.financeiro.internal.web;

import br.com.salao.financeiro.internal.application.ImportarExtratoUseCase;
import br.com.salao.financeiro.internal.application.ImportarExtratoUseCase.ResultadoDaImportacao;
import br.com.salao.financeiro.internal.application.ListarImportacoesUseCase;
import br.com.salao.financeiro.internal.application.ListarImportacoesUseCase.ImportacaoResumo;
import br.com.salao.financeiro.internal.application.PreverImportacaoUseCase;
import br.com.salao.financeiro.internal.application.PreverImportacaoUseCase.PreviaDaImportacao;
import br.com.salao.financeiro.internal.domain.CampoDoExtrato;
import br.com.salao.financeiro.internal.domain.FormatoDeNumero;
import br.com.salao.financeiro.internal.domain.MapeamentoDoExtrato;
import br.com.salao.shared.paginacao.Pagina;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * RT-FIN-009 — a importação do relatório de vendas da Stone. O primeiro upload do sistema.
 *
 * <p>Duas rotas de envio, e o arquivo não fica no servidor entre elas: a prévia lê e devolve; a
 * importação recebe o mesmo arquivo com o mapeamento confirmado. Guardar o arquivo entre as duas seria
 * guardar dado de pagamento para nada.
 *
 * <p>Multipart com uma parte JSON, {@code mapeamento}: o arquivo e o que se quer fazer com ele chegam
 * juntos, e não há como importar um arquivo com o mapeamento de outro.
 */
@RestController
@RequestMapping("/api/v1/conciliacao/importacoes")
public class ImportacoesDeExtratoController {

    private final PreverImportacaoUseCase prever;
    private final ImportarExtratoUseCase importar;
    private final ListarImportacoesUseCase listar;

    public ImportacoesDeExtratoController(PreverImportacaoUseCase prever,
                                          ImportarExtratoUseCase importar,
                                          ListarImportacoesUseCase listar) {
        this.prever = prever;
        this.importar = importar;
        this.listar = listar;
    }

    /** Qual coluna é qual campo, pelo nome da coluna, e como o arquivo escreve dinheiro. */
    public record MapeamentoRequest(Map<CampoDoExtrato, String> colunas, FormatoDeNumero formato) {

        MapeamentoDoExtrato paraDominio() {
            return new MapeamentoDoExtrato(colunas, formato);
        }
    }

    @PostMapping(path = "/previa", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviaDaImportacao previa(
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestPart(value = "mapeamento", required = false) MapeamentoRequest mapeamento)
            throws IOException {
        return prever.executar(arquivo.getBytes(),
                mapeamento == null ? null : mapeamento.paraDominio());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResultadoDaImportacao> importar(
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestPart("mapeamento") MapeamentoRequest mapeamento) throws IOException {
        var resultado = importar.executar(arquivo.getBytes(), arquivo.getOriginalFilename(),
                mapeamento.paraDominio());
        return ResponseEntity.created(URI.create("/api/v1/conciliacao/importacoes/" + resultado.id()))
                .body(resultado);
    }

    @GetMapping
    public Pagina<ImportacaoResumo> listar(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limite) {
        return listar.listar(cursor, limite);
    }
}
