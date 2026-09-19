---
id: RT-FIN-009
titulo: "Importar extrato do adquirente (CSV/OFX ou API — ver D3)"
modulo: financeiro
fase: 5
perfil: completo
status: implementado
depende_de: [RT-FIN-001]
permissoes: [financeiro:reconcile]
eventos: []
regras: [RN-FIN-024, RN-FIN-025, RN-FIN-026, RN-FIN-027, RN-FIN-028]
atualizado_em: 2026-09-19
---

# RT-FIN-009 — Importar o relatório de vendas da Stone

## 1. Objetivo

Trazer para dentro do sistema o que a maquininha diz que aconteceu: cada venda no cartão, com
Stone ID, NSU, autorização, bandeira, parcelas, valor bruto e o que a Stone retém. É a metade que falta
para a conciliação — o salão já registra o seu lado em cada pagamento (RT-FIN-001) — e o casamento dos
dois é a próxima rotina (RT-FIN-010).

## 2. As decisões — do dono, em 19/09/2026

| Pergunta | Decisão |
|---|---|
| Qual adquirente? (pergunta 12, D3) | **Stone / Ton** |
| O que importar? | **O relatório de vendas** do portal ("Lista de vendas"), uma linha por venda |
| A Stone não publica as colunas. Como ler? | **Mapeamento na tela, com prévia** |
| E a fila de espera parcial (RT-AGD-010)? | **Fica** para quando houver provedor de envio; a Fase 5 começa agora |

**Por que não a API de Conciliação da Stone**, que existe e é documentada (XML diário, layout 2.4, com
`AcquirerTransactionKey`, `IssuerAuthorizationCode`, parcelas e taxas): exige credencial de parceiro,
pedida à Stone. Fica como o segundo adapter de ADR-0007 — o domínio desta rotina não muda quando ele
entrar.

**Pix não vem.** A própria Stone avisa que Pix na maquininha ainda não está na Lista de vendas. Não
faz falta: pagamento em Pix é `NAO_APLICAVEL` para a conciliação (RT-FIN-001).

## 3. Por que mapear na tela

A Stone **não publica** as colunas da Lista de vendas: o nome de cada coluna, o separador, a codificação
e o formato de data e de número só se sabem pelo arquivo. Um leitor fixo teria de ser escrito sobre
nomes adivinhados — e um cabeçalho errado, ou "1.234,56" lido como 1,234, vira divergência falsa em
dinheiro de verdade.

Então quem lê o arquivo é quem o conhece: o sistema **sugere** qual coluna é qual pelo nome, e **mostra
as primeiras vendas já interpretadas** — data, valor, bandeira, parcelas — antes de gravar. O dono
confere, corrige o que precisar, e importa. O mapeamento fica salvo **pelo nome da coluna**, e a próxima
importação já vem preenchida. Se a Stone mudar o layout, a tela mostra — em vez de uma importação
errada em silêncio.

## 4. O desenho

- **Duas etapas, e o arquivo não fica no servidor entre elas.** `…/previa` lê e devolve colunas,
  sugestão e amostra; `…/importacoes` recebe o mesmo arquivo com o mapeamento confirmado e grava.
- **Domínio independente da origem** (ADR-0007): `TransacaoDoAdquirente` — adquirente, id na
  adquirente (o Stone ID), NSU, autorização, capturada em, produto, bandeira, parcelas, valor bruto,
  valor líquido quando vier, situação (aprovada, cancelada).
- **O arquivo original não fica guardado.** A Lista de vendas pode trazer o cartão mascarado, e a
  conciliação não precisa dele. Fica a **impressão SHA-256** do arquivo — prova de qual arquivo entrou, e
  o aviso "este arquivo já foi importado em …".
- **Reimportar é inofensivo.** Única por salão, adquirente e Stone ID: o mesmo arquivo duas vezes não
  cria nada novo. A venda que volta **cancelada** muda de situação, e isso fica registrado.
- **Dinheiro não é sobrescrito em silêncio.** O mesmo Stone ID com outro valor, ou uma cancelada que
  volta aprovada, é linha **recusada** com o motivo — quem decide é quem olha.
