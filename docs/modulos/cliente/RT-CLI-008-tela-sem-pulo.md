---
id: RT-CLI-008
titulo: A tela de clientes — sem pulo, com rosto
modulo: cliente
fase: 7
perfil: leve
status: implementado
depende_de: [RT-CLI-006, RT-INF-017]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-CLI-008 — A tela de clientes, sem pulo e com rosto

## 1. Objetivo

Melhorar "a forma de aparecer" da busca de clientes, a pedido do dono, sem mudar a busca: o
servidor continua decidindo se o termo é nome ou fim de telefone (RN-CLI-009), e a ficha
continua sendo endereço (`?cliente=`).

## 2. Contexto — fotografado em 1440px

| Defeito | Consequência |
|---|---|
| **A tela pulava ao abrir uma ficha**: a busca saía do centro (640px) para a esquerda (360px) e a lista encolhia no clique | Tudo muda de lugar no momento em que a pessoa escolhe — e quem procura a próxima cliente já não acha o campo onde estava |
| Linhas só com texto, e o nome selecionado **sublinhado** | O selecionado lia como link; nenhuma âncora visual para o olho correr a lista |
| "Não achou": o cadastro rápido aparecia **abaixo da dobra**, embaixo do aviso | Cadastrar é o próximo passo óbvio, e ficava a uma rolagem de distância |
| Ficha com rótulos em versalete ("TELEFONE", "E-MAIL") e "Corrigir ficha" como link sublinhado | Formulário de dez anos atrás; a ação mais usada da ficha parecia nota de rodapé |

## 3. Decisões

**Duas colunas fixas desde o início — lista à esquerda, ficha à direita** (a forma de Contatos e de
qualquer caixa de e-mail). Nada muda de lugar ao clicar: só o conteúdo da direita troca. Sem
ficha aberta, a direita diz o que fazer; sem resultado, a direita **é** o cadastro rápido, ao lado
do aviso de "ninguém com esse nome".

**Cada linha tem rosto**: as iniciais num círculo neutro (cor é de status, não de pessoa), o nome
como a recepção chama, e numa linha só o nome de documento e o telefone. Selecionada: o fundo e a
barra de tinta, como em todo o sistema — sem sublinhado.

**A ficha abre com o mesmo rosto, maior**, e "Corrigir ficha" vira botão ao lado do fechar.
Rótulos em caixa normal.

**Celular**: uma coluna; abrir a ficha mostra só a ficha (o fechar volta para a lista) — como um
app de contatos.

## 15. Testes obrigatórios

- [x] `Clientes.test.tsx` e `Ficha.test.tsx` (24) sem mudar o que verificam — o rosto entra com
      `aria-hidden`, e o nome falado de cada linha continua o mesmo
- [x] Suíte inteira, typecheck, lint e build
- [x] Fotografado nos quatro estados (vazio, lista, ficha, não achou) e no celular (lista e ficha)

**Ajustes que a foto pediu:** a busca passou a morar na coluna da lista (ela filtra a lista) e a
ficha sobe ao lado dela até o topo; "Corrigir ficha" precisava da variante `discreta` para ter
borda de botão; o limite de 620px de dentro da ficha cortava as réguas antes da borda do cartão;
no celular, o botão desce para a linha de baixo em vez de quebrar o nome em duas.
