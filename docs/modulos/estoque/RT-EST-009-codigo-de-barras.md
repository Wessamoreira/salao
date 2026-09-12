---
id: RT-EST-009
titulo: Leitor de código de barras (HID: input focado + Enter)
modulo: estoque
fase: 3
perfil: completo
status: implementado
depende_de: [RT-EST-001, RT-EST-002]
permissoes: [estoque:read, estoque:write]
eventos: []
regras: [RN-EST-026, RN-EST-027, RN-EST-028]
atualizado_em: 2026-09-12
---

# RT-EST-009 — Leitor de código de barras

## 1. Objetivo

Bipar a embalagem e o produto certo aparecer selecionado, sem ninguém procurar num select de cem
linhas com a mão ocupada.

## 2. Não há nada a integrar: um leitor HID é um teclado

O leitor comum de balcão se apresenta ao sistema operacional como **teclado**. Ele não tem driver,
SDK, porta serial nem evento próprio: digita os caracteres do código muito rápido e termina com
Enter.

Isso muda o que a rotina é. Não existe "integração com o leitor" — existe:

1. uma coluna onde o código mora (`produto.codigo_barras`);
2. uma busca exata por ele;
3. **um campo que está com o foco no instante em que a pessoa aperta o gatilho.**

O item 3 é o requisito de verdade, e é o que quase sempre falha. Se o campo não estiver focado, os
caracteres vão para um `input` vizinho, para o atalho de uma tecla, ou para o nada. A pessoa bipa,
não acontece nada, e bipa de novo mais forte. É o modo de falha real de conferência com leitor, e
nenhum log registra.

### Duas configurações do leitor que o sistema exige

O leitor precisa estar configurado com **sufixo Enter (CR)**. É o padrão de fábrica na maioria,
mas alguns saem com Tab, e alguns com sufixo nenhum. Sem o terminador não há como saber que o
código acabou — medir pausa entre teclas seria adivinhar.

Não tratamos Tab como terminador de propósito: Tab é navegação, e capturá-lo tornaria o formulário
inescapável pelo teclado.

## 3. Segurar o foco é decisão de quem monta a tela

O componente nasceu com uma versão só — campo que reconquista o foco sozinho — e isso estava
errado para a tela em que ele foi usado primeiro.

| Onde | Comportamento | Por quê |
|---|---|---|
| tela **dedicada** a bipar (inventário, entrada de nota) | `segurarFoco`: foca ao montar, volta a focar ao perder | perder o foco é perder o bipe |
| tela **compartilhada** (lançar produto na comanda) | não toma o foco de ninguém | há select de serviço e de profissional ao lado; refocar travaria a tela inteira |

Daí a prop `segurarFoco`, **com padrão `false`** — o padrão é o que não estraga a tela alheia. O
botão *Pausar* só existe quando ela é `true`, porque só aí há foco sendo tomado de alguém.

E o estado é dito em **texto**, não só pela borda que acende: para quem bipou, *"não aconteceu
nada"* e *"não estava escutando"* são a mesma experiência, e só a palavra os separa.

## 4. Sem validação de dígito verificador — RN-EST-026

A tentação é validar EAN-13 no cadastro. Seria errado, e o custo apareceria no segundo dia:
produto importado, produto reetiquetado pelo distribuidor com código interno de 5 dígitos,
etiqueta Code 128 impressa pelo próprio salão para o que chegou sem código.

Recusar esses cadastros não protege ninguém — empurra a pessoa a inventar um código que passe na
validação, e aí o campo mente. O que importa é o guardado ser **exatamente** o que o leitor emite.

Só o espaço em branco é proibido: nenhum padrão o contém, é sempre sujeira de recorte e cola, e um
código com espaço nunca casaria com bipe nenhum — sendo indistinguível a olho do certo.

## 5. O bipe escolhe; lançar é do humano — RN-EST-028

Na comanda, ler o código **seleciona** o produto. Não lança.

Lançar no gatilho seria mais rápido e erraria mais: o lançamento dá baixa FEFO no estoque
(RT-EST-004) e entra na conta da cliente, então desfazer um bipe torto custa um estorno. E a
quantidade quase nunca é 1 em venda de balcão.

O ponto que só apareceu ao abrir o navegador: **código desconhecido tem de limpar a seleção.** Sem
isso, bipar o produto A (seleciona), depois bipar o B que não tem código cadastrado, deixava o
aviso na tela e o **A ainda armado** no botão — e o clique seguinte lançava o item errado, no fluxo
em que ninguém está olhando a tela.

O aviso mostra **qual** código foi lido, porque "não achei" tem duas saídas diferentes: leitura
errada (bipar de novo) ou produto sem código cadastrado (escolher na lista e cadastrar depois).

## 6. A busca do bipe não vai ao servidor — e a rota existe mesmo assim