- **Linha ruim não derruba o arquivo.** Cada linha é lida sozinha; a importação diz quantas entraram,
  quantas já existiam, quantas foram canceladas, ignoradas (Pix, venda negada) e recusadas — com o
  número da linha e o motivo.
- **A hora é a do salão.** O portal mostra hora local; ela é lida no fuso do estabelecimento
  (ADR-0009).
- **Importar não é casar.** Esta rotina grava o lado da Stone; ligá-lo aos pagamentos é RT-FIN-010.
- **Permissão:** `financeiro:reconcile` — dono e gerência, com MFA.

## 5. As regras

| Regra | O quê |
|---|---|
| RN-FIN-024 | O arquivo é lido pelo mapeamento que o dono confere na prévia; nada é gravado antes |
| RN-FIN-025 | Reimportar é inofensivo, e dinheiro não é sobrescrito em silêncio |
| RN-FIN-026 | Número e data em modo estrito; a hora do arquivo é a do salão |
| RN-FIN-027 | O arquivo não fica guardado: só a impressão SHA-256 e os campos da venda |
| RN-FIN-028 | Linha ruim não derruba o arquivo, e as contas da importação fecham |

## 6. Contrato de API

O projeto não gera OpenAPI; o contrato é este, e `ImportacoesDeExtratoWebIT` o prende. Permissão
`financeiro:reconcile` nas três rotas.

**Prévia** — lê, sugere e mostra; não grava.

```http
POST /api/v1/conciliacao/importacoes/previa
Authorization: Bearer <token-com-mfa>
Content-Type: multipart/form-data; boundary=…

--…
Content-Disposition: form-data; name="arquivo"; filename="stone-setembro.csv"
Content-Type: text/csv

STONE ID;DATA DA VENDA;VALOR BRUTO;STATUS
ST8F3A21;18/09/2026 09:14;180,00;Aprovada
--…--
```

A parte `mapeamento` (JSON) é opcional aqui: sem ela, vem a sugestão; com ela, a prévia aplica o que a
tela escolheu.

```json
{
  "campos": [ { "campo": "ID_NA_ADQUIRENTE", "rotulo": "Stone ID", "obrigatorio": true }, … ],
  "colunas": [ "STONE ID", "DATA DA VENDA", "VALOR BRUTO", "STATUS" ],
  "mapeamento": { "ID_NA_ADQUIRENTE": "STONE ID", "DATA": "DATA DA VENDA",
                  "VALOR_BRUTO": "VALOR BRUTO", "SITUACAO": "STATUS" },
  "formato": "BRASILEIRO",
  "faltando": [],
  "amostra": [ { "linha": 2, "estado": "LIDA", "motivo": null, "idNaAdquirente": "ST8F3A21",
                 "capturadaEm": "2026-09-18T09:14:00", "horaInformada": true,
                 "valorBruto": 180.00, "situacao": "APROVADA", … } ],
  "linhas": 1, "lidas": 1, "ignoradas": 0, "recusadas": 0,
  "jaImportadoEm": null
}
```

**Importar** — o mesmo arquivo, com o mapeamento confirmado (parte `mapeamento`, obrigatória).

```http
POST /api/v1/conciliacao/importacoes
Content-Type: multipart/form-data; boundary=…

(parte "arquivo", como acima)
--…
Content-Disposition: form-data; name="mapeamento"
Content-Type: application/json

{ "colunas": { "ID_NA_ADQUIRENTE": "STONE ID", "DATA": "DATA DA VENDA", "VALOR_BRUTO": "VALOR BRUTO",
               "SITUACAO": "STATUS" }, "formato": "BRASILEIRO" }
--…--
```

**201 Created**, `Location: /api/v1/conciliacao/importacoes/{id}`:

```json
{ "id": "…", "importadaEm": "2026-09-19T15:02:11Z", "linhas": 128, "novas": 118, "canceladas": 2,
  "repetidas": 1, "ignoradas": 5, "recusadas": 2,
  "recusas": [ { "linha": 91, "motivo": "Stone ID ST8F4410 já importado com valor bruto R$ 80,00; o arquivo diz R$ 85,00" } ] }
```

**Histórico** — `GET /api/v1/conciliacao/importacoes?cursor=&limite=`, das mais recentes para trás.

**Erros** (formato de docs/07)

