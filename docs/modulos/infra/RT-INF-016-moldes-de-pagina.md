---
id: RT-INF-016
titulo: Moldes de página — uma largura, listas inteiras, e a moldura sem azul
modulo: infra
fase: 7
perfil: leve
status: implementado
depende_de: [RT-INF-013, RT-INF-014]
permissoes: []
eventos: []
regras: []
atualizado_em: 2026-11-15
---

# RT-INF-016 — Moldes de página

## 1. Objetivo

Fazer as treze telas de dentro da casca lerem como **um produto só**, e tirar dele o que o dono
nomeou como "cara de sistema gerado por IA".

## 2. Contexto

Achados de `19-auditoria-de-telas.md` (O1, O2, O5, M1), mais o pedido do dono em 15/11/2026:
*"cor do sistema não fundo azul — não quero ficar parecendo sistema gerado por IA"*.

| # | Defeito | Medido |
|---|---|---|
| O1 | Largura sem regra | Cinco telas esticam até a borda: em 1440px o nome fica a 1.400px do botão "Abrir". Duas param em 1080px, duas em 520–560px |
| O2 | Cada linha de lista é um cartão | Borda + canto + fundo + vão, linha por linha, em nove listas de cinco telas. Pilha de cartões arredondados é o retrato mais comum de interface gerada |
| O5 | Saldo negativo com o número em vermelho | `--status-falta` como texto: o sistema já mediu que nenhum status passa AA como texto |
| M1 | Selo do agente cortado no celular | O selo cai na coluna estreita da hora e transborda sobre a barra de status |
| — | Moldura e porta azuladas | `--espelho` #20282d e a noite da arte (#07052c) liam como "fundo azul" |

## 3. Decisões

**Uma largura para toda tela de lista: 1120px, centrada.** No corpo da página, por padrão — a
tela não precisa pedir. Quem se desenha inteira (agenda, comanda) continua com `.pagina-cheia`;
quem é formulário de uma coluna (configuração, busca de cliente) continua com a própria largura,
mais estreita, que já tinha motivo escrito.

**A lista é uma superfície só.** Uma borda em volta da lista inteira e uma régua fina entre as
linhas — a forma de tabela, não de baralho. A linha perde a própria moldura. É a regra que a
`Secao` já seguia ("seção é estrutura, não caixa"), estendida ao que mora dentro dela.

**Dívida é marca, não cor de letra.** O valor negativo fica em tinta, com o fundo claro da falta
e a barra de 2px — o mesmo vocabulário de status do resto do sistema.

**A moldura é grafite neutro.** `--espelho` passa de #20282d (azulado) a #1c1c1e, e a porta do
sistema e o carregamento usam a mesma superfície. Todos os pares de contraste subiram (texto
14,7 → 16,3:1; fraco 9,2 → 10,1:1). O ícone do app passa a ser o N da arte sobre o grafite, sem
o fundo azul-noite e sem o brilho de borda.

## 4. Atores e permissões

Nenhuma mudança. Nenhuma tela ganhou ou perdeu ação.

## 11. Casos de borda

| Situação | Comportamento |
|---|---|
| Tela com editor aberto dentro da lista | O editor mantém a própria moldura; só a linha perde a dela |
| Linha inativa ("fora do cardápio", "sem acesso") | Continua distinguível pelo próprio tratamento da tela; a régua entre linhas não muda |
| Celular | Mesma regra: a lista vai de borda a borda da coluna, linhas separadas por régua |

## 15. Testes obrigatórios

- [x] Suíte inteira, typecheck, lint e build
- [x] Prévias fotografadas em 1440px e 390px antes e depois: acessos, catálogo, equipe,
      financeiro e agente na coluna de 1120px com listas inteiras; selo do agente na coluna do
      texto no celular; moldura, porta e ícones em grafite

## Pendências

- [ ] Ação principal no alcance do polegar no celular (M3): as ações moram no título de cada
      seção, não no topo; levá-las para a base é mudança de comportamento, rotina própria.
- [ ] Comanda redesenhada (O4, M5) — rotina própria.
- [ ] Fluidez: dado pré-carregado, otimista com desfazer, listas longas leves (F1–F4).