Na comanda, o código é resolvido contra a lista de vendáveis **já carregada** para desenhar o
select (`produtoDoCodigo`). Três razões: o dado já está na mão, o bipe tem de responder no ritmo
do gatilho, e a internet do salão cai.

`GET /v1/produtos/por-codigo-barras` existe para as telas onde o produto pode estar **fora** da
lista do balcão — inventário, entrada de nota, consulta de preço de insumo. Ela devolve **404**
quando não existe, e não lista vazia: quem bipou precisa saber que aquele código não está
cadastrado, e uma lista de zero itens obriga o front a interpretar.

## 7. Duas correções de contrato feitas aqui

- `ProdutoResponse` não expunha `codigoBarras`. A coluna existia, a busca existia, e a tela não
  tinha como saber o código de nada.
- a rota do leitor devolvia o `ProdutoResumo` **cru**, enquanto todas as outras devolvem
  `ProdutoResponse`. O `Money` do domínio serializa como `{"valor": …}` e o resto da API manda
  número — a tela receberia **duas formas** do mesmo produto, e a que quebrasse seria a menos
  usada.

## 8. A checagem de duplicado vem antes do UPDATE, não no `catch`

A primeira versão consultava o dono do código **dentro** do `catch (DuplicateKeyException)`, para
dizer *"o código já é da Máscara"*. Não funciona: depois de uma violação de constraint o
PostgreSQL **aborta a transação**, e qualquer comando seguinte falha com `25P02`. O resultado foi
um `UncategorizedSQLException` escapando no lugar do erro de domínio — e o teste pegou.

A consulta foi para antes do UPDATE. O `catch` ficou, sem consulta, com mensagem genérica: ele
cobre só a corrida entre a checagem e a escrita. **A correção continua sendo do índice único**; a
consulta prévia existe apenas para a mensagem ser boa no caso comum.

É a mesma família de lição de `EstornoIT`: *anotação e checagem só valem onde ainda há transação
viva.*

## 9. Testes obrigatórios

- [x] `CodigoDeBarrasIT` — 12 cenários: bipar acha o produto · **dígito verificador inválido é
  aceito** · **código interno de 5 dígitos e Code 128 alfanumérico são aceitos** · o código é
  aparado, então CR/LF do leitor não estraga a busca · **espaço no meio é recusado** ·
  **código duplicado no mesmo salão é recusado, com o nome de quem já o tem** · o mesmo EAN em
  salões diferentes convive · remover o código libera para outro produto · corpo vazio remove ·
  código desconhecido dá 404 · produto inexistente · sem `estoque:write` não define.
- [x] `LeitorDeCodigo.test.tsx` — 11 cenários: Enter entrega o código aparado e limpa o campo ·
  Enter com campo vazio não é leitura (leitor emite o terminador duas vezes) · tecla comum não
  fecha · **não rouba o foco por padrão** · **com `segurarFoco` nasce focado** · pausar diz o
  estado em palavra · retomar devolve o foco · sem `segurarFoco` não há botão de pausar · ocupado
  bloqueia e avisa · duas leitoras na mesma tela não colidem de `id` · **o Enter chega cancelado**
  (é o que impede o `form` em volta de enviar).
- [x] `Comandas.test.tsx` — bipar escolhe o produto e não lança · código sem dono limpa a seleção
  e desarma o botão.

### O teste que não testava nada

A primeira versão de *"o Enter não submete o formulário"* montava um `<form onSubmit>` e afirmava
que ele não foi chamado. **Passava com e sem o `preventDefault`**: jsdom não implementa submissão
implícita por Enter. A mutação — tirar a linha e ver o teste continuar verde — mostrou isso. A
asserção passou a ser sobre `defaultPrevented`, que é o que de fato impede o envio no navegador.

## 10. Pendências

- [ ] **Não há tela de estoque.** O leitor está montado só na comanda; inventário (RT-EST-006) e
      entrada de lote (RT-EST-002) são onde `segurarFoco` ganha uso, e ambos ainda não têm tela.
      É o motivo de o modo dedicado existir sem consumidor por enquanto.
- [ ] **O cadastro do código não tem tela.** A rota existe (`PUT /v1/produtos/{id}/codigo-barras`)
      e o jeito natural de usá-la é bipar dentro do cadastro do produto — o que só existirá com a
      tela de estoque.
- [ ] **Não há bipe por lote.** `lote.codigo` é o código do lote, não o EAN do produto; conferir
      lote por leitor pede uma etiqueta impressa pelo salão e um segundo leitor na tela.
- [ ] **Sufixo do leitor não é configurável.** Só Enter. Um salão com leitor configurado para Tab
      precisa reconfigurar o aparelho.
- [ ] **Nada impede cadastrar o código de um produto no outro.** A unicidade impede dois produtos
      com o mesmo código, não um código trocado — e trocado, o bipe lança o item errado
      silenciosamente. Conferência de cadastro é trabalho humano aqui.