| Status | Código | Quando |
|---|---|---|
| 403 | `ER-INF-ACESSO_NEGADO` | sem `financeiro:reconcile` (a recepção), ou dono e gerente ainda sem MFA |
| 400 | `ER-INF-DADOS_INVALIDOS` | falta a parte `arquivo`, ou a de `mapeamento` na importação; multipart malformado |
| 413 | `ER-INF-ARQUIVO_GRANDE_DEMAIS` | acima de 5 MB |
| 422 | `ER-FIN-ARQUIVO_INVALIDO` | vazio, planilha do Excel, sem colunas, mais de 20.000 vendas |
| 422 | `ER-FIN-MAPEAMENTO_INCOMPLETO` | falta dizer qual coluna é o Stone ID, a data ou o valor bruto |

```json
{ "status": 422, "codigo": "ER-FIN-ARQUIVO_INVALIDO",
  "detail": "é uma planilha do Excel (.xlsx) — exporte a Lista de vendas em CSV" }
```

## 7. Dados — `V58`

- **`importacao_de_extrato`** — uma por importação: impressão SHA-256 do arquivo, nome, quem e quando, as
  contas (com `check` de que fecham) e as primeiras 500 recusas com linha e motivo. Só inserção.
- **`transacao_do_adquirente`** — única por salão, adquirente e Stone ID. A aplicação não tem `update`
  nem `delete`, com exceção de `situacao`, `cancelada_em` e `ultima_importacao_id`: a venda cancelada anda,
  o valor não se reescreve. As chaves para a importação são verificadas no commit, porque a importação
  é gravada depois das linhas, já com as contas finais.
- **`mapeamento_de_extrato`** — o que o salão confirmou por último, pelo nome da coluna.

Nenhuma coluna guarda cartão, nem mascarado.

## 8. Observabilidade

- `Extrato importado: N linhas — N novas, N canceladas, N repetidas, N ignoradas, N recusadas` no log,
  sem dado da venda.
- Auditoria `IMPORTACAO_DE_EXTRATO_CRIADO` com as mesmas contas, e nada da venda.
- O histórico da tela é a observação do dono: recusada em destaque quando há.

## 9. Testes

**Domínio** (unidade): `LeitorDeCsvTest` (9 — codificações, separadores, aspas, título antes do
cabeçalho, Excel, teto), `FormatoDeNumeroTest` (5), `MapeamentoDoExtratoTest` (5),
`InterpretadorDoExtratoTest` (10).

**`ImportarExtratoIT`** (15), com Postgres de verdade e fazendo o que a tela faz — prévia, e importar com
o sugerido: `previa`, `reimportar_nao_duplica`, `cancelada_depois`, `valor_diferente_recusado`,
`cancelada_nao_volta`, `linhas_ruins_nao_derrubam`, `hora_do_salao`, `mapeamento_salvo`,
`mapeamento_incompleto`, `excel_recusado`, `isolamento`, `sem_permissao`, `imutavel`, `historico`,
`auditoria`.

**`ImportacoesDeExtratoWebIT`** (3), pelo multipart de verdade: a gerente importa, a recepção não, e os
limites viram 413, 400 e 422 — nunca 500.

**Front** — `Conciliacao.test.tsx` (9): de onde tirar o arquivo, a prévia sem e com mapeamento, o valor
lido ao lado de cada campo, obrigatório faltando, a recusada na amostra, importar e o resultado, o aviso de
já importado, a planilha recusada, o histórico.

## 10. O que os testes pegaram

- **Formato trocado gravava valor errado, sem erro.** O conversor tirava os separadores antes de
  conferir a forma: "1.234,56" lido como internacional virava 1,23456. `formato_trocado` pegou; agora a
  forma inteira é conferida antes — milhar em grupos de três, até quatro casas —, e "10.50" num arquivo
  brasileiro também é recusado, em vez de virar 1050.
- **Cabeçalho errado em arquivo pequeno.** Com o cabeçalho e uma única venda de tamanho diferente, o
  desempate escolhia a venda como cabeçalho. Agora, no empate, vale a contagem que aparece primeiro.
- **"Já importado?" estourava na primeira importação.** `max()` devolve uma linha com NULL, e o
  `JdbcClient` recusa resultado nulo. Virou "a mais recente, se houver".
