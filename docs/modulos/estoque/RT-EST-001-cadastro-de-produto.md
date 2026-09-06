---
id: RT-EST-001
titulo: Cadastro de produto (venda, consumo interno ou ambos)
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-INF-002, RT-IAM-007]
permissoes: [estoque:read, estoque:write, produto:price:write]
eventos: []
regras: [RN-EST-001, RN-EST-002, RN-EST-003, RN-EST-004, RN-EST-005]
atualizado_em: 2026-09-05
---

# RT-EST-001 — Cadastro de produto

## 1. Objetivo

Saber o que o salão tem. É a rotina que destrava o item de produto na comanda (RT-ATD-003) e todo o
resto da Fase 3.

## 2. Uma entidade só, com finalidades

O shampoo que a cliente leva para casa e a tinta que a coloração gasta são o mesmo tipo de coisa. E
o **mesmo frasco** pode ser as duas: o salão lava com ele e vende quando alguém pede.

Modelar venda e consumo como entidades separadas obrigaria a cadastrar duas vezes o que é um
produto só — e, pior, o saldo se dividiria entre elas: o frasco vendido no balcão não baixaria do
saldo que a coloração usa, e o inventário nunca fecharia.

## 3. Sem preço é cadastro válido; venda inválida

Mesma filosofia de RN-ATD-005, do outro lado da mesma moeda. O produto pode existir antes de alguém
decidir quanto cobrar — a caixa chegou, alguém cadastrou, a margem se decide depois. Quem recusa é
a venda.

**Consumo interno nunca tem preço.** Não é redundância com a regra acima: um número ali acabaria
lido por alguma tela de venda, e a tinta apareceria no balcão. O `check` do banco garante, e o caso
de uso recusa antes com uma mensagem que diz o que fazer.

## 4. Quem cadastra não precifica — e o mapa já dizia isso

`estoque:write` e `produto:price:write` existem separadas desde RT-IAM-007, e a recepção tem a
primeira e não a segunda. É a divisão real do salão: quem recebe a mercadoria cadastra; quem decide
margem precifica.

Isso decidiu três coisas de desenho:

1. **O preço tem rota própria.** No corpo do cadastro, a rota inteira exigiria a permissão de
   preço — e a recepção deixaria de conseguir cadastrar.
2. **Informar preço no cadastro exige as duas permissões**, conferidas na mesma expressão. Sem
   isso, o campo opcional seria o furo: bastaria preencher.
3. **Virar consumo interno com preço cadastrado é recusado, não limpo.** Limpar junto faria a
   recepção apagar um preço pela porta dos fundos, e a permissão que separa quem precifica de quem
   cadastra deixaria de valer por causa de um efeito colateral. Custa um passo a mais — alguém que
   possa mexer em preço o remove antes —, e é o passo que mantém a fronteira honesta.

## 5. Sem vigência de preço, ao contrário do serviço

Preço de serviço tem histórico (RT-CAT-002) porque virou rotina própria, com `vigente_de` e tudo. O
produto não tem: o preço mora no próprio produto.

O que a vigência daria — *por quanto se vendia em março?* — o snapshot em
`item_comanda.preco_unitario` já garante para toda venda **fechada**. O que se perde é a pergunta
sobre o que **não** foi vendido: "por quanto este shampoo estava anunciado em março" não tem
resposta. É assimetria consciente, e está nas pendências.

## 6. Unidade é lista fechada

`quantidade` é numérica em toda parte. `"un"`, `"und"` e `"unid"` digitados à mão viram três
unidades diferentes, e a soma de saldo passa a depender de como cada pessoa escreveu.

Tinta se mede em gramas, ox em mililitros, escova em unidade — e sem isso a ficha técnica
(RT-EST-007) não tem como dizer "40 g de tinta". **Não há conversão automática** entre elas: um
produto é cadastrado na unidade em que o salão o usa, e converter grama em quilo sozinho é o tipo
de esperteza que erra por mil quando alguém cadastra errado.

## 7. Produto não se apaga

Ele aparece em lote, movimento e item de comanda de meses atrás. Apagá-lo deixaria o histórico
apontando para o nada. Desativar tira da lista de quem vende e mantém a linha que explica a venda
antiga.

## 8. `vendavel` é derivado no servidor

`ativo` **e** não é consumo interno **e** tem preço. A tela recebe a resposta pronta, num campo do
`ProdutoResponse`, porque compô-la no front recriaria no JavaScript a regra que mora aqui — e as
duas cópias divergiriam na primeira mudança. É a mesma razão de `Capacidades` derivar menus no
servidor (RT-IAM-006).

> O método derivado do record não vira campo JSON — Jackson serializa só os componentes. Foi o
> teste de contrato que mostrou isso, e a saída foi um record de resposta explícito na borda, como
> `arquivos` já fazia.

## 9. Testes

- [x] `CadastroDeProdutoIT` — 16: produto de venda nasce vendável · consumo interno existe sem
      preço · **o mesmo frasco pode ser as duas coisas** · **sem preço é cadastro válido** ·
      **o que não se vende não tem preço** · nome repetido · nome vazio e preço negativo ·
      **cadastrar com preço exige a permissão de preço** · atualizar sobe a versão · **salvar por
      cima de tela velha** · **virar consumo interno com preço é recusado, não limpo** · definir e
      remover preço · precificar consumo interno · **a lista do balcão** · a tela de cadastro vê o
      desativado · um salão não vê o produto do outro, e o mesmo nome cabe nos dois
- [x] `ProdutosWebIT` — 6: a recepção cadastra · **a recepção não precifica, nem no cadastro nem
      depois** · unidade fora da lista para na borda · **conflito de versão responde 409** · a
      lista do balcão não traz consumo interno · sem token não abre

## 10. Pendências

- [ ] **Não há `ProdutoApi`.** Nenhum módulo consome produto ainda; o contrato nasce com
      RT-ATD-003, que é quem vai precisar de `porId` e do preço. Criá-lo agora seria uma interface
      sem chamador
- [ ] **Sem código de barras.** RT-EST-009 (leitor) precisa da coluna, e ela não existe — é
      cadastro puro, e entra com a rotina que a usa
- [ ] **Sem estoque mínimo.** RT-EST-008 (alertas) é quem traz o campo
- [ ] **Preço de produto não tem histórico.** Serviço tem (RT-CAT-002); a assimetria é do plano,
      não do modelo, e o custo é não saber por quanto um produto **não vendido** estava anunciado
- [ ] **Não há saldo.** O produto existe e não tem quantidade nenhuma: lote é RT-EST-002 e saldo é
      RT-EST-003. Até lá, cadastrar produto não diz se há um no armário
- [ ] **Não há tela.** As rotas existem e o front não as usa
- [ ] **`AtualizarProduto` não permite trocar só um campo.** É substituição inteira, e a tela
      precisa mandar tudo — o que é aceitável numa tela de cadastro e ruim numa correção rápida
