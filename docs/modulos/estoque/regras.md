# Regras de negócio — módulo `estoque`

---

### RN-EST-001 — Venda, consumo interno ou ambos: uma entidade só

**Enunciado.** Produto tem uma `finalidade`: `VENDA`, `CONSUMO_INTERNO` ou `AMBOS`. Não há
entidades separadas para o que se vende e o que se gasta.

**Motivo.** O mesmo frasco de shampoo lava o cabelo aqui e vai para a sacola quando alguém pede.
Duas tabelas obrigariam a cadastrar duas vezes o que é um produto só — e o saldo se dividiria entre
elas: o frasco vendido no balcão não baixaria do saldo que a coloração usa, e o inventário nunca
fecharia.

**Onde é garantida.** `produto.finalidade` (`check`) · `FinalidadeDoProduto.podeSerVendido`.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.ambos`,
`CadastroDeProdutoIT.consumo_interno`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-002 — Sem preço é cadastro válido; consumo interno nunca tem preço

**Enunciado.** Produto de venda pode existir sem preço — não é vendável até ter um. Produto de
`CONSUMO_INTERNO` **não pode** ter preço de venda, nem no cadastro, nem depois.

**Motivo.** A caixa chega, alguém cadastra, e a margem se decide depois. Recusar o cadastro por
falta de preço faria a recepção inventar um número para conseguir salvar — que é exatamente o
número errado que apareceria na venda. Quem recusa é a venda, como em RN-ATD-005.

E o que não se vende não tem preço: um número ali acabaria lido por alguma tela de venda, e a tinta
apareceria no balcão.

**Onde é garantida.** `constraint consumo_interno_nao_tem_preco` ·
`CadastrarProdutoUseCase.ProdutoValido.precoCabeNaFinalidade` · `ProdutoResumo.vendavel`.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.sem_preco_e_cadastro_valido`,
`CadastroDeProdutoIT.consumo_interno_nao_tem_preco`,
`CadastroDeProdutoIT.precificar_consumo_interno`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-003 — Quem cadastra não precifica, e mudar a finalidade não apaga o preço

**Enunciado.** Cadastrar e corrigir produto exige `estoque:write`; definir ou remover o preço de
venda exige `produto:price:write`. Informar preço no cadastro exige as duas. Tornar
`CONSUMO_INTERNO` um produto que tem preço é **recusado**, não limpo.

**Motivo.** As duas permissões existem separadas desde RT-IAM-007, e a recepção tem a primeira e
não a segunda: quem recebe a mercadoria cadastra, quem decide margem precifica.

Se o preço entrasse no mesmo ato sem checagem própria, a separação seria decorativa — bastaria
preencher o campo opcional. E se mudar a finalidade limpasse o preço junto, a recepção o apagaria
pela porta dos fundos: a permissão deixaria de valer por causa de um efeito colateral.

O passo a mais — alguém que possa mexer em preço o remove antes — é o que mantém a fronteira
honesta.

**Onde é garantida.** `@PreAuthorize` de `CadastrarProdutoUseCase` (com a condição sobre o preço) ·
`DefinirPrecoDeVendaUseCase` · `AtualizarProdutoUseCase` recusa a virada · rota de preço separada.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.recepcao_cadastra_sem_preco`,
`CadastroDeProdutoIT.virar_consumo_interno_com_preco`, `ProdutosWebIT.recepcao_nao_precifica`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-004 — A unidade é lista fechada, e não há conversão automática

**Enunciado.** `unidade` é uma de `UN`, `ML`, `L`, `G`, `KG`. Não há texto livre e não há conversão
entre unidades.

**Motivo.** `quantidade` é numérica em toda parte. `"un"`, `"und"` e `"unid"` digitados à mão viram
três unidades diferentes, e a soma de saldo passa a depender de como cada pessoa escreveu.

Sem unidade, a ficha técnica (RT-EST-007) não tem como dizer "40 g de tinta". E converter grama em
quilo automaticamente é o tipo de esperteza que erra por mil quando alguém cadastra errado — o
produto é cadastrado na unidade em que o salão o usa.

**Onde é garantida.** `produto.unidade` (`check`) · `UnidadeDeMedida` · validação de borda no
controller.
**Rotinas.** RT-EST-001 · **Teste.** `ProdutosWebIT.unidade_invalida`
**Configurável?** Não · **Origem.** 2026-09-05

---

### RN-EST-005 — Produto não se apaga; desativa-se

**Enunciado.** Não há remoção de produto. `ativo = false` tira da lista de venda e do cadastro
corrente; a listagem completa continua mostrando.

**Motivo.** O produto aparece em lote, movimento e item de comanda de meses atrás. Apagá-lo
deixaria o histórico apontando para o nada — e o item da comanda guarda a descrição em snapshot
justamente porque a linha precisa continuar legível, mas o saldo e o custo não são snapshot.

**Onde é garantida.** Não existe rota de remoção · `DefinirAtivacaoDoProdutoUseCase` ·
`idx_produto_ativo`.
**Rotinas.** RT-EST-001 · **Teste.** `CadastroDeProdutoIT.lista_de_cadastro`
**Configurável?** Não · **Origem.** 2026-09-05