- **Arquivo grande demais derrubava a conexão.** O Tomcat recusava e fechava sem ler o resto do corpo, e
  quem enviou via "a conexão caiu" em vez do 413. `server.tomcat.max-swallow-size: 10MB` — lê e descarta até
  10 MB para responder direito.

E a prévia na tela pegou duas coisas que nenhum teste pegaria: o botão "Importar outro arquivo" esticado
até a borda, e o dinheiro nas mensagens de recusa escrito "80" e "85.00" — agora "R$ 80,00".

## 11. Mutações verificadas

Uma rodada, 18 mutações, cada uma sozinha e contra os testes que deveriam matá-la. **18 mortas**, todas
pelo teste esperado.

| # | Mutação | Morta por |
|---|---|---|
| F01 | número convertido antes de conferir a forma | `FormatoDeNumeroTest.formato_trocado_recusa`, `InterpretadorDoExtratoTest.formato_trocado` |
| F02 | data em modo tolerante (31/02 vira 28/02) | `InterpretadorDoExtratoTest.data_estrita` |
| F03 | sem as negativas da situação ("não autorizada" aprovada) | `InterpretadorDoExtratoTest.situacoes` |
| F04 | valor bruto zero aceito | `InterpretadorDoExtratoTest.valor_nao_positivo` |
| F05 | Pix não ignorado | `InterpretadorDoExtratoTest.pix` |
| F06 | desempate do cabeçalho pela maior contagem | `InterpretadorDoExtratoTest.colunas_a_mais` |
| F07 | sem o Windows-1252 | `LeitorDeCsvTest.windows_1252` |
| F08 | planilha do Excel não detectada | `LeitorDeCsvTest.excel_recusado` |
| F09 | mapeamento salvo sem prioridade | `MapeamentoDoExtratoTest.salvo_tem_prioridade` |
| F10 | outro valor vira repetida | `ImportarExtratoIT.valor_diferente_recusado` |
| F11 | cancelada volta aprovada | `ImportarExtratoIT.cancelada_nao_volta` |
| F12 | cancelamento não aplicado | `ImportarExtratoIT.cancelada_depois` |
| F13 | hora em UTC, e não do salão | `ImportarExtratoIT.hora_do_salao` |
| F14 | mapeamento não é salvo | `ImportarExtratoIT.mapeamento_salvo` |
| F15 | prévia sem `@PreAuthorize` | `ImportarExtratoIT.sem_permissao` |
| F16 | a aplicação pode reescrever a venda (sem `revoke`) | `ImportarExtratoIT.imutavel` |
| F17 | sem o tratador de 413 | `ImportacoesDeExtratoWebIT.limites` |
| F18 | Tomcat fecha sem ler o resto (sem `max-swallow-size`) | `ImportacoesDeExtratoWebIT.limites` |

Uma lacuna apareceu **ao planejar** a rodada e virou asserção antes de rodar: sem a detecção do Excel, a
planilha ainda seria recusada — por "não encontrei separador … o arquivo é um CSV?" —, e o teste só
olhava "CSV", que as duas mensagens têm. F08 sobreviveria; agora o teste exige "Excel".

## 12. Pendências

- [ ] **A primeira importação real vai confirmar os apelidos.** Os nomes sugeridos vêm da documentação da
      conciliação Stone e do que é comum em portais; se a Lista de vendas usar outros, a prévia mostra e o
      dono escolhe — e o nome certo entra na lista de apelidos.
- [ ] **API de Conciliação da Stone** (XML diário, com taxas e recebíveis): segundo adapter de ADR-0007,
      quando houver credencial de parceiro. O domínio não muda.
- [x] ~~Casar com os pagamentos~~ — RT-FIN-010. **Taxa e previsão de recebimento** continuam em RT-FIN-012.
- [ ] **Proxy de produção:** quando o Caddy for configurado (RT-INF-009), o limite de corpo não pode ficar
      abaixo de 6 MB, senão o 413 vem dele, sem a mensagem do sistema.
- [ ] **Retenção** de `transacao_do_adquirente` e `importacao_de_extrato`: é dado financeiro; o prazo
      acompanha o da auditoria financeira (5 anos), e o job ainda não existe.
- [ ] **Outro adquirente** é um valor no `check` da `V58` e um apelido a mais — o resto não muda.
